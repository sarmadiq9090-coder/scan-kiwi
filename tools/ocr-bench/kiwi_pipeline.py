# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Reference implementation of the app's fused OCR pipeline ("Accurate" mode, engine id pp-fused).

Every step mirrors the Kotlin code in app/src/main/java/com/fastkiwi/scan/ocr/fused/ line by line:
  PpDetector.kt         detector pre-processing (gray -> 3 x H x W, long side <= 960, multiple of 32)
                        and DB post-processing (threshold, 2x2 dilation, 8-connected components,
                        PCA-rotated rectangle, rectangle-mean score, unclip)
  PpArabicRecognizer.kt line crop (rotated rectangle sampled straight to 48 px height), CTC greedy
                        decode with the model's dictionary, visual -> logical order (Bidi.kt)
  TesseractRecognizer.kt Tesseract fast re-reads the same line box (PSM 7) on the illumination-
                        normalised page (port of ImageFilters.normalizedGray)
  OcrFusion.kt          per line: Tesseract's text wins when it holds more numbers (and, with
                        latin_rule, clearly more Latin letters)
  ArabicPostProcessor.kt Arabic comma between digits -> Arabic decimal separator
  LineGrouper.kt        rows top to bottom; cells right to left on Arabic pages, left to right on
                        pages with more Latin than Arabic letters

The model runtime is pluggable: ONNX Runtime (the roadmap section 3 numbers) or MNN (the app's runtime),
so the parity test runs the same logic over both. Pure numpy apart from the runtimes; no OpenCV in the
pipeline logic, so nothing here depends on a library the app does not have.
"""
from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

import numpy as np

DET_MAX_SIDE = 960
DET_THRESH = 0.3
BOX_THRESH = 0.5
UNCLIP_RATIO = 1.6
MIN_SIZE = 3
MAX_CANDIDATES = 1000
MAX_TILT_DEG = 10.0
REC_H = 48
REC_MIN_W = 320
REC_PAD = 0.25      # zero padding after the line, as a share of its width (the model reads badly when a line fills the tensor)
REC_PAD_MIN = 96
TEXT_SCORE = 0.3
TESS_PAD = 0.25

NUM = re.compile(r"[0-9٠-٩]+(?:[.,،٫٬/:\-][0-9٠-٩]+)*")
AR_COMMA_BETWEEN_DIGITS = re.compile(r"(?<=[0-9٠-٩])،(?=[0-9٠-٩])")


# --------------------------------------------------------------------------- image helpers

def luma_from_bgr(bgr: np.ndarray) -> np.ndarray:
    """ImageFilters.luma: (r*77 + g*150 + b*29) >> 8."""
    b = bgr[..., 0].astype(np.int32)
    g = bgr[..., 1].astype(np.int32)
    r = bgr[..., 2].astype(np.int32)
    return ((r * 77 + g * 150 + b * 29) >> 8).astype(np.uint8)


def normalized_gray(gray: np.ndarray) -> np.ndarray:
    """Port of ImageFilters.normalizeIllumination (== normalizedGray on the luma plane)."""
    h, w = gray.shape
    cell = max(8, max(w, h) // 64)
    gw, gh = (w + cell - 1) // cell, (h + cell - 1) // cell
    grid = np.zeros((gh, gw), np.float32)
    for gy in range(gh):
        for gx in range(gw):
            blk = gray[gy * cell:min(h, gy * cell + cell):2, gx * cell:min(w, gx * cell + cell):2].ravel()
            n = blk.size
            hist = np.bincount(blk, minlength=256)
            target = int(n * 0.90)
            acc = np.cumsum(hist)
            idx = np.nonzero(acc > target)[0]
            grid[gy, gx] = float(idx[0]) if idx.size else 255.0
    pad = np.pad(grid, 1, mode="edge")
    m = np.max(np.stack([pad[dy:dy + gh, dx:dx + gw] for dy in range(3) for dx in range(3)]), axis=0)
    cur = m.astype(np.float32)
    for _ in range(2):
        p = np.pad(cur, 1, mode="edge")
        s = np.zeros_like(cur)
        for dy in range(3):
            for dx in range(3):
                s = s + p[dy:dy + gh, dx:dx + gw]
        cur = (s / np.float32(9.0)).astype(np.float32)
    cur = np.maximum(cur, np.float32(24.0))
    xs = np.arange(w, dtype=np.float32)
    ys = np.arange(h, dtype=np.float32)
    fx = np.clip((xs + 0.5) / cell - 0.5, 0, gw - 1).astype(np.float32)
    fy = np.clip((ys + 0.5) / cell - 0.5, 0, gh - 1).astype(np.float32)
    x0 = fx.astype(np.int32); y0 = fy.astype(np.int32)
    x1 = np.minimum(gw - 1, x0 + 1); y1 = np.minimum(gh - 1, y0 + 1)
    ax = (fx - x0.astype(np.float32))[None, :]; ay = (fy - y0.astype(np.float32))[:, None]
    top = cur[y0][:, x0] * (1 - ax) + cur[y0][:, x1] * ax
    bot = cur[y1][:, x0] * (1 - ax) + cur[y1][:, x1] * ax
    bg = top * (1 - ay) + bot * ay
    out = (gray.astype(np.float32) / bg * np.float32(255.0)).astype(np.int32)
    return np.clip(out, 0, 255).astype(np.uint8)


def round_half_up(x: float) -> int:
    return int(math.floor(x + 0.5))


def resize_bilinear(gray: np.ndarray, rw: int, rh: int) -> np.ndarray:
    """Half-pixel-centre bilinear resize, float32 result in 0..255 (Kotlin: Resample.bilinear)."""
    h, w = gray.shape
    sx = np.float32(w / rw); sy = np.float32(h / rh)
    xs = np.clip((np.arange(rw, dtype=np.float32) + np.float32(0.5)) * sx - np.float32(0.5), 0, w - 1).astype(np.float32)
    ys = np.clip((np.arange(rh, dtype=np.float32) + np.float32(0.5)) * sy - np.float32(0.5), 0, h - 1).astype(np.float32)
    x0 = xs.astype(np.int32); y0 = ys.astype(np.int32)
    x1 = np.minimum(w - 1, x0 + 1); y1 = np.minimum(h - 1, y0 + 1)
    ax = (xs - x0.astype(np.float32))[None, :]; ay = (ys - y0.astype(np.float32))[:, None]
    g = gray.astype(np.float32)
    top = g[y0][:, x0] * (1 - ax) + g[y0][:, x1] * ax
    bot = g[y1][:, x0] * (1 - ax) + g[y1][:, x1] * ax
    return (top * (1 - ay) + bot * ay).astype(np.float32)


# --------------------------------------------------------------------------- detector

@dataclass
class DetSize:
    width: int
    height: int


def det_size(w: int, h: int) -> DetSize:
    s = min(1.0, DET_MAX_SIDE / max(w, h))
    rw = max(32, round_half_up(w * s / 32) * 32)
    rh = max(32, round_half_up(h * s / 32) * 32)
    return DetSize(rw, rh)


def det_input(gray: np.ndarray) -> np.ndarray:
    h, w = gray.shape
    ds = det_size(w, h)
    r = resize_bilinear(gray, ds.width, ds.height)
    v = (r / np.float32(255.0) - np.float32(0.5)) / np.float32(0.5)
    return np.repeat(v[None, None, :, :], 3, axis=1).astype(np.float32)


@dataclass
class TextBox:
    """A rotated rectangle in page pixels (edge coordinates: the page spans 0..W, 0..H)."""
    cx: float
    cy: float
    w: float
    h: float
    angle: float  # radians, the direction of the box's width
    score: float

    def corners(self):
        ux, uy = math.cos(self.angle), math.sin(self.angle)
        vx, vy = -uy, ux
        hw, hh = self.w / 2, self.h / 2
        return [(self.cx - hw * ux - hh * vx, self.cy - hw * uy - hh * vy),
                (self.cx + hw * ux - hh * vx, self.cy + hw * uy - hh * vy),
                (self.cx + hw * ux + hh * vx, self.cy + hw * uy + hh * vy),
                (self.cx - hw * ux + hh * vx, self.cy - hw * uy + hh * vy)]

    def bounds(self, page_w: int, page_h: int):
        pts = self.corners()
        x0 = max(0.0, min(p[0] for p in pts)); x1 = min(float(page_w), max(p[0] for p in pts))
        y0 = max(0.0, min(p[1] for p in pts)); y1 = min(float(page_h), max(p[1] for p in pts))
        return x0, y0, x1, y1


def components(mask: np.ndarray):
    """8-connected components; each as (ys, xs) arrays, ordered by their first pixel in raster order."""
    import cv2  # labelling only; the result (sets of pixels) is unique, so the order rule below makes it exact
    n, lab = cv2.connectedComponents(mask.astype(np.uint8), connectivity=8)
    flat = lab.ravel()
    order = np.argsort(flat, kind="stable")
    sorted_lab = flat[order]
    starts = np.searchsorted(sorted_lab, np.arange(1, n))
    ends = np.searchsorted(sorted_lab, np.arange(1, n), side="right")
    w = mask.shape[1]
    comps = []
    for s, e in zip(starts, ends):
        idx = order[s:e]
        comps.append((int(idx.min()), idx // w, idx % w))
    comps.sort(key=lambda c: c[0])
    return [(ys, xs) for _, ys, xs in comps]


def db_boxes(prob: np.ndarray, page_w: int, page_h: int) -> list[TextBox]:
    rh, rw = prob.shape
    b = prob > DET_THRESH
    d = b.copy()
    d[1:, :] |= b[:-1, :]
    d[:, 1:] |= b[:, :-1]
    d[1:, 1:] |= b[:-1, :-1]
    out = []
    fx, fy = page_w / rw, page_h / rh
    for k, (ys, xs) in enumerate(components(d)):
        if k >= MAX_CANDIDATES:
            break
        xs_f = xs.astype(np.float64); ys_f = ys.astype(np.float64)
        mx, my = xs_f.mean(), ys_f.mean()
        dx, dy = xs_f - mx, ys_f - my
        cxx, cyy, cxy = (dx * dx).mean(), (dy * dy).mean(), (dx * dy).mean()
        theta = 0.5 * math.atan2(2 * cxy, cxx - cyy)
        if abs(theta) > math.radians(MAX_TILT_DEG):
            theta = 0.0
        ux, uy = math.cos(theta), math.sin(theta)
        pu = dx * ux + dy * uy
        pv = -dx * uy + dy * ux
        umin, umax, vmin, vmax = pu.min(), pu.max(), pv.min(), pv.max()
        bw, bh = umax - umin + 1.0, vmax - vmin + 1.0
        if min(bw, bh) < MIN_SIZE:
            continue
        cu, cv = (umin + umax) / 2, (vmin + vmax) / 2
        ccx, ccy = mx + cu * ux - cv * uy, my + cu * uy + cv * ux  # pixel-centre coordinates
        # score: mean probability over the pixels whose centres lie inside the rectangle
        r = 0.5 * math.hypot(bw, bh)
        x0, x1 = max(0, int(math.floor(ccx - r))), min(rw - 1, int(math.ceil(ccx + r)))
        y0, y1 = max(0, int(math.floor(ccy - r))), min(rh - 1, int(math.ceil(ccy + r)))
        gy, gx = np.mgrid[y0:y1 + 1, x0:x1 + 1]
        qx, qy = gx - ccx, gy - ccy
        inside = (np.abs(qx * ux + qy * uy) <= bw / 2) & (np.abs(-qx * uy + qy * ux) <= bh / 2)
        cnt = int(inside.sum())
        if cnt == 0:
            continue
        score = float(prob[y0:y1 + 1, x0:x1 + 1][inside].astype(np.float64).sum() / cnt)
        if score < BOX_THRESH:
            continue
        dist = bw * bh * UNCLIP_RATIO / (2 * (bw + bh))
        ew, eh = bw + 2 * dist, bh + 2 * dist
        if min(ew, eh) < MIN_SIZE + 2:
            continue
        pcx, pcy = (ccx + 0.5) * fx, (ccy + 0.5) * fy
        pw = ew * math.hypot(ux * fx, uy * fy)
        ph = eh * math.hypot(-uy * fx, ux * fy)
        ang = math.atan2(uy * fy, ux * fx)
        if pw <= 3 or ph <= 3:
            continue
        out.append(TextBox(pcx, pcy, pw, ph, ang, score))
    return out


# --------------------------------------------------------------------------- recogniser

def rec_width(box: TextBox) -> tuple[int, int]:
    ratio = box.w / box.h
    resized = max(1, int(math.ceil(REC_H * ratio)))
    img_w = max(REC_MIN_W, resized + max(REC_PAD_MIN, int(resized * REC_PAD)))
    return resized, img_w


def rec_input(gray: np.ndarray, box: TextBox) -> np.ndarray:
    h, w = gray.shape
    rw, img_w = rec_width(box)
    ux, uy = math.cos(box.angle), math.sin(box.angle)
    vx, vy = -uy, ux
    j = (np.arange(rw, dtype=np.float32) + np.float32(0.5)) / np.float32(rw) * np.float32(box.w) - np.float32(box.w / 2)
    i = (np.arange(REC_H, dtype=np.float32) + np.float32(0.5)) / np.float32(REC_H) * np.float32(box.h) - np.float32(box.h / 2)
    a = j[None, :]; bb = i[:, None]
    px = (np.float32(box.cx) + a * np.float32(ux) + bb * np.float32(vx) - np.float32(0.5)).astype(np.float32)
    py = (np.float32(box.cy) + a * np.float32(uy) + bb * np.float32(vy) - np.float32(0.5)).astype(np.float32)
    px = np.clip(px, 0, w - 1); py = np.clip(py, 0, h - 1)
    x0 = px.astype(np.int32); y0 = py.astype(np.int32)
    x1 = np.minimum(w - 1, x0 + 1); y1 = np.minimum(h - 1, y0 + 1)
    ax = px - x0.astype(np.float32); ay = py - y0.astype(np.float32)
    g = gray.astype(np.float32)
    top = g[y0, x0] * (1 - ax) + g[y0, x1] * ax
    bot = g[y1, x0] * (1 - ax) + g[y1, x1] * ax
    v = (top * (1 - ay) + bot * ay).astype(np.float32)
    v = (v / np.float32(255.0) - np.float32(0.5)) / np.float32(0.5)
    out = np.zeros((1, 3, REC_H, img_w), np.float32)
    out[0, :, :, :rw] = v[None]
    return out


def load_dict(path: Path) -> list[str]:
    chars = path.read_text(encoding="utf-8").split("\n")
    if chars and chars[-1] == "":
        chars = chars[:-1]
    return ["<blank>"] + chars + [" "]


def ctc_greedy(idx: np.ndarray, prob: np.ndarray, chars: list[str]) -> tuple[str, float]:
    out, confs, prev = [], [], -1
    for t in range(len(idx)):
        k = int(idx[t])
        if k != prev and k != 0:
            out.append(chars[k]); confs.append(float(prob[t]))
        prev = k
    return "".join(out), (sum(confs) / len(confs) if confs else 0.0)


# --------------------------------------------------------------------------- bidi (Bidi.kt)

def bidi_class(ch: str) -> str:
    c = unicodedata.bidirectional(ch)
    if c in ("R", "AL", "L", "EN", "AN", "ES", "ET", "CS", "NSM"):
        return c
    if c in ("WS", "S", "B"):
        return "WS"
    return "ON"


def visual_to_logical(s: str, classes=bidi_class) -> str:
    """Unicode bidi core (rules W1-W7, N1-N2, I1-I2, L2; no explicit embeddings, no mirroring) applied
    to the recogniser's left-to-right output. Reordering the visual string as if it were logical gives
    back the logical order for the runs that matter here, like python-bidi's get_display does in RapidOCR."""
    n = len(s)
    if n == 0:
        return s
    t = [classes(c) for c in s]
    base = 0
    for c in t:
        if c == "L":
            base = 0; break
        if c in ("R", "AL"):
            base = 1; break
    sos = "R" if base else "L"
    # W1
    prev = sos
    for i in range(n):
        if t[i] == "NSM":
            t[i] = prev
        prev = t[i]
    # W2, W3
    last = sos
    for i in range(n):
        if t[i] in ("L", "R", "AL"):
            last = t[i]
        elif t[i] == "EN" and last == "AL":
            t[i] = "AN"
    for i in range(n):
        if t[i] == "AL":
            t[i] = "R"
    # W4
    for i in range(1, n - 1):
        if t[i] == "ES" and t[i - 1] == "EN" and t[i + 1] == "EN":
            t[i] = "EN"
        elif t[i] == "CS" and t[i - 1] == t[i + 1] and t[i - 1] in ("EN", "AN"):
            t[i] = t[i - 1]
    # W5
    i = 0
    while i < n:
        if t[i] == "ET":
            j = i
            while j < n and t[j] == "ET":
                j += 1
            if (i > 0 and t[i - 1] == "EN") or (j < n and t[j] == "EN"):
                for k in range(i, j):
                    t[k] = "EN"
            i = j
        else:
            i += 1
    # W6
    for i in range(n):
        if t[i] in ("ES", "ET", "CS"):
            t[i] = "ON"
    # W7
    last = sos
    for i in range(n):
        if t[i] in ("L", "R"):
            last = t[i]
        elif t[i] == "EN" and last == "L":
            t[i] = "L"
    # N1, N2
    def strong(c):
        return "R" if c in ("R", "EN", "AN") else c
    i = 0
    while i < n:
        if t[i] in ("ON", "WS"):
            j = i
            while j < n and t[j] in ("ON", "WS"):
                j += 1
            before = strong(t[i - 1]) if i > 0 else sos
            after = strong(t[j]) if j < n else sos
            d = before if before == after else ("R" if base else "L")
            for k in range(i, j):
                t[k] = d
            i = j
        else:
            i += 1
    # I1, I2
    lv = []
    for c in t:
        if base == 0:
            lv.append(1 if c == "R" else 2 if c in ("AN", "EN") else 0)
        else:
            lv.append(2 if c in ("L", "EN", "AN") else 1)
    # L2
    chars = list(s)
    hi = max(lv)
    lo_odd = min(l for l in lv if l % 2 == 1) if any(l % 2 == 1 for l in lv) else hi + 1
    for level in range(hi, lo_odd - 1, -1):
        i = 0
        while i < n:
            if lv[i] >= level:
                j = i
                while j < n and lv[j] >= level:
                    j += 1
                chars[i:j] = chars[i:j][::-1]
                lv[i:j] = lv[i:j][::-1]
                i = j
            else:
                i += 1
    return "".join(chars)


# --------------------------------------------------------------------------- fusion, post, grouping

def count_numbers(s: str) -> int:
    return len(NUM.findall(s))


def latin_letters(s: str) -> int:
    return sum(1 for ch in s if "a" <= ch.lower() <= "z")


LRM, RLM = "‎", "‏"
TESS_REORDER = True      # Tesseract's mixed-direction lines back into logical order (TesseractLineOrder.kt)
ROUTE_MIXED = True       # line-script routing (OcrFusion.kt)
LATIN_LINE_SHARE = 0.6   # a line whose Tesseract letters are at least this Latin is a Latin line
MIXED_MIN_GAIN = 3       # an Arabic line where Tesseract read this many more Latin letters ...
MIXED_AGREE = 0.8        # ... and agrees with PP-OCR on the Arabic letters this much


def is_arabic_letter(ch: str) -> bool:
    return "ؠ" <= ch <= "ي" or "ٱ" <= ch <= "ۓ"


def tess_logical(s: str) -> str:
    """Tesseract writes a mixed Arabic/Latin line with bidi marks and its runs in display order. If the
    first mark is RLM, the line was laid out left to right and each Arabic run is wrapped RLM..LRM: the
    logical order is the runs reversed. If it is LRM, the line is right to left and each Latin run is
    wrapped LRM..RLM, with neighbouring numbers in display order: such a run of non-Arabic words is
    reversed word by word."""
    if not TESS_REORDER or (LRM not in s and RLM not in s):
        return s
    first_rlm = s.find(RLM) != -1 and (s.find(LRM) == -1 or s.find(RLM) < s.find(LRM))
    if first_rlm:
        runs, buf, inside = [], [], False
        for ch in s:
            if ch == RLM:
                runs.append("".join(buf)); buf = []; inside = True
            elif ch == LRM and inside:
                runs.append("".join(buf)); buf = []; inside = False
            elif ch in (LRM, RLM):
                continue
            else:
                buf.append(ch)
        runs.append("".join(buf))
        runs = [r.strip() for r in runs if r.strip()]
        return " ".join(reversed(runs))
    tokens = s.split()
    marked = [LRM in t or RLM in t for t in tokens]
    clean = [t.replace(LRM, "").replace(RLM, "") for t in tokens]
    out, i = [], 0
    while i < len(clean):
        if clean[i] and not any(is_arabic_letter(c) for c in clean[i]):
            j = i
            while j < len(clean) and clean[j] and not any(is_arabic_letter(c) for c in clean[j]):
                j += 1
            run = clean[i:j]
            out.extend(reversed(run) if j - i > 1 and any(marked[i:j]) else run)
            i = j
        else:
            out.append(clean[i]); i += 1
    return " ".join(t for t in out if t)


def levenshtein(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def arabic_agreement(a: str, b: str) -> float:
    x = "".join(c for c in a if is_arabic_letter(c))
    y = "".join(c for c in b if is_arabic_letter(c))
    n = max(len(x), len(y))
    return 1.0 if n == 0 else 1.0 - levenshtein(x, y) / n


def fuse(pp: str, tess: str, latin_rule: bool) -> str:
    more_num = count_numbers(tess) > count_numbers(pp)
    more_lat = latin_rule and latin_letters(tess) >= latin_letters(pp) + 3
    if more_num or more_lat:
        return tess
    if ROUTE_MIXED:
        lat, ar = latin_letters(tess), sum(1 for c in tess if is_arabic_letter(c))
        if lat > 0 and lat / (lat + ar) >= LATIN_LINE_SHARE:
            return tess  # a Latin line: Tesseract's eng model
        if lat >= latin_letters(pp) + MIXED_MIN_GAIN and arabic_agreement(pp, tess) >= MIXED_AGREE:
            return tess  # an Arabic line with Latin words the Arabic model dropped
    return pp


def post_process(s: str) -> str:
    return AR_COMMA_BETWEEN_DIGITS.sub("٫", s)


def arabic_letters(s: str) -> int:
    return sum(1 for ch in s if "ؠ" <= ch <= "ي" or "ٱ" <= ch <= "ۓ")


def group_lines(items, rtl: bool):
    """items: (x0, y0, x1, y1, text). Rows top to bottom; cells right to left (rtl) or left to right."""
    rows = sorted(items, key=lambda r: (r[1] + r[3]) / 2)
    lines = []
    for r in rows:
        for L in lines:
            ov = min(L["y1"], r[3]) - max(L["y0"], r[1])
            if ov > 0.5 * min(L["y1"] - L["y0"], r[3] - r[1]):
                L["cells"].append(r); L["y0"] = min(L["y0"], r[1]); L["y1"] = max(L["y1"], r[3]); break
        else:
            lines.append(dict(y0=r[1], y1=r[3], cells=[r]))
    lines.sort(key=lambda L: (L["y0"] + L["y1"]) / 2)
    out = []
    for L in lines:
        cells = sorted(L["cells"], key=(lambda c: -c[2]) if rtl else (lambda c: c[0]))
        txt = " ".join(c[4].strip() for c in cells if c[4].strip())
        if txt:
            out.append((L, cells, txt))
    return out


# --------------------------------------------------------------------------- runtimes

class OrtModel:
    def __init__(self, path):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        so.inter_op_num_threads = 1
        self.s = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        self.name = self.s.get_inputs()[0].name

    def __call__(self, x):
        return self.s.run(None, {self.name: x})[0]


class MnnModel:
    """MNN Interpreter/Session API, the same calls the app's JNI glue (kiwi_mnn.cpp) makes."""

    def __init__(self, path, precision="high"):
        import MNN
        self.MNN = MNN
        self.it = MNN.Interpreter(str(path))
        cfg = {"numThread": 1, "precision": precision}
        self.sess = self.it.createSession(cfg)
        self.inp = self.it.getSessionInput(self.sess)

    def __call__(self, x):
        MNN = self.MNN
        self.it.resizeTensor(self.inp, x.shape)
        self.it.resizeSession(self.sess)
        tmp = MNN.Tensor(x.shape, MNN.Halide_Type_Float, np.ascontiguousarray(x), MNN.Tensor_DimensionType_Caffe)
        self.inp.copyFromHostTensor(tmp)
        self.it.runSession(self.sess)
        out = self.it.getSessionOutput(self.sess)
        shape = out.getShape()
        host = MNN.Tensor(shape, MNN.Halide_Type_Float, MNN.Tensor_DimensionType_Caffe)
        out.copyToHostTensor(host)
        return np.array(host.getNumpyData(), copy=True).reshape(shape)


# --------------------------------------------------------------------------- the pipeline

class KiwiFused:
    def __init__(self, det_model, rec_model, dict_path, tess_api=None, latin_rule=False, use_tess=True):
        self.det = det_model
        self.rec = rec_model
        self.chars = load_dict(Path(dict_path))
        self.tess = tess_api
        self.latin_rule = latin_rule
        self.use_tess = use_tess and tess_api is not None
        self.trace = None  # set to a dict to record raw outputs (golden test)

    def detect(self, gray):
        h, w = gray.shape
        x = det_input(gray)
        prob = self.det(x)[0, 0].astype(np.float32)
        if self.trace is not None:
            q = np.clip(np.round(prob * 255), 0, 255).astype(np.uint8)
            self.trace["det_input_sum"] = float(x.astype(np.float64).sum())
            self.trace["det_shape"] = list(prob.shape)
            self.trace["det_map_u8"] = q
            prob = q.astype(np.float32) / np.float32(255.0)  # the golden test feeds the quantised map
        return db_boxes(prob, w, h)

    def read_line(self, gray, box):
        x = rec_input(gray, box)
        y = self.rec(x)[0]
        idx = y.argmax(axis=1)
        p = y.max(axis=1).astype(np.float32)
        if self.trace is not None:
            self.trace.setdefault("rec", []).append(dict(input_sum=float(x.astype(np.float64).sum()),
                                                         width=int(x.shape[3]),
                                                         idx=idx.astype(int).tolist(),
                                                         prob=[float(v) for v in p]))
        text, score = ctc_greedy(idx, p, self.chars)
        return visual_to_logical(text), score

    def tess_read(self, norm, box):
        h, w = norm.shape
        x0, y0, x1, y1 = box.bounds(w, h)
        pad = int(TESS_PAD * (y1 - y0))
        ix0, iy0 = max(0, int(x0) - pad), max(0, int(y0) - pad)
        ix1, iy1 = min(w, int(x1) + pad), min(h, int(y1) + pad)
        if ix1 <= ix0 or iy1 <= iy0:
            return ""
        self.tess.SetRectangle(ix0, iy0, ix1 - ix0, iy1 - iy0)
        return self.tess.GetUTF8Text().strip()

    def __call__(self, bgr=None, gray=None):
        if gray is None:
            gray = luma_from_bgr(bgr)
        h, w = gray.shape
        boxes = self.detect(gray)
        if self.use_tess:
            from PIL import Image
            norm = normalized_gray(gray)
            self.tess.SetImage(Image.fromarray(norm))
        items = []
        tess_texts = []
        for b in boxes:
            t, score = self.read_line(gray, b)
            if not t.strip() or score < TEXT_SCORE:
                tess_texts.append(None)
                continue
            pick = t
            if self.use_tess:
                raw = self.tess_read(norm, b)
                tess_texts.append(raw)  # raw Tesseract text (the golden test feeds it to Kotlin)
                pick = fuse(t, tess_logical(raw), self.latin_rule)
            else:
                tess_texts.append(None)
            pick = post_process(pick)
            x0, y0, x1, y1 = b.bounds(w, h)
            items.append((x0, y0, x1, y1, pick))
        all_text = "".join(i[4] for i in items)
        rtl = not (latin_letters(all_text) > arabic_letters(all_text))
        grouped = group_lines(items, rtl)
        if self.trace is not None:
            self.trace["boxes"] = [[b.cx, b.cy, b.w, b.h, b.angle, b.score] for b in boxes]
            self.trace["tess"] = tess_texts
            self.trace["lines"] = [g[2] for g in grouped]
        return [g[2] for g in grouped]

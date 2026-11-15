# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Score <results>/<engine>.json against <data>/*.gt.txt.

    python -I score.py --data work/sets/dev --results work/results/dev tess_fast kiwi_fused_mnn
    python -I score.py ... --json report.json --markdown table.md
    python -I score.py ... --gate base=tess_fast,new=kiwi_fused_mnn --max-cer 0.20 --min-rel-gain 0.30 \
        --max-table-cer 0.10 --min-numbers 0.65 --max-regress-pp 2 --allow-regress mixed:5
    python -I score.py ... --parity kiwi_fused_onnx,kiwi_fused_mnn16 --tol-pp 0.5   # 2.0 parity reference

Normalisation (both sides): NFKC, bidi controls and tatweel removed, Arabic-Indic and Eastern Arabic-Indic
digits mapped to ASCII, Arabic thousands/decimal separators mapped to , and ., whitespace collapsed. "CER"
also removes harakat (the base-letter error rate); "CER+harakat" keeps them (diacritics pages).
Page text = the engine's lines in the engine's order, so reading-order mistakes raise CER/WER.
Number accuracy = share of numbers in the ground truth found exactly (digit order included).
Reading order = pairwise order agreement of matched lines (a GT line matches the engine line with the lowest
CER, if that CER < 0.5); "lines found" = share of GT lines matched.
"""
import argparse
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from rapidfuzz.distance import Levenshtein

HARAKAT = re.compile("[ً-ْٰٖ-ٟ]")
CTRL = re.compile("[​-‏‪-‮⁦-⁩ـ﻿]")
DIG = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹٬٫", "01234567890123456789,.")
NUM = re.compile(r"\d+(?:[.,/:\-]\d+)*")
CATS = ["ALL", "connected", "diacritics", "numbers", "amounts", "dates", "table", "mixed", "small_lowq"]


def norm(s, harakat=False):
    s = unicodedata.normalize("NFKC", s)
    s = CTRL.sub("", s).translate(DIG)
    if not harakat:
        s = HARAKAT.sub("", s)
    return re.sub(r"\s+", " ", s).strip()


def cer(ref, hyp):
    return Levenshtein.distance(ref, hyp) / max(1, len(ref))


def order_score(gt_lines, hyp_lines):
    idx = []
    for g in gt_lines:
        best, bi = 1.0, None
        for j, h in enumerate(hyp_lines):
            c = cer(g, h)
            if c < best:
                best, bi = c, j
        idx.append(bi if best < 0.5 else None)
    m = [i for i in idx if i is not None]
    pairs = sum(1 for a in range(len(m)) for b in range(a + 1, len(m)))
    good = sum(1 for a in range(len(m)) for b in range(a + 1, len(m)) if m[a] < m[b])
    return (good / pairs if pairs else 1.0), len(m) / len(gt_lines)


def score(data: Path, res: dict):
    man = {m["id"]: m for m in json.loads((data / "manifest.json").read_text(encoding="utf-8"))["pages"]}
    acc = defaultdict(lambda: defaultdict(float))
    for s in res["samples"]:
        gt_raw = (data / f"{s['id']}.gt.txt").read_text(encoding="utf-8").splitlines()
        gl = [norm(l) for l in gt_raw if l.strip()]
        hl = [norm(l) for l in s["lines"] if norm(l)]
        g, h = " ".join(gl), " ".join(hl)
        gh, hh = norm(" ".join(gt_raw), True), norm(" ".join(s["lines"]), True)
        gn, hn = Counter(NUM.findall(g)), Counter(NUM.findall(h))
        o, found = order_score(gl, hl)
        for key in ("ALL", man[s["id"]]["category"]):
            a = acc[key]
            a["n"] += 1
            a["ce"] += Levenshtein.distance(g, h); a["cc"] += len(g)
            a["che"] += Levenshtein.distance(gh, hh); a["chc"] += len(gh)
            a["we"] += Levenshtein.distance(g.split(), h.split()); a["wc"] += len(g.split())
            a["num_ok"] += sum((gn & hn).values()); a["num_n"] += sum(gn.values())
            a["order"] += o; a["found"] += found; a["sec"] += s["seconds"]
    out = {}
    for k, a in acc.items():
        out[k] = dict(n=int(a["n"]), cer=a["ce"] / a["cc"], cer_harakat=a["che"] / a["chc"], wer=a["we"] / a["wc"],
                      num_acc=(a["num_ok"] / a["num_n"]) if a["num_n"] else None,
                      order=a["order"] / a["n"], lines_found=a["found"] / a["n"], sec_per_page=a["sec"] / a["n"])
    return out


def pct(v):
    return "-" if v is None else f"{100 * v:.1f}%"


def markdown(report, set_name):
    lines = [f"Set `{set_name}`. Desktop proxy (one CPU thread on the build PC), not phone numbers.", "",
             "| Engine | CER | WER | Numbers | Order | Lines found | s/page | Peak RSS |",
             "|---|---|---|---|---|---|---|---|"]
    for e, r in report.items():
        a = r["summary"]["ALL"]
        lines.append(f"| `{e}` | {pct(a['cer'])} | {pct(a['wer'])} | {pct(a['num_acc'])} | {a['order']:.2f} | "
                     f"{pct(a['lines_found'])} | {a['sec_per_page']:.2f} | {r['peak_mb']:.0f} MB |")
    lines += ["", "CER by category (lower is better):", "",
              "| Engine | " + " | ".join(CATS[1:]) + " |", "|---" * len(CATS) + "|"]
    for e, r in report.items():
        lines.append(f"| `{e}` | " + " | ".join(pct(r["summary"].get(c, {}).get("cer")) for c in CATS[1:]) + " |")
    lines += ["", "Number accuracy by category (higher is better):", "",
              "| Engine | " + " | ".join(CATS[1:]) + " |", "|---" * len(CATS) + "|"]
    for e, r in report.items():
        lines.append(f"| `{e}` | " + " | ".join(pct(r["summary"].get(c, {}).get("num_acc")) for c in CATS[1:]) + " |")
    lines += ["", "Diacritics pages with harakat kept (reported, not gated): " +
              ", ".join(f"`{e}` {pct(r['summary'].get('diacritics', {}).get('cer_harakat'))}" for e, r in report.items())]
    return "\n".join(lines) + "\n"


def gate(report, base, new, a):
    """The roadmap 1.5.0 accuracy gates. Returns a list of (name, value, limit, ok)."""
    b, n = report[base]["summary"], report[new]["summary"]
    res = []
    if a.max_cer is not None:
        res.append(("overall CER", n["ALL"]["cer"], f"<= {a.max_cer:.2f}", n["ALL"]["cer"] <= a.max_cer))
    if a.min_rel_gain is not None:
        gain = 1 - n["ALL"]["cer"] / b["ALL"]["cer"]
        res.append((f"relative CER gain vs {base}", gain, f">= {a.min_rel_gain:.2f}", gain >= a.min_rel_gain))
    if a.max_table_cer is not None and "table" in n:
        res.append(("table CER", n["table"]["cer"], f"<= {a.max_table_cer:.2f}", n["table"]["cer"] <= a.max_table_cer))
    if a.min_numbers is not None:
        res.append(("number accuracy", n["ALL"]["num_acc"], f">= {a.min_numbers:.2f}", n["ALL"]["num_acc"] >= a.min_numbers))
    if a.max_regress_pp is not None:
        allow = {}
        for spec in a.allow_regress or []:
            k, v = spec.split(":")
            allow[k] = float(v)
        for c in CATS[1:]:
            if c not in n or c not in b or c == "diacritics":
                continue
            lim = allow.get(c, a.max_regress_pp)
            d = 100 * (n[c]["cer"] - b[c]["cer"])
            res.append((f"{c} CER vs {base} (pp)", d, f"<= +{lim:g}", d <= lim))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("engines", nargs="+")
    ap.add_argument("--data", required=True, type=Path)
    ap.add_argument("--results", required=True, type=Path)
    ap.add_argument("--json", type=Path)
    ap.add_argument("--markdown", type=Path)
    ap.add_argument("--gate", help="base=ENGINE,new=ENGINE")
    ap.add_argument("--max-cer", type=float)
    ap.add_argument("--min-rel-gain", type=float)
    ap.add_argument("--max-table-cer", type=float)
    ap.add_argument("--min-numbers", type=float)
    ap.add_argument("--max-regress-pp", type=float)
    ap.add_argument("--allow-regress", action="append")
    ap.add_argument("--parity", help="REF_ENGINE,NEW_ENGINE: overall CER within --tol-pp")
    ap.add_argument("--tol-pp", type=float, default=0.5)
    a = ap.parse_args()
    set_name = json.loads((a.data / "manifest.json").read_text(encoding="utf-8"))["set"]
    report = {}
    for e in a.engines:
        p = a.results / f"{e}.json"
        if not p.exists():
            print(f"missing {p}", file=sys.stderr)
            continue
        res = json.loads(p.read_text(encoding="utf-8"))
        report[e] = dict(summary=score(a.data, res), load_s=res["load_s"], peak_mb=res["peak_mb"])
    md = markdown(report, set_name)
    sys.stdout.buffer.write(md.encode("utf-8"))
    if a.json:
        a.json.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    if a.markdown:
        a.markdown.write_text(md, encoding="utf-8")
    failed = False
    if a.gate:
        kv = dict(x.split("=") for x in a.gate.split(","))
        print(f"\nGates ({kv['new']} vs {kv['base']}):")
        for name, v, lim, ok in gate(report, kv["base"], kv["new"], a):
            print(f"  {'PASS' if ok else 'FAIL'}  {name}: {v:.4f} (limit {lim})")
            failed |= not ok
    if a.parity:
        ref, new = a.parity.split(",")
        d = 100 * (report[new]["summary"]["ALL"]["cer"] - report[ref]["summary"]["ALL"]["cer"])
        ok = abs(d) <= a.tol_pp
        print(f"\nParity {new} vs {ref}: {d:+.2f} pp CER ({'PASS' if ok else 'FAIL'}, tolerance {a.tol_pp} pp)")
        failed |= not ok
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

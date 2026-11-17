# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""Rebuilds the app's MNN models from the pinned ONNX exports and checks them against the shipped files.

    python -I tools/mnn/convert.py --work <scratch dir> [--install]

1. Downloads each ONNX file from its pinned URL and checks its sha256 (models.json).
2. Converts it with MNNConvert from the hash-pinned `mnn==3.6.2` wheel (tools/ocr-bench/requirements-lock.txt).
3. MNNConvert writes a random 36-character UUID into every file, so two conversions differ in those bytes
   only. The check compares sha256 with that UUID zeroed ("masked sha256", pinned in models.json) and,
   for the shipped file, also its plain sha256.
4. --install copies the converted files over app/src/main/assets/ocr/ (then the plain sha256 changes:
   update THIRD_PARTY.md, models.json and the SBOM).
The app never downloads anything; this runs only on a developer PC.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ASSETS = REPO / "app" / "src" / "main" / "assets" / "ocr"
UUID = re.compile(rb"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def masked_sha256(b: bytes) -> str:
    m = UUID.search(b)
    if m is None:
        raise SystemExit("no MNNConvert UUID found: not an MNNConvert 3.6 file?")
    bb = bytearray(b)
    bb[m.start():m.end()] = b"0" * 36
    return sha256(bytes(bb))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, type=Path)
    ap.add_argument("--install", action="store_true")
    a = ap.parse_args()
    spec = json.loads((HERE / "models.json").read_text(encoding="utf-8"))
    a.work.mkdir(parents=True, exist_ok=True)
    conv = shutil.which("mnnconvert") or str(Path(sys.executable).parent / "mnnconvert")
    ok = True
    for m in spec["models"]:
        src = a.work / m["onnx"]["file"]
        if not src.exists() or sha256(src.read_bytes()) != m["onnx"]["sha256"]:
            print("download", m["onnx"]["url"])
            with urllib.request.urlopen(m["onnx"]["url"]) as r:
                src.write_bytes(r.read())
        if sha256(src.read_bytes()) != m["onnx"]["sha256"]:
            raise SystemExit(f"sha256 mismatch for {src.name}")
        out = a.work / m["mnn"]["file"]
        cmd = [conv, "-f", "ONNX", "--modelFile", str(src), "--MNNModel", str(out), "--bizCode", "MNN"] + m["mnn"]["convert_args"]
        # MNNConvert 3.6.2 on Windows may crash while exiting, after "Converted Success!"; the file is complete.
        p = subprocess.run(cmd, capture_output=True, text=True)
        if "Converted Success" not in p.stdout:
            raise SystemExit(f"conversion failed: {p.stdout[-500:]} {p.stderr[-500:]}")
        got = masked_sha256(out.read_bytes())
        same = got == m["mnn"]["masked_sha256"]
        ok &= same
        print(f"{out.name}: masked sha256 {got} {'== pinned' if same else '!= pinned ' + m['mnn']['masked_sha256']}")
        shipped = ASSETS / m["mnn"]["file"]
        if shipped.exists():
            sb = shipped.read_bytes()
            print(f"  shipped {shipped.name}: sha256 {sha256(sb)} ({'pinned' if sha256(sb) == m['mnn']['sha256'] else 'NOT the pinned file'}), "
                  f"masked {'matches the rebuild' if masked_sha256(sb) == got else 'DIFFERS from the rebuild'}")
            ok &= sha256(sb) == m["mnn"]["sha256"] and masked_sha256(sb) == got
        if a.install:
            ASSETS.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(out, shipped)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

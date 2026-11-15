# SPDX-FileCopyrightText: 2026 FastKiwi
# SPDX-License-Identifier: Apache-2.0
"""One command for the OCR benchmark: venv from the hash-pinned lock, models fetched and checked, set
generated, engines run, scores printed (and the 1.5.0 gates checked on the held-out set).

    python -I tools/ocr-bench/run.py --set dev --engines tess_fast,kiwi_fused_mnn16
    python -I tools/ocr-bench/run.py --set heldout --engines tess_fast,kiwi_fused_mnn16 --gates

Everything goes under tools/ocr-bench/work/ (git-ignored). Synthetic pages only.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import venv
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
WORK = HERE / "work"


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def python_in(v: Path) -> Path:
    return v / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def ensure_venv() -> Path:
    if os.environ.get("KIWI_BENCH_NO_VENV"):  # CI: the lock is already installed in this interpreter
        return Path(sys.executable)
    v = WORK / "venv"
    py = python_in(v)
    if not py.exists():
        venv.create(v, with_pip=True)
        subprocess.run([str(py), "-m", "pip", "install", "--require-hashes", "-r", str(HERE / "requirements-lock.txt")], check=True)
    return py


def ensure_models() -> Path:
    spec = json.loads((HERE / "models.json").read_text(encoding="utf-8"))
    m = WORK / "models"
    for d in spec["download"]:
        f = m / d["file"]
        if not f.exists() or sha256(f) != d["sha256"]:
            f.parent.mkdir(parents=True, exist_ok=True)
            print("download", d["url"], flush=True)
            with urllib.request.urlopen(d["url"]) as r:
                f.write_bytes(r.read())
        if sha256(f) != d["sha256"]:
            sys.exit(f"sha256 mismatch: {f}")
    for d in spec["from_app"]:
        f = m / d["file"]
        f.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO / "app/src/main/assets" / d["asset"], f)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", default="dev")
    ap.add_argument("--engines", default="tess_fast,kiwi_fused_mnn16")
    ap.add_argument("--renderer", default="chromium", choices=["chromium", "edge"])
    ap.add_argument("--gates", action="store_true", help="check the roadmap 1.5.0 accuracy gates (held-out set)")
    a = ap.parse_args()
    WORK.mkdir(exist_ok=True)
    py = ensure_venv()
    models = ensure_models()
    env = dict(os.environ, KIWI_BENCH_MODELS=str(models), PYTHONHASHSEED="0", OMP_THREAD_LIMIT="1",
               PLAYWRIGHT_BROWSERS_PATH=str(WORK / "ms-playwright"), PYTHONIOENCODING="utf-8")
    data = WORK / "sets" / a.set
    if not (data / "manifest.json").exists():
        if a.renderer == "chromium":
            subprocess.run([str(py), "-m", "playwright", "install", "chromium"], check=True, env=env)
        subprocess.run([str(py), "-I", str(HERE / "gen_dataset.py"), "--set", a.set, "--out", str(data), "--renderer", a.renderer], check=True, env=env)
    out = WORK / "results" / a.set
    engines = a.engines.split(",")
    for e in engines:
        subprocess.run([str(py), "-I", str(HERE / "bench.py"), e, "--data", str(data), "--out", str(out)], check=True, env=env)
    cmd = [str(py), "-I", str(HERE / "score.py"), *engines, "--data", str(data), "--results", str(out),
           "--json", str(out / "report.json"), "--markdown", str(out / "report.md")]
    if a.gates:
        cmd += ["--gate", f"base={engines[0]},new={engines[-1]}", "--max-cer", "0.20", "--min-rel-gain", "0.30",
                "--max-table-cer", "0.10", "--min-numbers", "0.65", "--max-regress-pp", "2", "--allow-regress", "mixed:5"]
    sys.exit(subprocess.run(cmd, env=env).returncode)


if __name__ == "__main__":
    main()

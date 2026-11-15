# MNN runtime and OCR models («دقيق» Accurate mode)

The Accurate OCR mode runs two PaddleOCR models on [MNN](https://github.com/alibaba/MNN) (Apache-2.0),
CPU backend only. Everything here is pinned by version and sha256. Nothing is downloaded by the app.

## 1. MNN library (`app/src/main/jniLibs/<abi>/libMNN.so`)

Source: the **official MNN 3.6.2 GitHub release**, asset
`mnn_3.6.2_android_armv7_armv8_cpu_opencl_vulkan.zip`
(<https://github.com/alibaba/MNN/releases/download/3.6.2/mnn_3.6.2_android_armv7_armv8_cpu_opencl_vulkan.zip>,
sha256 `0b4cc255964de1e3ac8dce75dce316699a0a28b914ecacb7bea8465666858ece`, GitHub's own digest matches).
Only `libMNN.so` is taken (the CPU runtime; OpenCL, Vulkan, Express, LLM and audio libraries are not shipped).

| ABI | File | sha256 | Size |
|---|---|---|---|
| arm64-v8a | `libMNN.so` | `f915474f37c6e04046eae2206ac9de0c1c336f648564f983a640e21021e1bb00` | 2,497,216 B |
| armeabi-v7a | `libMNN.so` | `d07d529b5fbbdf4bdae0fba6bd4f7e41f45b78ea3b2234205a68380c3f7f7444` | 1,419,300 B |

Why the release build and not a source build: its 64-bit ELF `LOAD` segments are already **16 KB aligned**
(`llvm-readelf -lW`: `Align 0x4000`), it was built with NDK r27's clang 18.0.3 (`.comment`: `Android
(12470979 … r522817c) clang version 18.0.3`), the same NDK the app pins, and it needs only `libc++_shared.so`,
`liblog`, `libm`, `libdl`, `libc` and `libandroid`. Every libc++ symbol it imports exists in NDK
27.3.13750724's `libc++_shared.so` (checked with `llvm-nm`), which the app packages (CMake
`-DANDROID_STL=c++_shared`). A source build would add a long native build to every CI run for no gain.

Headers (`app/src/main/cpp/mnn-include/MNN/*.h*`) are the public headers of the tag `3.6.2`
(<https://github.com/alibaba/MNN/tree/3.6.2/include/MNN>, tag object `498882a2781b4a0385e3dcd0042c751cf8708143`).

To re-check: download the zip, compare its sha256, unzip, compare the two `libMNN.so` hashes above, then
`llvm-readelf -lW arm64-v8a/libMNN.so | grep LOAD` (all `0x4000`).

## 2. JNI glue (`app/src/main/cpp/kiwi_mnn.cpp`)

Built from source by Gradle (`externalNativeBuild`, CMake 3.31.5, NDK **27.3.13750724**, the default NDK of
GitHub's `ubuntu-24.04` runner image), linked with `-Wl,-z,max-page-size=16384`. It only loads a model from
bytes, sets the input tensor, runs, and copies the output out (MNN `Precision_High`, i.e. fp32 arithmetic).
Pre- and post-processing are Kotlin (`com.fastkiwi.scan.ocr.fused`).

## 3. Models (`app/src/main/assets/ocr/`)

| File | sha256 | From | Licence |
|---|---|---|---|
| `PP-OCRv6_det_tiny.fp16.mnn` | `a1d21086e7508b10fe977de651cdcba902d60d5fc6d1f87f17cea20f0fe129e6` | `PP-OCRv6_det_tiny.onnx` `f42c0fbd…b3d5` | Apache-2.0 |
| `arabic_PP-OCRv5_rec_mobile.fp16.mnn` | `9c0098c4d0b37437f55e1e1225c6be2fae6506239bfef868fb3c8f90a5cc484e` | `arabic_PP-OCRv5_rec_mobile.onnx` `c1192e63…9295` | Apache-2.0 |
| `ppocrv5_arabic_dict.txt` | `7f92f7dbb9b75a4787a83bfb4f6d14a8ab515525130c9d40a9036f61cf6999e9` | the official dictionary (= the ONNX metadata) | Apache-2.0 |

The ONNX files are the PaddlePaddle models exported by RapidOCR 3.10.0 (ModelScope `RapidAI/RapidOCR`,
tag `v3.10.0`), the same files the roadmap §3 benchmark used. Licence evidence: the official PaddlePaddle
model cards on Hugging Face list `license: apache-2.0` — `PaddlePaddle/PP-OCRv6_tiny_det` at revision
`d3177d4e5551463292a61e27cfca2b53e7c3fe9d` and `PaddlePaddle/arabic_PP-OCRv5_mobile_rec` at revision
`33d91636a65dca87f5562cc48860332ae367ee1b` (checked 2026-11-15 through the Hugging Face model API); PaddleOCR,
RapidOCR and MNN are Apache-2.0 (GitHub licence API).

Conversion: `mnnconvert -f ONNX --modelFile X.onnx --MNNModel X.fp16.mnn --bizCode MNN --fp16`, MNNConvert from
the `mnn==3.6.2` wheel (hash-pinned in `tools/ocr-bench/requirements-lock.txt`). **fp16 weight storage**
halves the files (1.75 → 0.90 MB and 7.98 → 4.05 MB) and passed the parity test (dev set: fp16 15.5 % CER,
fp32 15.5 %, ONNX 15.6 %; see `docs/ocr-benchmark.md`), so it ships. MNN expands the weights to fp32 when
it loads them.

Reproducing: `python -I tools/mnn/convert.py --work <dir>` downloads the ONNX files, checks their sha256,
converts them and compares with the shipped files. MNNConvert writes a random 36-character UUID into each
file, so a rebuild differs from the shipped file in those 36 bytes only; the script compares sha256 with the
UUID zeroed (`masked_sha256` in `models.json`). Verified on 2026-11-15: both rebuilds match.

// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
//
// Minimal JNI glue between the app's Kotlin OCR code and MNN (Apache-2.0): load a model from bytes,
// set the input tensor, run, read the output. All pre- and post-processing lives in Kotlin
// (com.fastkiwi.scan.ocr.fused), where it is unit-tested on the JVM. Nothing here touches files,
// logs text or uses the network.
#include <jni.h>

#include <memory>
#include <mutex>
#include <vector>

#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

namespace {

struct Model {
    std::unique_ptr<MNN::Interpreter> net;
    MNN::Session* session = nullptr;
    MNN::Tensor* input = nullptr;
    std::vector<int> outShape;
    std::mutex lock;
};

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fastkiwi_scan_ocr_fused_MnnNative_nativeCreate(JNIEnv* env, jclass, jbyteArray model, jint threads) {
    const jsize n = env->GetArrayLength(model);
    jbyte* bytes = env->GetByteArrayElements(model, nullptr);
    if (bytes == nullptr) return 0;
    // createFromBuffer copies the model, so the Java array can be released right away.
    MNN::Interpreter* net = MNN::Interpreter::createFromBuffer(bytes, static_cast<size_t>(n));
    env->ReleaseByteArrayElements(model, bytes, JNI_ABORT);
    if (net == nullptr) return 0;
    auto* m = new Model();
    m->net.reset(net);
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = threads > 0 ? threads : 1;
    MNN::BackendConfig backend;
    backend.precision = MNN::BackendConfig::Precision_High;  // fp32 arithmetic: the parity test's setting
    backend.memory = MNN::BackendConfig::Memory_Normal;
    config.backendConfig = &backend;
    m->session = net->createSession(config);
    if (m->session == nullptr) { delete m; return 0; }
    m->input = net->getSessionInput(m->session, nullptr);
    if (m->input == nullptr) { delete m; return 0; }
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_fastkiwi_scan_ocr_fused_MnnNative_nativeRun(JNIEnv* env, jclass, jlong handle, jfloatArray input,
                                                      jintArray shape, jintArray outShape) {
    auto* m = reinterpret_cast<Model*>(handle);
    if (m == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(m->lock);
    const jsize rank = env->GetArrayLength(shape);
    std::vector<int> dims(static_cast<size_t>(rank));
    env->GetIntArrayRegion(shape, 0, rank, dims.data());
    size_t count = 1;
    for (int d : dims) count *= static_cast<size_t>(d);
    if (static_cast<size_t>(env->GetArrayLength(input)) < count) return nullptr;

    m->net->resizeTensor(m->input, dims);
    m->net->resizeSession(m->session);
    {
        // NCHW host tensor -> the backend's own layout.
        MNN::Tensor host(m->input, MNN::Tensor::CAFFE);
        env->GetFloatArrayRegion(input, 0, static_cast<jsize>(count), host.host<float>());
        if (!m->input->copyFromHostTensor(&host)) return nullptr;
    }
    if (m->net->runSession(m->session) != MNN::NO_ERROR) return nullptr;
    MNN::Tensor* out = m->net->getSessionOutput(m->session, nullptr);
    if (out == nullptr) return nullptr;
    MNN::Tensor result(out, MNN::Tensor::CAFFE);
    if (!out->copyToHostTensor(&result)) return nullptr;
    const std::vector<int> s = result.shape();
    const jsize outRank = env->GetArrayLength(outShape);
    for (jsize i = 0; i < outRank; ++i) {
        const jint v = i < static_cast<jsize>(s.size()) ? s[static_cast<size_t>(i)] : 1;
        env->SetIntArrayRegion(outShape, i, 1, &v);
    }
    const int size = result.elementSize();
    jfloatArray arr = env->NewFloatArray(size);
    if (arr == nullptr) return nullptr;  // OutOfMemoryError pending in Java
    env->SetFloatArrayRegion(arr, 0, size, result.host<float>());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_fastkiwi_scan_ocr_fused_MnnNative_nativeRelease(JNIEnv*, jclass, jlong handle) {
    auto* m = reinterpret_cast<Model*>(handle);
    if (m == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(m->lock);
        if (m->session != nullptr) m->net->releaseSession(m->session);
        m->session = nullptr;
    }
    delete m;
}

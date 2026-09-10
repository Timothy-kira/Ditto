#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <jni.h>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>
#include <vector>

namespace {

constexpr const char *kTag = "ditto_oboe";
constexpr int kTargetRate = 16000;

JavaVM *g_vm = nullptr;
std::mutex g_lock;
jobject g_callback = nullptr;
jmethodID g_on_pcm = nullptr;
std::shared_ptr<oboe::AudioStream> g_stream;
int32_t g_actual_rate = kTargetRate;

JNIEnv *env_for_callback() {
    if (g_vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        return env;
    }
    return nullptr;
}

void emit_pcm16(JNIEnv *env, const int16_t *samples, int32_t frames) {
    if (env == nullptr || g_callback == nullptr || g_on_pcm == nullptr || samples == nullptr || frames <= 0) {
        return;
    }
    const jsize bytes = frames * 2;
    jbyteArray array = env->NewByteArray(bytes);
    if (array == nullptr) return;
    env->SetByteArrayRegion(array, 0, bytes, reinterpret_cast<const jbyte *>(samples));
    env->CallVoidMethod(g_callback, g_on_pcm, array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(array);
}

void resample_to_target(
    const int16_t *in,
    int32_t in_frames,
    int32_t in_rate,
    std::vector<int16_t> *out
) {
    if (in == nullptr || out == nullptr || in_frames <= 0 || in_rate <= 0) {
        out->clear();
        return;
    }
    if (in_rate == kTargetRate) {
        out->assign(in, in + in_frames);
        return;
    }
    const double ratio = static_cast<double>(in_rate) / static_cast<double>(kTargetRate);
    const int32_t out_frames = std::max(1, static_cast<int32_t>(std::llround(in_frames / ratio)));
    out->resize(static_cast<size_t>(out_frames));
    for (int32_t i = 0; i < out_frames; ++i) {
        const double src = static_cast<double>(i) * ratio;
        auto i0 = static_cast<int32_t>(src);
        if (i0 >= in_frames) i0 = in_frames - 1;
        int32_t i1 = i0 + 1;
        if (i1 >= in_frames) i1 = in_frames - 1;
        const double frac = src - static_cast<double>(i0);
        const double mixed = in[i0] * (1.0 - frac) + in[i1] * frac;
        (*out)[static_cast<size_t>(i)] = static_cast<int16_t>(std::clamp(mixed, -32768.0, 32767.0));
    }
}

class MicCallback : public oboe::AudioStreamDataCallback {
public:
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *stream,
        void *audio_data,
        int32_t num_frames
    ) override {
        if (stream == nullptr || audio_data == nullptr || num_frames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }
        JNIEnv *env = env_for_callback();
        if (env == nullptr) return oboe::DataCallbackResult::Continue;

        const int32_t channels = std::max(1, stream->getChannelCount());
        const oboe::AudioFormat format = stream->getFormat();
        converted_.resize(static_cast<size_t>(num_frames));
        if (format == oboe::AudioFormat::I16) {
            const auto *in = static_cast<const int16_t *>(audio_data);
            if (channels == 1) {
                converted_.assign(in, in + num_frames);
            } else {
                for (int32_t i = 0; i < num_frames; ++i) {
                    int32_t sum = 0;
                    for (int32_t c = 0; c < channels; ++c) {
                        sum += in[i * channels + c];
                    }
                    converted_[static_cast<size_t>(i)] = static_cast<int16_t>(sum / channels);
                }
            }
        } else {
            const auto *in = static_cast<const float *>(audio_data);
            for (int32_t i = 0; i < num_frames; ++i) {
                float sample = in[i * channels];
                sample = std::clamp(sample, -1.0f, 1.0f);
                converted_[static_cast<size_t>(i)] = static_cast<int16_t>(sample * 32767.0f);
            }
        }
        const int32_t rate = stream->getSampleRate() > 0 ? stream->getSampleRate() : kTargetRate;
        resample_to_target(converted_.data(), static_cast<int32_t>(converted_.size()), rate, &resampled_);
        if (!resampled_.empty()) {
            emit_pcm16(env, resampled_.data(), static_cast<int32_t>(resampled_.size()));
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    std::vector<int16_t> converted_;
    std::vector<int16_t> resampled_;
};

std::unique_ptr<MicCallback> g_callback_impl;

void close_stream_locked() {
    if (g_stream) {
        g_stream->stop();
        g_stream->close();
        g_stream.reset();
    }
    g_actual_rate = kTargetRate;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_kira_ditto_audio_OboeMicCapture_nativeStart(JNIEnv *env, jobject thiz, jint sample_rate) {
    std::lock_guard<std::mutex> lock(g_lock);
    close_stream_locked();
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    g_callback = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_on_pcm = env->GetMethodID(cls, "onNativePcm", "([B)V");
    env->DeleteLocalRef(cls);
    if (g_on_pcm == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "onNativePcm not found");
        return JNI_FALSE;
    }
    g_callback_impl = std::make_unique<MicCallback>();
    const int requested = sample_rate > 0 ? sample_rate : kTargetRate;

    auto open_with = [&](oboe::SharingMode sharing) -> oboe::Result {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input);
        builder.setSharingMode(sharing);
        builder.setPerformanceMode(oboe::PerformanceMode::None);
        builder.setFormat(oboe::AudioFormat::I16);
        builder.setChannelCount(1);
        builder.setSampleRate(requested);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Best);
        builder.setInputPreset(oboe::InputPreset::VoiceRecognition);
        builder.setDataCallback(g_callback_impl.get());
        return builder.openStream(g_stream);
    };

    oboe::Result result = open_with(oboe::SharingMode::Shared);
    if (result != oboe::Result::OK) {
        close_stream_locked();
        result = open_with(oboe::SharingMode::Exclusive);
    }
    if (result != oboe::Result::OK || !g_stream) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "openStream failed: %s", oboe::convertToText(result));
        close_stream_locked();
        return JNI_FALSE;
    }
    result = g_stream->requestStart();
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "requestStart failed: %s", oboe::convertToText(result));
        close_stream_locked();
        return JNI_FALSE;
    }
    g_actual_rate = g_stream->getSampleRate();
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "mic opened requested=%d actual=%d ch=%d fmt=%d",
        requested,
        g_actual_rate,
        g_stream->getChannelCount(),
        static_cast<int>(g_stream->getFormat())
    );
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_kira_ditto_audio_OboeMicCapture_nativeSampleRate(JNIEnv *, jobject) {
    return g_actual_rate;
}

extern "C" JNIEXPORT void JNICALL
Java_kira_ditto_audio_OboeMicCapture_nativeStop(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    close_stream_locked();
    g_callback_impl.reset();
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    g_on_pcm = nullptr;
}

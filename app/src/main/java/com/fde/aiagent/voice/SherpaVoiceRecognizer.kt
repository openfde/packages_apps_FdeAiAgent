package com.fde.aiagent.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fde.aiagent.R
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

/**
 * 基于 sherpa-onnx 的**完全离线**中文语音识别（流式 zipformer-transducer，14M 模型）。
 *
 * - 模型文件内置在 APK assets 的 `sherpa/zh14m/` 下，不经网络直接加载；
 * - 使用系统麦克风 [AudioRecord] 采集 16kHz 单声道 PCM，实时喂给
 *   [OnlineRecognizer] 进行流式解码（从 [Listener.onPartial] 得到中间结果）；
 * - 按下一个麦克风（推-按式）：[start] 开始采集，[stop] 结束并返回整句最终结果。
 *
 * **重要**：这里统一使用 **float32（非 int8 量化）** 模型。
 *
 * int8 量化模型会触发 onnxruntime 的 int8 GEMM 内核，该内核依赖 ARM 的
 * dotprod(FEAT_DotProd)/i8mm(FEAT_I8MM) SIMD 指令（如 `sdot`/`smmla`）。
 * 在部分 arm64 设备/云手机（含 ARM 指令翻译环境，CPU 特性上报与实际执行能力
 * 不一致）上，执行这些指令会直接抛 SIGILL（Fatal signal 4, ILL_ILLOPC）导致整个
 * 进程闪退，并且 Java/Kotlin 层无法捕获。float32 模型只使用 ARMv8 基线 NEON 指令，
 * 兼容性最好，可将崩溃问题彻底规避（代价是模型文件略大、速度略慢）。
 *
 * 语言：纯中文（模型为中文 zipformer，不依赖系统语音识别服务，任何设备可用）。
 */
class SherpaVoiceRecognizer(context: Context) {

    /** 识别过程回调（均在主线程）。 */
    interface Listener {
        /** 模型开始加载（后台进行）。 */
        fun onModelLoading()

        /** 模型加载完成，可以开始识别。 */
        fun onModelReady()

        /** 模型加载失败。 */
        fun onModelError(message: String)

        /** 流式中间识别结果（说话过程中实时更新，可为空）。 */
        fun onPartial(text: String)

        /** 一次完整识别结束的最终结果。 */
        fun onResult(text: String)

        /** 采集/识别过程出错。 */
        fun onError(message: String)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MODEL_DIR = "sherpa/zh14m"
        private const val TOKENS = "$MODEL_DIR/tokens.txt"
        // float32（非 int8）模型：避免触发 onnxruntime int8 GEMM 内核所需的
        // dotprod/i8mm 指令——这些指令在部分 arm64 设备/云手机上会 SIGILL 闪退。
        private const val ENCODER = "$MODEL_DIR/encoder-epoch-99-avg-1.onnx"
        private const val DECODER = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "$MODEL_DIR/joiner-epoch-99-avg-1.onnx"
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null
    private var listener: Listener? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var recording = false

    @Volatile
    private var loading = false

    private fun str(res: Int, vararg args: Any): String = appContext.getString(res, *args)

    /** model/tokens 等资产是否打包在 APK 中。 */
    fun hasModelAssets(): Boolean = appContext.assets.list(MODEL_DIR)?.isNotEmpty() == true

    fun isReady(): Boolean = recognizer != null

    fun isLoading(): Boolean = loading

    fun isRecording(): Boolean = recording

    /** 后台加载模型（幂等：只加载一次），完成后经 [Listener] 回调。 */
    fun load(listener: Listener) {
        this.listener = listener
        if (recognizer != null) {
            mainHandler.post { listener.onModelReady() }
            return
        }
        if (loading) return
        loading = true
        mainHandler.post { listener.onModelLoading() }
        thread(name = "SherpaLoadModel") {
            try {
                if (!hasModelAssets()) {
                    throw IllegalStateException(str(R.string.sherpa_assets_missing, MODEL_DIR))
                }
                val cfg = OnlineRecognizerConfig(
                    featConfig = FeatureConfig().also {
                        it.sampleRate = SAMPLE_RATE
                        it.featureDim = 80
                        it.dither = 0.0f
                    },
                    modelConfig = OnlineModelConfig().also {
                        it.tokens = TOKENS
                        it.numThreads = 2
                        it.debug = false
                        it.modelType = "zipformer"
                        it.transducer = OnlineTransducerModelConfig().also { t ->
                            t.encoder = ENCODER
                            t.decoder = DECODER
                            t.joiner = JOINER
                        }
                    },
                    endpointConfig = EndpointConfig(),
                    enableEndpoint = false,
                )
                val rec = OnlineRecognizer(appContext.assets, cfg)
                // 构造成功即代表模型可用
                recognizer = rec
                mainHandler.post { listener.onModelReady() }
            } catch (t: Throwable) {
                t.printStackTrace()
                //Log.e("error","err: "+str(R.string.sherpa_model_load_failed))
               // mainHandler.post { listener.onModelError(str(R.string.sherpa_model_load_failed, t.message ?: "")) }
            } finally {
                loading = false
            }
        }
    }

    /** 开始录音识别。模型未就绪时回调 [Listener.onError]。 */
    fun start(listener: Listener) {
        this.listener = listener
        val rec = recognizer
        if (rec == null) {
            mainHandler.post { listener.onError(str(R.string.sherpa_model_not_ready)) }
            return
        }
        startRecording(rec, listener)
    }

    /** 结束录音并输出整句结果（结果经 [Listener.onResult] 异步回调）。 */
    fun stop() {
        if (!recording) return
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
            // 未启动时 stop() 会抛 IllegalStateException，忽略
        }
    }

    /** 停止并释放全部资源（Activity onDestroy 时调用）。 */
    fun release() {
        stop()
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
        recognizer?.release()
        recognizer = null
        listener = null
    }

    // ---------------- 内部实现 ----------------

    private fun startRecording(rec: OnlineRecognizer, listener: Listener) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            mainHandler.post { listener.onError(str(R.string.sherpa_mic_alloc_failed)) }
            return
        }
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBuf * 2)
        } catch (t: Throwable) {
            mainHandler.post { listener.onError(str(R.string.sherpa_mic_open_failed, t.message ?: "")) }
            return
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            try {
                audio.release()
            } catch (_: Throwable) {
            }
            mainHandler.post { listener.onError(str(R.string.sherpa_mic_failed)) }
            return
        }

        audioRecord = audio
        val stream: OnlineStream = rec.createStream()
        audio.startRecording()
        recording = true
        mainHandler.post { listener.onPartial("") }

        recordingThread = thread(name = "SherpaRecognize") {
            val chunkSamples = SAMPLE_RATE / 10 // 100ms + 每块
            val buffer = ShortArray(chunkSamples)
            var lastPartial = ""
            try {
                while (recording) {
                    val read = audio.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text
                    if (text != lastPartial) {
                        lastPartial = text
                        mainHandler.post { listener.onPartial(text) }
                    }
                }

                // 输入结束，回溯输出完整一句话
                stream.inputFinished()
                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }
                val finalText = rec.getResult(stream).text
                mainHandler.post { listener.onResult(finalText) }
            } catch (t: Throwable) {
                val err = str(R.string.sherpa_recognize_error, "${t.javaClass.simpleName}: ${t.message}")
                mainHandler.post { listener.onError(err) }
            } finally {
                stream.release()
                recording = false
                try {
                    audio.release()
                } catch (_: Throwable) {
                }
                audioRecord = null
            }
            recordingThread = null
        }
    }
}

package ai.whalephone.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * 语音输入。
 *
 * 用 SpeechRecognizer 而不是 RecognizerIntent:后者会拉起一个全屏的识别界面 ——
 * 那是一次真正的界面打断,而这个 app 的整个立场就是不打断。SpeechRecognizer 在
 * 进程内跑,识别中的文字直接回填到悬浮球那条输入栏里,机主看得见但屏幕没被换掉。
 *
 * **准不准这件事,我们的参数说了不算。** 调用参数已经是对的(自由文本 + zh-CN +
 * 分段结果),真正决定准确率的是设备上装了哪个识别引擎、系统默认选了哪个 ——
 * 那是设备级变量。实测这台机器默认走 Google 的 GoogleTTSRecognitionService,
 * 中文识别明显不如国内引擎;而装着的讯飞输入法**没有注册 RecognitionService**,
 * 标准 API 拿不到它。
 *
 * 一度在设置里做过引擎选择器,删掉了:**它是系统那个设置的劣质副本**。Android
 * 本来就有「默认语音识别服务」,系统那条路走 SpeechRecognitionManagerService;
 * 而 app 自己 createSpeechRecognizer(ctx, component) 是直接绑服务 —— 实测显式选中
 * 反而硬失败(agsa_transcription_GRPC_ERROR),系统默认至少还会退到端上模型。
 * 同一件事做得比系统自带的更差,还多一份可能不一致的状态,那就不该存在。
 * 设置页现在只放一个入口,指向系统那个设置。
 *
 * 换一台没有任何识别服务的机器,[available] 会是 false,悬浮球那边直接说清楚,
 * 而不是点了没反应。
 */
class Voice(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** 麦克风电平(dB)。给界面画「它真的在听」用,识别器不一定报 —— 不报就一次都不回调 */
    private val onLevel: (Float) -> Unit = {},
) {

    private var sr: SpeechRecognizer? = null

    fun start() {
        // SpeechRecognizer 必须在有 Looper 的线程上创建和调用,而且只能是同一个线程。
        // 这里的调用方全在主线程(悬浮球的点击回调),不额外切。
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) { onError("语音识别起不来"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { onLevel(v) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onPartialResults(b: Bundle?) {
                best(b)?.let(onPartial)
            }

            override fun onResults(b: Bundle?) {
                onFinal(best(b).orEmpty())
            }

            override fun onError(code: Int) {
                // 把错误码翻成机主看得懂的话。原样报 "ERROR_7" 等于没报。
                onError(
                    when (code) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清,再说一次"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务连不上网"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙,稍等一下"
                        else -> "识别失败($code)"
                    }
                )
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        runCatching { r.startListening(i) }
            .onFailure { Log.w(TAG, "startListening 失败", it); onError("语音识别起不来") }
    }

    fun stop() {
        val r = sr ?: return
        sr = null
        runCatching { r.stopListening() }
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        private const val TAG = "WPVoice"

        fun available(ctx: Context) =
            runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)


        fun micGranted(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

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
 * 所以引擎不由代码定死:[engines] 把设备上注册过的都列出来,机主在设置里选,
 * 留空就是系统默认(也就是今天的行为)。这不是「让用户去解决」—— 是把一个我们
 * 确实决定不了、而不同机器上答案不同的变量,交给唯一能试出答案的人。
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
        val r = runCatching { create() }.getOrNull()
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

    /** 机主选过引擎就用他选的,没选就用系统默认 */
    private fun create(): SpeechRecognizer {
        val picked = Config.get(ctx, Config.KEY_VOICE_ENGINE)
            .takeIf { it.isNotBlank() }
            ?.let { android.content.ComponentName.unflattenFromString(it) }
        return if (picked != null) SpeechRecognizer.createSpeechRecognizer(ctx, picked)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
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

        /**
         * 这台设备上注册过的识别引擎,附上人看得懂的名字。
         *
         * 一台机器上通常不止一个(实测这台有三个:Google 的、端上那个、还有别的 app
         * 带的),而它们的中文准确率差别很大 —— 差到值得让机主自己试一遍。
         */
        fun engines(ctx: Context): List<Pair<android.content.ComponentName, String>> =
            runCatching {
                val pm = ctx.packageManager
                pm.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
                    .map { ri ->
                        val si = ri.serviceInfo
                        android.content.ComponentName(si.packageName, si.name) to
                            si.applicationInfo.loadLabel(pm).toString()
                    }
            }.getOrDefault(emptyList())

        fun micGranted(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

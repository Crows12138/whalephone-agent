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
 * 这台机器上系统默认的识别服务是 Google 的(voice_recognition_service 指向
 * com.google.android.tts/...GoogleTTSRecognitionService),另外还装着端上识别
 * (com.google.android.as 的 AiAiSpeechRecognitionService)。换一台没有识别服务的
 * 机器 [available] 会是 false,悬浮球那边会直接说清楚,而不是点了没反应。
 */
class Voice(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
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
            override fun onRmsChanged(v: Float) {}
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

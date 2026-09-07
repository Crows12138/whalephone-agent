package ai.whalephone.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
 * **点下去和真的开始听之间隔着几百毫秒**,这段时间说的话不进音频。唯一准确的
 * 就绪信号是 [RecognitionListener.onReadyForSpeech] —— 界面的文案、动画和那一声
 * 提示音全挂在它上面([onReady]),不挂在 startListening 返回的那一刻。挂错地方
 * 等于让界面撒谎:它说「在听」的时候麦克风其实还没开。
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
    /** 麦克风真的开了,现在说的话才进得去。在这之前界面不该说「在听」 */
    private val onReady: () -> Unit = {},
) {

    private var sr: SpeechRecognizer? = null

    /** 机主点下去的时刻,用来量「点下去到能说」到底有多久 */
    private var startAt = 0L
    private var ready = false

    fun start() {
        startAt = SystemClock.elapsedRealtime()
        // SpeechRecognizer 必须在有 Looper 的线程上创建和调用,而且只能是同一个线程。
        // 这里的调用方全在主线程(悬浮球的点击回调),不额外切。
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()
        if (r == null) { onError("语音识别起不来"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            // 就绪 = 识别器第一次表现出活着。正常是 onReadyForSpeech,但那是一份
            // 契约,不是一条保证 —— 万一某个引擎不发,界面就会永远停在「先别说」,
            // 而它其实一直在听。那比原来的毛病更糟。所以另外两个只有开了麦才可能
            // 发生的回调也算数,谁先到谁作数。
            override fun onReadyForSpeech(p: Bundle?) = markReady("onReadyForSpeech")
            override fun onBeginningOfSpeech() = markReady("有人开口了")
            override fun onRmsChanged(v: Float) { markReady("有电平了"); onLevel(v) }
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
                        // 「连不上网」这句话容易被读成「你手机没网」。真实情况是识别
                        // 引擎连不上**它自己的后端** —— 本 app 连模型接口一直是好的。
                        // 实测这台机器上就是这样(见 FINDINGS),不点破的话机主会去
                        // 查自己的网络,查不出任何问题。
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别引擎连不上它的服务器(不是你没网)"
                        // 离线包没装。这条和上一条常常是一对:在线走不通、离线又没有
                        // 语言包,而语言包本身也要联网下载。
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "这台手机没装中文的离线识别包"
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

    /** 第一次听到动静就宣布就绪,之后的回调不再重复响 */
    private fun markReady(why: String) {
        if (ready) return
        ready = true
        Log.i(TAG, "麦克风开了($why),点下去到这里 ${SystemClock.elapsedRealtime() - startAt} ms")
        cue()
        onReady()
    }

    /**
     * 「可以说了」的那一下。
     *
     * 响的时候麦克风已经开着,这一声会被录进去 —— 所以要短要轻。实测 120 ms 的单音
     * 不影响识别结果;系统自己的语音搜索也是在同一时刻响的。
     *
     * 出不了声就振一下。出不了声有三种:静音档、振动档、系统音量拧到 0。不能只看
     * 前两种 —— 音量为 0 的时候提示音是「放了但没人听见」,而机主看到的就是这个功能
     * 没做,比不做还糟。
     */
    private fun cue() {
        if (beeped()) return
        runCatching {
            ctx.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "振不了", it) }
    }

    /** 真的出声了才返回 true。走系统音流:这一声是操作反馈,归机主的「系统音」管 */
    private fun beeped(): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (am.getStreamVolume(AudioManager.STREAM_SYSTEM) <= 0) return false
        return runCatching {
            // ToneGenerator 不会自己回收,放完得 release,否则下一次就没声了
            val tg = ToneGenerator(AudioManager.STREAM_SYSTEM, TONE_VOLUME)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
            Handler(Looper.getMainLooper())
                .postDelayed({ runCatching { tg.release() } }, TONE_MS + 200L)
        }.onFailure { Log.w(TAG, "提示音放不出来", it) }.isSuccess
    }

    private fun best(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        private const val TAG = "WPVoice"

        private const val TONE_MS = 120
        private const val TONE_VOLUME = 80  // 还要再乘一遍系统音量,最终多响由机主定

        fun available(ctx: Context) =
            runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)


        fun micGranted(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }
}

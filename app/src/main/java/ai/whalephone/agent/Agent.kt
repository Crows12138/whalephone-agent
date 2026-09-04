package ai.whalephone.agent

import android.util.Log
import org.json.JSONObject

/**
 * 决策循环:看副屏 -> 让模型出一个动作 -> 执行 -> 再看。
 *
 * 感知用无障碍树而不是截图,原因有三个,按重要性排:
 *   1. 虚拟屏的截图路径本来就窄(screencap 抓不到,只能自己接 ImageReader),
 *      而无障碍树是唯一一个原生就带显示器维度的读取通道;
 *   2. 结构化文本比图便宜一个数量级,长时任务要跑几十上百步,这个差距是决定性的;
 *   3. 树里有 clickable / editable / bounds,动作可以按序号精确落点,
 *      不需要模型去猜坐标 —— 坐标猜错在副屏上不会被用户看见,但会静默走错。
 * 截图留作兜底:WebView / Canvas / 游戏这类树里读不出内容的场景。
 */
class Agent(
    private val hands: Hands,
    private val llm: Llm,
    private val goal: String,
    private val maxSteps: Int = 40,
) {
    data class Step(val n: Int, val thought: String, val action: String, val result: String)
    data class Outcome(val done: Boolean, val message: String, val trace: List<Step>)

    private val trace = mutableListOf<Step>()
    private var pendingQuestion: String? = null

    /** 需要用户拍板时,回调出去(上层用通知承接,而不是弹窗抢屏) */
    var onAsk: ((String) -> Unit)? = null
    var onStep: ((Step) -> Unit)? = null

    fun run(): Outcome {
        var lastRender = ""
        var sameCount = 0

        for (n in 1..maxSteps) {
            val snap = hands.snapshot()
            val render = snap.render()

            if (render == lastRender) sameCount++ else sameCount = 0
            lastRender = render
            if (sameCount >= 3) {
                return Outcome(false, "界面连续 4 步没有变化,判定卡住了", trace)
            }

            val reply = runCatching {
                llm.chat(
                    listOf(
                        Llm.Message("system", SYSTEM),
                        Llm.Message("user", userTurn(n, render)),
                    )
                )
            }.getOrElse { return Outcome(false, "模型调用失败: ${it.message}", trace) }

            val act = parse(reply) ?: run {
                Log.w(TAG, "解析不了模型输出: ${reply.take(300)}")
                null
            } ?: continue

            val thought = act.optString("thought")
            val name = act.optString("action")
            Log.i(TAG, "第 $n 步 $name  $thought")

            when (name) {
                "done" -> {
                    val msg = act.optString("summary", "完成")
                    record(n, thought, name, msg)
                    return Outcome(true, msg, trace)
                }
                "ask" -> {
                    val q = act.optString("question", "需要你确认")
                    pendingQuestion = q
                    record(n, thought, name, q)
                    onAsk?.invoke(q)
                    return Outcome(false, "等待用户确认: $q", trace)
                }
                else -> record(n, thought, name, execute(name, act))
            }
        }
        return Outcome(false, "走满 $maxSteps 步还没完成", trace)
    }

    private fun execute(name: String, a: JSONObject): String = when (name) {
        "click"      -> hands.click(a.getInt("index"))
        "long_click" -> hands.longClick(a.getInt("index"))
        "set_text"   -> hands.setText(a.getInt("index"), a.optString("text"))
        "scroll"     -> hands.scroll(a.getInt("index"), a.optString("direction", "forward") == "forward")
        "launch"     -> hands.launch(a.getString("package"))
        "back"       -> hands.back()
        "home"       -> hands.home()
        "wait"       -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(100, 60_000)); "等了一下" }
        else         -> "不认识的动作 $name"
    }.also { Thread.sleep(600) }   // 留出界面响应时间,否则下一帧快照拍到的是旧界面

    private fun record(n: Int, thought: String, action: String, result: String) {
        val s = Step(n, thought, action, result)
        trace += s
        onStep?.invoke(s)
    }

    private fun userTurn(n: Int, render: String): String = buildString {
        appendLine("目标:$goal")
        appendLine()
        if (trace.isNotEmpty()) {
            appendLine("已经做过的:")
            trace.takeLast(8).forEach { appendLine("  ${it.n}. ${it.action} -> ${it.result}") }
            appendLine()
        }
        appendLine("这是副屏第 $n 步的界面:")
        append(render)
    }

    /** 模型爱把 JSON 包在 ``` 里,或者前后带一句话。取第一个完整的 JSON 对象。 */
    private fun parse(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until reply.length) {
            when (reply[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0)
                    return runCatching { JSONObject(reply.substring(start, i + 1)) }.getOrNull()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "WPAgent"

        private val SYSTEM = """
            你在操作一台安卓手机的「副屏」。手机主人此刻正在同一台手机的主屏上做他自己的事,
            他不应该察觉到你的存在 —— 他的画面、焦点、键盘都不归你用。

            你能看到副屏当前界面的元素列表,每个元素有一个序号。你通过序号操作,不用坐标。

            每一步只输出一个 JSON 对象,前后不要有别的文字:
            {"thought":"一句话说明这一步为什么","action":"动作名", ...动作参数}

            可用动作:
              click       index
              long_click  index
              set_text    index, text
              scroll      index, direction("forward" 往下 / "backward" 往上)
              launch      package(应用包名)
              back        (无参数,只在副屏上返回)
              home        (无参数,回副屏自己的桌面;界面乱了就用它重来)
              wait        ms
              done        summary(任务结果,说清楚查到/做成了什么)
              ask         question(需要主人拍板的事)

            必须守的几条:
            - 序号只在当前这份列表里有效,每一步都会重新编号,不要用上一步的序号。
            - 你没有键盘,文字一律用 set_text 写进输入框。
            - 花钱、给别人发消息、以及任何撤不回来的操作,先 ask,不要自己拍板。
            - 界面连着几步没变,说明你的做法不起作用,换一个,别原地重复。
            - 副屏上可能还停着上一个任务留下的界面。不确定自己在哪就先 home,再 launch。
            - 目标达成就立刻 done,不要多点。
        """.trimIndent()
    }
}

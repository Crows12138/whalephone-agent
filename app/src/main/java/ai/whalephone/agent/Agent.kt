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

    /**
     * 模型的草稿纸。
     *
     * 每一轮它只看得到「最近几步做了什么」和「这一帧长什么样」,更早的**观察**
     * 是不进上下文的。实测的后果:在商品详情页第 6 步就读到了价格 799,往下滚
     * 去找店铺名,价格滚出屏幕之后它又开始反复找价格,来回打转直到被判卡死。
     *
     * 让它把查到的事实显式记下来,后续每一轮都带上。这是长任务能不能收敛的关键 ——
     * 不然模型永远只有一帧的记忆。
     */
    private val notes = mutableListOf<String>()
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

            // performAction 返回 true 只代表节点收下了这个动作,不代表界面有反应。
            // 界面到底动没动,只有这里比得出来 —— 而模型看不到这里。它的历史里
            // 每一步都是「点了 [68]」这种成功记录,于是它会一直点下去,直到被判卡死,
            // 全程不知道自己在原地踏步。把这个事实写回上一步的结果,让它下一轮就看见。
            val idled = render == lastRender && trace.lastOrNull()?.action.let { it != null && it != "note" }
            if (idled) {
                sameCount++
                trace[trace.lastIndex] = trace.last().let { it.copy(result = it.result + "  ← 界面没有任何变化") }
            } else sameCount = 0
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
                "note" -> {
                    val v = act.optString("text")
                    if (v.isNotBlank()) notes += v
                    record(n, thought, name, "记下了:$v")
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
        Log.i(TAG, "     -> $result")
        val s = Step(n, thought, action, result)
        trace += s
        onStep?.invoke(s)
    }

    private fun userTurn(n: Int, render: String): String = buildString {
        appendLine("目标:$goal")
        appendLine()
        if (notes.isNotEmpty()) {
            appendLine("已经查到的:")
            notes.forEach { appendLine("  · $it") }
            appendLine()
        }
        if (trace.isNotEmpty()) {
            appendLine("已经做过的:")
            trace.takeLast(8).forEach { appendLine("  ${it.n}. ${it.action} -> ${it.result}") }
            appendLine()
        }
        // 卡死检测靠的是「快照连续几帧一样」,但模型可以一直点不同的元素、
        // 让界面每帧都有点变化,却始终没往目标推进 —— 那种循环检测不到。
        // 连续三次同一个动作就直接把这件事摊开说,比让它自己从历史里看出来可靠。
        val last3 = trace.takeLast(3)
        if (last3.size == 3 && last3.map { it.action }.distinct().size == 1) {
            appendLine("注意:你已经连续三步都在 ${last3[0].action},显然没有推进。换一个动作。")
            appendLine()
        }
        appendLine("这是第 $n 步(最多 $maxSteps 步)。副屏当前界面:")
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
              set_text    index, text(自带聚焦并覆盖原内容,不用先点它、也不用先清空)
              scroll      index, direction("forward" 往下 / "backward" 往上)
              launch      package(应用包名)
              back        (无参数,只在副屏上返回)
              home        (无参数,回副屏自己的桌面;界面乱了就用它重来)
              wait        ms
              note        text(把查到的事实记下来,后面每一轮都还看得到)
              done        summary(任务结果,说清楚查到/做成了什么)
              ask         question(需要主人拍板的事)

            必须守的几条:
            - 序号只在当前这份列表里有效,每一步都会重新编号,不要用上一步的序号。
            - 你没有键盘,文字一律用 set_text 写进输入框。
            - 花钱、给别人发消息、以及任何撤不回来的操作,先 ask,不要自己拍板。
            - 历史里标了「界面没有任何变化」的那步,是白做的 —— 元素收下了动作但什么也没发生。
              再点一次结果一样。要么换个元素,要么换条路,要么承认这条路走不通。
            - 想往输入框里写字就直接 set_text。反复点同一个元素等它「变成输入状态」是没用的。
            - 副屏上可能还停着上一个任务留下的界面。不确定自己在哪就先 home,再 launch。
            - 一屏放不下的信息,看到一条就先 note 一条再往下翻。你每一轮只看得见当前这一帧,
              滚走了就没了 —— 靠回头再找会原地打转。
            - 目标里有几项而某一项确实找不到时,把找到的 note 下来,然后 done,
              在 summary 里说清楚哪项没拿到、你试过什么。**无限找下去是最差的结果**:
              用户拿到「价格是 799,店铺名没找到」是有用的,拿到「卡住了」是没用的。
            - 留意步数。过了一半还没接近目标,就该考虑换路子或者带着已有结果收尾。
            - 目标达成就立刻 done,不要多点。
        """.trimIndent()
    }
}

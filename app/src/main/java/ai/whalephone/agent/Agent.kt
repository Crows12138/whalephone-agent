package ai.whalephone.agent

import android.util.Log
import org.json.JSONObject

/**
 * 决策循环:看副屏 -> 让模型出一个动作 -> 执行 -> 再看。
 *
 * 感知用无障碍树而不是截图,原因有三个,按重要性排:
 *   1. 无障碍树是唯一一个原生就带显示器维度的读取通道。uiautomator 只读全局焦点
 *      那块屏,而焦点在用户手里 —— 这条对整个方案是决定性的;
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

        // n 只数「真正动过手」的步。让路的那些轮不占预算 —— 否则机主多打几次字,
        // 步数预算就被等待吃光,任务明明还没开始做就报「走满 N 步」。
        // rounds 是防死循环的兜底:万一让路条件一直成立,总轮数还是有上界。
        var n = 0
        var rounds = 0
        while (n < maxSteps && rounds < maxSteps * 4) {
            rounds++
            n++
            // 接下来是拍快照 + 等模型返回,agent 这几秒什么都不做。
            // 焦点这段时间没有理由押在副屏上 —— 押着的代价是机主一碰自己的屏幕就可能
            // 「Application does not have a focused window」。实测这类空闲占了任务
            // 全程的大部分,所以还回去能把风险窗口从「整个任务」压到「只有动作那几下」。
            hands.returnFocusToOwner()
            val snap = hands.snapshot()
            val render = snap.render()

            // performAction 返回 true 只代表节点收下了这个动作,不代表界面有反应。
            // 界面到底动没动,只有这里比得出来 —— 而模型看不到这里。它的历史里
            // 每一步都是「点了 [68]」这种成功记录,于是它会一直点下去,直到被判卡死,
            // 全程不知道自己在原地踏步。把这个事实写回上一步的结果,让它下一轮就看见。
            // 让过路的那一步本来就没动手,界面当然没变 —— 它不能算进「原地踏步」。
            // 不排掉的话,机主多打几次字,任务就会被误判成卡死。
            val idled = render == lastRender && !lastStepYielded &&
                trace.lastOrNull()?.action.let { it != null && it != "note" }
            lastStepYielded = false   // 判完就清:它描述的是**上一步**
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
                // 参数缺了、类型不对、序号越界 —— 这些不该杀掉整个任务。
                // 模型下一轮看得到错在哪,自己就能改;抛出去则是一步走错、满盘皆输。
                else -> record(n, thought, name,
                    runCatching { execute(name, act) }
                        .getOrElse { "这个动作的参数不对(${it.message});照着动作表把参数补齐再来一次" })
            }
            if (lastStepYielded) n--   // 这一轮只是等机主打完字,没动手,不算一步
        }
        return Outcome(false, "走满 $maxSteps 步还没完成", trace)
    }

    /** 上一步是不是「让了路、没动手」。卡死检测要跳过这种步。 */
    private var lastStepYielded = false

    private fun execute(name: String, a: JSONObject): String {
        // 机主在打字就先让路,**每个动作都让**,不按动作类型区分。
        //
        // 试过按类型区分,退回来了。当时的依据是逐个操作归因表:无障碍动作不动焦点
        // 也不动键盘,只有往副屏 `am start` 会收键盘,所以只挡 launch。真机 A/B:
        //
        //   只挡 launch        打字期间键盘被收起 1   任务 done=true
        //   每个动作都挡        打字期间键盘被收起 0   任务 done=true
        //
        // 推错在哪:归因表里「页内跳转不收键盘」那一行,测的是同一个 Activity 内的
        // 导航。而淘宝点一下搜索栏是**新开一个 Activity** —— 在窗口管理器眼里那和
        // am start 是同一件事,新的可获焦窗口一出现,主屏就丢焦点、键盘就被收起。
        // agent 事先不可能知道哪一次点击会开新窗口。
        //
        // 代价是机主打字期间 agent 基本停摆。这个代价可以接受,前提是「打字期间」
        // 这句话说得准 —— 见 Conflict.ownerStillAtIt:判据除了看键盘在不在,
        // 还要看最近有没有键真的落下。键盘被人留在屏上走开,不算打字。
        val waited = hands.yieldToOwner()
        // 等过之后就不能再照着旧决策动手了。
        //
        // 模型这一步的序号和位置,都是在**等待之前**那一帧快照上算出来的。等待期间
        // 机主在操作他自己那块屏,副屏上的 App 也可能自己往前走(动画、加载、弹窗)。
        // 拿旧序号去点,轻则点空,重则点到别的东西上 —— 实测两轮任务就是这么跑飞的:
        // 让了两秒,接着点击落空,再往下模型就读不懂自己在哪了。
        //
        // 所以让过路就把这一步退回去,让循环重新拍一帧、模型重新决定。代价是多一次
        // 模型调用,换的是「决策和世界是同一时刻的」这条不变量。
        if (waited >= 1000) {
            lastStepYielded = true
            return "机主刚才在打字,agent 让了 ${waited / 1000} 秒没动手。" +
                "这段时间界面可能已经变了,上面这一帧是最新的,重新看一眼再决定"
        }
        val r = doExecute(name, a)
        Thread.sleep(600)   // 留出界面响应时间,否则下一帧快照拍到的是旧界面
        return r
    }

    private fun doExecute(name: String, a: JSONObject): String = when (name) {
        "click"      -> hands.click(a.getInt("index"))
        "long_click" -> hands.longClick(a.getInt("index"))
        "set_text"   -> hands.setText(a.getInt("index"), a.optString("text"))
        "scroll"     -> hands.scroll(a.getInt("index"), a.optString("direction", "forward") == "forward")
        "swipe"      -> hands.swipe(if (a.has("index")) a.getInt("index") else null,
                                    a.optString("direction", "up"))
        "double_tap" -> hands.doubleTap(a.getInt("index"))
        "enter"      -> hands.enter()
        // 模型给这个参数起过 package / app / name 三种名字,都收下 ——
        // 与其在提示词里反复强调字段名,不如让接口宽容一点。
        "launch"     -> hands.launch(
            listOf("package", "app", "name", "app_name")
                .firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
                ?: throw IllegalArgumentException("launch 要一个 package 参数(app 显示名或包名)")
        )
        "back"       -> hands.back()
        "home"       -> hands.home()
        // 空屏上等待是**证明无效**的:副屏上一个 App 都没起来,不存在正在加载的东西,
        // 等多久界面都不会变。真机上撞到过一轮 —— 让路 45 秒之后模型重新看了一眼,
        // 看到空屏,连着选了三次 wait,直到卡死检测把任务判死。
        // 与其让它撞满卡死阈值,不如把这件事直接告诉它:这不是覆盖它的决策,
        // 是把一个它推不出来的事实(空屏不会自己变)喂回循环里。
        "wait"       -> {
            val s = hands.last
            if (s != null && s.elements.isEmpty() && s.packages.isEmpty() && s.wmSays == null)
                "副屏上还一个 App 都没有,没有任何东西在加载 —— 等下去界面不会变。用 launch 打开 App"
            else { Thread.sleep(a.optLong("ms", 1000).coerceIn(100, 60_000)); "等了一下" }
        }
        else         -> "不认识的动作 $name"
    }

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
                          —— 语义滚动,只对本身可滚动的列表有效
              swipe       direction("up"/"down"/"left"/"right"),index 可省(省了就划整块屏)
                          —— 真实划动。轮播图、侧边抽屉、左滑删除这些 scroll 滚不动的,用它
              double_tap  index
              enter       (无参数,回车/搜索键)
              launch      package(app 的显示名或包名都行,比如「淘宝」「计算器」;不用猜包名)
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
            - 搜索框填完直接 enter 提交,比去树里找「搜索」按钮可靠 —— 那个按钮不一定在树里。
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

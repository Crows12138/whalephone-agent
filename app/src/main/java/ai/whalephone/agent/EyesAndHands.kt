package ai.whalephone.agent

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.util.SparseLongArray
import android.view.accessibility.AccessibilityEvent

private const val TAG = "WPEyes"

/**
 * 无障碍服务:agent 的眼睛和手。
 *
 * 感知和操作都在这里,因为只有 AccessibilityService 能跨显示器读窗口
 * (`getWindowsOnAllDisplays()`,API 30+)。ADB 侧的 `uiautomator dump --display`
 * 参数被系统忽略,只能读全局焦点所在的屏 —— 而「用户正在用手机」恰恰意味着
 * 焦点在主屏。这是 on-device 服务成为必需品而非可选项的原因。
 *
 * 广播接口供 adb 驱动测试:
 *   DUMP                          列出所有显示器及其窗口
 *   SNAP  --ei display N          打印该屏的快照(模型看到的文本)
 *   CLICK --ei display N --es text S | --ei index I
 *   TEXT  --ei display N --ei index I --es text S
 *   TYPING                        机主此刻在不在打字,两路信号分别报
 *   YIELD                         直接测闸门:机主在打字时会不会停下来等
 */
class EyesAndHands : AccessibilityService() {

    private val clipboard by lazy { ClipboardGuard(this) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val d = i?.getIntExtra("display", -1) ?: -1
            when (i?.action) {
                ACT_DUMP  -> dumpAllDisplays()
                ACT_SNAP  -> snap(d)
                ACT_CLICK -> click(d, i.getStringExtra("text"), i.getIntExtra("index", -1))
                ACT_TEXT  -> setText(d, i.getIntExtra("index", -1), i.getStringExtra("text") ?: "")
                ACT_BRIDGE -> Thread { bridgeSelfTest() }.start()
                ACT_TYPING -> Thread { typingCheck() }.start()
                ACT_YIELD -> Thread { yieldCheck() }.start()
                ACT_RUN -> {
                    val g = i.getStringExtra("goal").orEmpty()
                    Log.i(TAG, "收到任务: $g")
                    if (g.isNotBlank()) AgentService.start(this@EyesAndHands, g)
                }
                ACT_CONFIG -> {
                    val k = i.getStringExtra("key").orEmpty()
                    // 不带 value 就是删这个键。adb 的 `--es value ""` 传不进空串,
                    // 想清掉一个配置只能靠「缺席」表达。
                    val v = i.getStringExtra("value")
                    if (k == "TRACE_EVENTS") trace = (v == "1")
                    if (k.isNotBlank()) {
                        if (v == null) {
                            Config.remove(this@EyesAndHands, k)
                            Log.i(TAG, "配置 $k 已清除")
                        } else {
                            Config.set(this@EyesAndHands, k, v)
                            Log.i(TAG, "配置 $k = ${if (k.contains("KEY")) "***" else v}")
                        }
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "=== 无障碍服务已连接 ===")
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(ACT_DUMP); addAction(ACT_SNAP); addAction(ACT_CLICK)
                addAction(ACT_TEXT); addAction(ACT_BRIDGE); addAction(ACT_CONFIG); addAction(ACT_RUN)
                addAction(ACT_TYPING); addAction(ACT_YIELD)
            },
            Context.RECEIVER_EXPORTED,
        )
        // onServiceConnected 在服务重连时会再次调用,不去重会越挂越多个接收器
        if (screenOn == null) screenOn = Watch.attach(this)
        dumpAllDisplays()
    }

    private var screenOn: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(receiver) }
        runCatching { screenOn?.let { unregisterReceiver(it) } }
        super.onDestroy()
    }

    /**
     * 每块屏最近一次界面变动的时刻。
     *
     * performAction(ACTION_CLICK) 返回 true 只说明节点收下了这个动作 —— 它走的是
     * View.performClick(),只触发 OnClickListener。App 自己用 onTouchListener 处理
     * 触摸时(淘宝商品页底栏就是这样),节点照样返回 true,界面纹丝不动。
     * 一个动作有没有真的生效,只能看界面动没动 —— 这是唯一可靠的判据。
     */
    private val lastChange = SparseLongArray()

    /** 事件追踪开关,只在 CONFIG 广播时更新,不在事件回调里查配置 */
    @Volatile private var trace = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 这个回调跑在主线程上,而且滚动一个信息流就是成百上千次。
        // 这里做的任何事都得是常数级的 —— 原来每次都读一遍 SharedPreferences,
        // 改成只读内存里的开关(CONFIG 广播来的时候更新)。
        if (trace) Log.i(TAG, "evt 屏=${e.displayId} ${AccessibilityEvent.eventTypeToString(e.eventType)}")
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            // 有些控件把值藏在自己那儿,不进无障碍树 —— 三星计算器的输入框就是,
            // 按了数字之后树里还是「计算器输入字段」。这时候唯一能证明「点生效了」
            // 的就是这条文本变化事件。漏了它,每按一个数字都会被判成「点不动」,
            // 然后还会多补一次真实触摸,把数字按两遍。
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                synchronized(lastChange) { lastChange.put(e.displayId, SystemClock.uptimeMillis()) }
        }
    }

    /** 从 [since] 起,这块屏的界面有没有动过 */
    fun changedSince(displayId: Int, since: Long): Boolean =
        synchronized(lastChange) { lastChange.get(displayId, 0L) > since }
    override fun onInterrupt() {}

    // ---- 广播处理 ----

    private fun dumpAllDisplays() {
        val all = windowsOnAllDisplays
        Log.i(TAG, "---- 显示器 ${all.size()} 块  全局焦点屏=${activeDisplay()} ----")
        for (i in 0 until all.size()) {
            val id = all.keyAt(i)
            val wins = all.valueAt(i)
            Log.i(TAG, "  Display $id : ${wins.size} 个窗口")
            wins.forEach { w ->
                Log.i(TAG, "    type=${w.type} focused=${w.isFocused} active=${w.isActive} " +
                        "pkg=${w.root?.packageName ?: "-"} title=${w.title}")
            }
        }
        Log.i(TAG, "---- end ----")
    }

    private fun snap(displayId: Int) {
        val s = snapshotOf(displayId)
        Log.i(TAG, "---- SNAP display=$displayId ----")
        s.render().lineSequence().forEach { if (it.isNotBlank()) Log.i(TAG, it) }
        Log.i(TAG, "---- 共 ${s.elements.size} 个元素 ----")
    }

    private fun click(displayId: Int, text: String?, index: Int) {
        val before = activeDisplay()
        val s = snapshotOf(displayId)
        val el = when {
            index >= 0 -> s.byIndex(index)
            text != null -> s.elements.firstOrNull { it.text == text || it.desc == text }
            else -> null
        }
        if (el == null) {
            Log.w(TAG, "CLICK 找不到目标 (display=$displayId index=$index text=$text), " +
                    "该屏共 ${s.elements.size} 个元素")
            return
        }
        Actions.click(el)
        Thread.sleep(500)
        val after = activeDisplay()
        Log.i(TAG, "焦点 $before -> $after  ${if (before == after) "未被抢走 OK" else "被抢走了"}")
    }

    private fun setText(displayId: Int, index: Int, text: String) {
        val h = hands(displayId)
        h.snapshot()
        Log.i(TAG, "TEXT [$index] <- 「$text」  ${h.setText(index, text)}")
    }

    /** 每块屏一双手,复用同一份快照缓存 */
    private val handsByDisplay = HashMap<Int, Hands>()
    private fun hands(displayId: Int): Hands =
        handsByDisplay.getOrPut(displayId) { Hands(this, displayId, this) }

    /**
     * 无头自检:把特权桥拉起来,造一块 agent 屏,把实际拿到的标志位打出来。
     * 不需要解锁、不需要人看着 —— 这条路上每一步能不能成都是设备相关的,
     * 必须在真机上量,不能靠读文档下结论。
     */
    /**
     * 注入面回归检查。
     *
     * 凡是命令里含模型给的字符串,都必须走 execArgs(直接 execve,不经过 sh)。
     * 这条链值得盯着:无障碍树里的文字(商品标题、网页内容、通知)是攻击者可控的,
     * 它们进模型上下文、模型输出又变成命令参数 —— 中间任何一处做字符串拼接,
     * 都等于把 uid 2000 的 shell 交出去。
     *
     * 这里拿一个真实 payload 走一遍:参数里的 `;` 如果被 shell 解析,探针文件就会
     * 被创建出来。
     */
    /**
     * 「机主在不在打字」这个判据本身能不能信 —— 单独测,不要从整轮任务里反推。
     *
     * 两路信号分开报:无障碍窗口列表里的 IME 窗口、IMMS 的 mInputShown。
     * 它们**应该**永远一致;不一致的那一路就是不能用的那一路。整轮任务跑完只能看到
     * 「让了几次」,分不出「没让是因为机主没打字」还是「判据根本读不到」。
     */
    private fun typingCheck() {
        val wins = runCatching { windowsOnAllDisplays.get(Conflict.USER_DISPLAY) }.getOrNull()
        val types = wins?.joinToString(" ") { "${it.type}" } ?: "读不到"
        val byA11y = wins?.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } == true
        val shown = Privileged.exec("dumpsys input_method | grep -m1 mInputShown").trim()
        val noBridge = shown.startsWith("NO_BRIDGE") || shown.startsWith("EXEC_FAIL")
        val byShell = shown.contains("mInputShown=true")
        Log.i(TAG, "---- TYPING ----")
        Log.i(TAG, "  无障碍: $byA11y   (主屏窗口类型: $types)")
        Log.i(TAG, "  IMMS  : ${if (noBridge) "测不了" else "$byShell"}  ($shown)")
        Log.i(TAG, when {
            // 桥没连的时候 IMMS 那一路根本没读到东西,不能拿它去和无障碍比对 ——
            // 那会报出一个假的「两路不一致」,比没有这条检查还糟。
            noBridge      -> "  特权桥没连,IMMS 这一路测不了;只验证了无障碍那一路"
            byA11y == byShell -> "  两路一致 —— 判据可信"
            else          -> "  两路不一致 —— 无障碍这条不能单独用,让路机制会走 IMMS 兜底"
        })
    }

    /**
     * 直接测「闸门」本身:机主在打字时,agent 到底会不会停下来等。
     *
     * 单独测这一条的理由和 typingCheck 一样 —— 从整轮任务里反推不出来。
     * 任务跑完发现键盘没被收起,可能是闸门起作用了,也可能是那一轮机主压根没打字。
     */
    private fun yieldCheck() {
        Log.i(TAG, "---- YIELD ---- 开始等(机主在打字的话这里会卡住)")
        val t = Conflict.yieldWhileOwnerTypes(this)
        Log.i(TAG, "---- YIELD ---- 让了 $t 毫秒后放行")
    }

    private fun injectionCheck() {
        val probe = "/data/local/tmp/wp_inject_probe"
        Privileged.exec("rm -f $probe")
        Privileged.execArgs("echo", "x; touch $probe")
        val leaked = Privileged.exec("ls $probe 2>/dev/null").isNotBlank()
        Log.i(TAG, "注入面: " + if (leaked) "有洞 —— execArgs 的参数被 sh 解析了"
                                else "干净 —— 参数没有被 sh 解析")
        Privileged.exec("rm -f $probe")
    }

    private fun bridgeSelfTest() {
        Log.i(TAG, "---- 特权桥自检 ----")
        Log.i(TAG, "Shizuku 在运行=${Privileged.shizukuAlive()} 已授权=${Privileged.shizukuGranted()}")
        val ok = Privileged.connect(this)
        Log.i(TAG, "桥连上=$ok")
        if (!ok) { Log.i(TAG, "---- 自检中止 ----"); return }
        Log.i(TAG, "whoami: " + Privileged.exec("id").trim())
        injectionCheck()

        val m = resources.displayMetrics
        val d = AgentDisplay.create(m.widthPixels, m.heightPixels, m.densityDpi)
        if (d == null) { Log.e(TAG, "造屏失败"); return }
        Log.i(TAG, "副屏 id=${d.displayId} 标志位=0x${d.flags.toString(16)} 保证=${d.guarantees()}")
        Log.i(TAG, "期望标志位=0x${ShellBridge.AGENT_DISPLAY_FLAGS.toString(16)} " +
                (if (d.flags == ShellBridge.AGENT_DISPLAY_FLAGS) "全拿到" else "降级了"))

        Thread.sleep(1500)
        Log.i(TAG, "系统侧: " + Privileged.exec("dumpsys display | grep -A2 'mDisplayId=${d.displayId}'").trim().take(400))
        Log.i(TAG, "无障碍能否看到这块屏: " + windowsOnAllDisplays.let { all ->
            (0 until all.size()).joinToString(", ") { "${all.keyAt(it)}(${all.valueAt(it).size}窗口)" }
        })
        Log.i(TAG, "截图: " + if (d.captureTo("/sdcard/wp_vd.png")) "成功" else "失败(屏上还没有内容是正常的)")
        d.release()
        Log.i(TAG, "---- 自检结束,副屏已销毁 ----")
    }

    /** 当前活跃(全局焦点)显示器 */
    private fun activeDisplay(): Int {
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) if (all.valueAt(i).any { it.isActive }) return all.keyAt(i)
        return -1
    }

    companion object {
        /** agent 侧唯一的入口。无障碍服务全进程只有一个实例,生命周期由系统托管。 */
        @Volatile var instance: EyesAndHands? = null

        const val ACT_DUMP  = "ai.whalephone.agent.DUMP"
        const val ACT_SNAP  = "ai.whalephone.agent.SNAP"
        const val ACT_CLICK = "ai.whalephone.agent.CLICK"
        const val ACT_TEXT  = "ai.whalephone.agent.TEXT"
        const val ACT_BRIDGE = "ai.whalephone.agent.BRIDGE"
        const val ACT_TYPING = "ai.whalephone.agent.TYPING"
        const val ACT_YIELD = "ai.whalephone.agent.YIELD"
        const val ACT_CONFIG = "ai.whalephone.agent.CONFIG"
        const val ACT_RUN = "ai.whalephone.agent.RUN"
    }
}

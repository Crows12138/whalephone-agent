package ai.whalephone.agent

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
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
                addAction(ACT_TEXT); addAction(ACT_BRIDGE)
            },
            Context.RECEIVER_EXPORTED,
        )
        dumpAllDisplays()
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
    private fun bridgeSelfTest() {
        Log.i(TAG, "---- 特权桥自检 ----")
        Log.i(TAG, "Shizuku 在运行=${Privileged.shizukuAlive()} 已授权=${Privileged.shizukuGranted()}")
        val ok = Privileged.connect(this)
        Log.i(TAG, "桥连上=$ok")
        if (!ok) { Log.i(TAG, "---- 自检中止 ----"); return }
        Log.i(TAG, "whoami: " + Privileged.exec("id").trim())

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
    }
}

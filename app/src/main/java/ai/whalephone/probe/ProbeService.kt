package ai.whalephone.probe

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

private const val TAG = "WPProbe"

/**
 * 探针无障碍服务。只验证两个假设:
 *   H1  getWindowsOnAllDisplays() 能否读到非焦点显示器上的窗口
 *   H2  performAction(ACTION_CLICK) 是否会抢走全局焦点
 * 通过 adb 广播触发,结果打到 logcat。
 */
class ProbeService : AccessibilityService() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                ACT_DUMP  -> dumpAllDisplays()
                ACT_CLICK -> clickByText(
                    i.getIntExtra("display", -1),
                    i.getStringExtra("text") ?: ""
                )
                ACT_TREE  -> dumpTree(i.getIntExtra("display", -1))
            }
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "=== ProbeService connected ===")
        val f = IntentFilter().apply {
            addAction(ACT_DUMP); addAction(ACT_CLICK); addAction(ACT_TREE)
        }
        registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        dumpAllDisplays()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** H1:枚举所有显示器上的窗口 */
    private fun dumpAllDisplays() {
        Log.i(TAG, "---- H1 getWindowsOnAllDisplays ----")
        val all = windowsOnAllDisplays
        Log.i(TAG, "显示器数量 = ${all.size()}")
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            val wins = all.valueAt(i)
            Log.i(TAG, "  Display $displayId : ${wins.size} 个窗口")
            wins.forEach { w ->
                val pkg = w.root?.packageName ?: "(无root)"
                Log.i(TAG, "    type=${w.type} focused=${w.isFocused} active=${w.isActive} pkg=$pkg title=${w.title}")
            }
        }
        Log.i(TAG, "---- H1 end ----")
    }

    /** 打印某个显示器上第一个窗口的可点击节点 */
    private fun dumpTree(displayId: Int) {
        Log.i(TAG, "---- TREE display=$displayId ----")
        val wins = windowsOnAllDisplays.get(displayId)
        if (wins == null) { Log.w(TAG, "该显示器没有窗口"); return }
        wins.forEach { w ->
            val root = w.root ?: return@forEach
            Log.i(TAG, "  window pkg=${root.packageName}")
            walk(root, 0) { n, d ->
                if (n.isClickable || !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty()) {
                    Log.i(TAG, "    ${" ".repeat(d)}[${n.className?.toString()?.substringAfterLast('.')}]" +
                            " text=${n.text} desc=${n.contentDescription} clickable=${n.isClickable}")
                }
            }
        }
        Log.i(TAG, "---- TREE end ----")
    }

    /** H2:按文本找节点并 performAction(CLICK),记录焦点变化 */
    private fun clickByText(displayId: Int, text: String) {
        Log.i(TAG, "---- H2 click display=$displayId text='$text' ----")
        val before = focusedDisplay()
        Log.i(TAG, "  点击前 焦点显示器 = $before")

        val wins = windowsOnAllDisplays.get(displayId)
        if (wins == null) { Log.w(TAG, "  该显示器没有窗口"); return }

        var target: AccessibilityNodeInfo? = null
        outer@ for (w in wins) {
            val root = w.root ?: continue
            walk(root, 0) { n, _ ->
                if (target == null &&
                    (n.text?.toString() == text || n.contentDescription?.toString() == text)) {
                    target = n
                }
            }
            if (target != null) break@outer
        }

        if (target == null) { Log.w(TAG, "  找不到节点 '$text'"); return }

        var clickable: AccessibilityNodeInfo? = target
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val node = clickable ?: target!!

        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "  performAction(CLICK) 返回 = $ok  (节点 ${node.className})")

        Thread.sleep(600)
        val after = focusedDisplay()
        Log.i(TAG, "  点击后 焦点显示器 = $after")
        Log.i(TAG, "  ==> 焦点${if (before == after) "未被抢走 ✅" else "被抢走了 ❌ ($before -> $after)"}")
        Log.i(TAG, "---- H2 end ----")
    }

    /** 用 isActive 推断当前活跃(焦点)显示器 */
    private fun focusedDisplay(): Int {
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.isActive }) return all.keyAt(i)
        }
        return -1
    }

    private fun walk(n: AccessibilityNodeInfo?, depth: Int, f: (AccessibilityNodeInfo, Int) -> Unit) {
        if (n == null || depth > 40) return
        f(n, depth)
        for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1, f)
    }

    companion object {
        const val ACT_DUMP  = "ai.whalephone.probe.DUMP"
        const val ACT_CLICK = "ai.whalephone.probe.CLICK"
        const val ACT_TREE  = "ai.whalephone.probe.TREE"
    }
}

package ai.whalephone.probe

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 操作层。三条硬规则,都是为了「不打扰用户」:
 *
 *  1. 只用 performAction 操作节点,绝不用 dispatchGesture —— 后者会把手势画在真实屏幕上。
 *  2. 文字用 ACTION_SET_TEXT 直接写进节点,绝不走输入法 —— 实测 mDisplayIdToShowIme 恒为 0,
 *     虚拟屏的键盘会弹到用户脸上。
 *  3. 绝不用 performGlobalAction(返回/主页/最近任务)—— 见下方说明。
 */
object Actions {

    private const val TAG = "WPProbe"

    /**
     * 陷阱:AccessibilityService 的全局动作没有显示器维度。
     *
     * performGlobalAction(GLOBAL_ACTION_BACK) 打到的是**当前全局焦点所在的显示器**,
     * 而用户正在用手机时那就是主屏。agent 想在虚拟屏上返回,结果会让用户的 App 后退一页。
     * 这是最隐蔽的一类「抢资源」—— 它不抢焦点、不抢键盘,直接改用户的导航状态。
     *
     * 所以带显示器维度的按键必须走 shell 通道(`input -d <id> keyevent`),
     * 由 ADB(阶段一)或 Shizuku(阶段二)提供。
     */
    fun globalActionIsUnsafe(): Nothing =
        error("performGlobalAction 没有 displayId 维度,会作用到用户那块屏,禁止使用")

    fun click(el: Perception.Element): Boolean {
        val target = el.node.findClickableSelfOrAncestor() ?: el.node
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "click [${el.index}] \"${el.text ?: el.desc}\" -> $ok")
        return ok
    }

    fun longClick(el: Perception.Element): Boolean =
        el.node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    /**
     * 直接写入文本,不经过输入法。
     *
     * ACTION_SET_TEXT 会**返回 true 但什么都没写**:很多 App 的搜索框是自定义控件,
     * 只是没拒绝这个动作而已(淘宝就是)。所以写完必须回读校验,不能信返回值。
     * 校验不过就换粘贴 —— 粘贴走的是 App 自己的文本插入逻辑,自定义控件通常认。
     */
    fun setText(el: Perception.Element, text: String): Boolean {
        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        el.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        el.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (verify(el, text)) { Log.i(TAG, "setText [${el.index}] 直写成功"); return true }

        Log.i(TAG, "setText [${el.index}] 直写没生效,改用粘贴")
        return false   // 粘贴需要剪贴板和 shell,由 Hands 那层接手
    }

    /** 粘贴。调用方负责用 ClipboardGuard 把剪贴板还原回去。 */
    fun paste(ctx: Context, el: Perception.Element, text: String): Boolean {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("wp", text))
        el.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        el.node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        return verify(el, text)
    }

    /** 回读校验。节点是快照时抓的,得刷新一次才能看到新值。 */
    fun verify(el: Perception.Element, expect: String): Boolean {
        Thread.sleep(350)
        el.node.refresh()
        return el.node.text?.toString()?.contains(expect) == true
    }

    fun scroll(el: Perception.Element, forward: Boolean): Boolean {
        if (!el.scrollable) return false
        val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return el.node.performAction(a)
    }

    private fun AccessibilityNodeInfo.findClickableSelfOrAncestor(): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = this
        var hops = 0
        while (n != null && !n.isClickable && hops < 12) { n = n.parent; hops++ }
        return n
    }
}

/**
 * 剪贴板是全局单例 —— agent 复制任何东西都会覆盖用户正在用的剪贴板内容。
 *
 * 这是实测清单里最容易被忽略的一类冲突:它不抢焦点、不抢键盘、屏幕上毫无痕迹,
 * 但用户复制的那段文字会凭空消失。用 [around] 把 agent 的剪贴板操作包起来,
 * 用完立刻还原。
 */
class ClipboardGuard(private val ctx: Context) {

    private val cm get() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun <T> around(block: () -> T): T {
        val saved: ClipData? = runCatching { cm.primaryClip }.getOrNull()
        return try {
            block()
        } finally {
            runCatching {
                if (saved != null) cm.setPrimaryClip(saved) else cm.clearPrimaryClip()
            }.onFailure { Log.w("WPProbe", "剪贴板还原失败: ${it.message}") }
        }
    }
}

/** 便捷:从 service 拿某块显示器的快照 */
fun AccessibilityService.snapshotOf(displayId: Int): Perception.Snapshot =
    Perception.snapshot(displayId, windowsOnAllDisplays.get(displayId))

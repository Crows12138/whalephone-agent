package ai.whalephone.probe

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log

/**
 * agent 在副屏上的一双手。所有对外的操作都必须从这里走,因为
 * 「不打扰用户」这件事不是一句原则,而是每个动作各自的实现细节:
 *
 *   点击   走 performAction(ACTION_CLICK),不走 dispatchGesture ——
 *          后者是往真实触摸层里画手势,只能落在用户那块屏上。
 *   输入   走 ACTION_SET_TEXT,不走输入法。整机只有一个 IME,
 *          实测 mDisplayIdToShowIme 恒为 0,agent 一调输入法就是抢用户的键盘。
 *   按键   走 shell 的 `input -d <显示器>`,不走 performGlobalAction ——
 *          全局动作没有显示器维度,打到的是全局焦点所在的屏,也就是用户的屏。
 *   剪贴板 全机唯一,写之前存、写完还原。
 */
class Hands(
    private val svc: AccessibilityService,
    val displayId: Int,
    ctx: Context,
) {
    private val ctxRef = ctx
    private val clipboard = ClipboardGuard(ctx)
    private var last: Perception.Snapshot? = null

    fun snapshot(): Perception.Snapshot = svc.snapshotOf(displayId).also { last = it }

    private fun el(i: Int) = last?.byIndex(i)

    fun click(i: Int): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        return if (Actions.click(e)) "已点击 [$i] ${e.label()}" else "点击 [$i] 失败"
    }

    fun longClick(i: Int): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        return if (Actions.longClick(e)) "已长按 [$i]" else "长按 [$i] 失败"
    }

    /**
     * 三级降级。每一级都回读校验,不信返回值:
     *   1. ACTION_SET_TEXT   —— 最干净,标准 EditText 都吃这一套
     *   2. 剪贴板 + ACTION_PASTE —— 自定义控件通常认粘贴,剪贴板用完立刻还原
     *   3. `input -d <屏> text` —— 走输入事件通道,但带显示器维度,落不到用户屏上;
     *      需要目标控件在这块屏上有焦点,所以放最后
     */
    fun setText(i: Int, text: String): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"

        if (Actions.setText(e, text)) return "已在 [$i] 填入「$text」"

        val pasted = clipboard.around { Actions.paste(ctxRef, e, text) }
        if (pasted) return "已在 [$i] 填入「$text」(用粘贴)"

        val esc = text.replace("%", "%%").replace(" ", "%s")
        Privileged.exec("input -d $displayId text $esc")
        if (Actions.verify(e, text)) return "已在 [$i] 填入「$text」(用按键注入)"

        return "[$i] 三种写法都没写进去,这个控件可能不接受外部输入"
    }

    fun scroll(i: Int, forward: Boolean): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        return if (Actions.scroll(e, forward)) "已滚动 [$i]" else "[$i] 滚不动了(到头了)"
    }

    /** 带显示器维度的按键。这是唯一必须借 shell 的动作。 */
    fun key(code: Int): String {
        val out = Privileged.exec("input -d $displayId keyevent $code")
        return if (out.isBlank() || out == "NO_BRIDGE") {
            if (out == "NO_BRIDGE") "按键失败:特权桥没连上" else "已按键 $code"
        } else "按键 $code: ${out.trim()}"
    }

    fun back() = key(4)

    /**
     * 把 App 启到副屏。--activity-multiple-task 是关键:
     * 如果目标 App 已经在用户那块屏上开着,不加这个参数系统会把用户正在用的那个 task
     * 整个搬到副屏来 —— 用户会看着自己的微信凭空消失。实测踩过。
     */
    fun launch(pkg: String): String {
        if (Conflict.userIsUsing(pkg)) {
            return "用户此刻正在前台用 $pkg,为避免把他的界面搬走,这一步先跳过;" +
                "换个不冲突的做法,或者 ask 请示"
        }
        val out = Privileged.exec(
            "am start --display $displayId --activity-multiple-task --activity-new-task " +
                "\$(cmd package resolve-activity --brief $pkg | tail -1)"
        )
        Log.i(TAG, "launch $pkg -> ${out.trim()}")
        return if (out.contains("Error") || out.contains("Exception")) "启动 $pkg 失败: ${out.trim()}"
        else "已在副屏打开 $pkg"
    }

    companion object { private const val TAG = "WPHands" }
}

/** 资源竞争的处理集中在这里,每一条都对应一次真机上量到的冲突 */
object Conflict {

    /** 用户当前前台 App。用来避免把他正在用的 task 搬到副屏。 */
    fun userForegroundPackage(): String? {
        val out = Privileged.exec("dumpsys activity activities | grep -m1 topResumedActivity")
        return Regex("""\s([A-Za-z0-9_.]+)/""").find(out)?.groupValues?.get(1)
    }

    fun userIsUsing(pkg: String): Boolean = userForegroundPackage() == pkg

    /** 副屏是否真的拿到了独立焦点。没拿到就退回「尽量少动焦点」的保守策略。 */
    fun ownFocusEffective(display: AgentDisplay?): Boolean =
        display != null && (display.flags and ShellBridge.OWN_FOCUS) != 0
}

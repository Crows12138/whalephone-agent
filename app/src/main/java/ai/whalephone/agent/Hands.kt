package ai.whalephone.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
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
    val svc: AccessibilityService,
    val displayId: Int,
    ctx: Context,
) {
    private val ctxRef = ctx
    private val clipboard = ClipboardGuard(ctx)
    private var last: Perception.Snapshot? = null

    fun snapshot(): Perception.Snapshot = svc.snapshotOf(displayId).also { last = it }

    private fun el(i: Int) = last?.byIndex(i)

    /**
     * 两级降级,和 setText 一样不信返回值 —— 只信界面动没动:
     *   1. ACTION_CLICK   —— 标准控件都吃这套,而且不产生任何真实触摸事件
     *   2. 往这块屏注入一次真实点击 —— 只挂了 onTouchListener 的自定义控件只认这个。
     *      带 `-d 屏号`,事件进的是 agent 这块屏的 InputDispatcher,到不了用户那块屏。
     *
     * 没有第二级的时候,模型会对着一个「点了返回 true 但什么也不会发生」的按钮
     * 一直点到被判卡死 —— 它拿不到任何失败信号。实测淘宝商品详情页的店铺入口就是。
     */
    fun click(i: Int): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        val t = SystemClock.uptimeMillis()
        val ok = Actions.click(e)
        Thread.sleep(CLICK_SETTLE_MS)
        val eyes = svc as? EyesAndHands
        if (ok && (eyes == null || eyes.changedSince(displayId, t)))
            return "已点击 [$i] ${e.label()}"

        val b = Rect().also { e.node.getBoundsInScreen(it) }
        if (b.isEmpty) return if (ok) "已点击 [$i] ${e.label()}" else "点击 [$i] 失败"
        Privileged.execArgs("input", "-d", "$displayId", "tap", "${b.centerX()}", "${b.centerY()}")
        Thread.sleep(CLICK_SETTLE_MS)
        return if (eyes == null || eyes.changedSince(displayId, t))
            "已点击 [$i] ${e.label()}(无障碍点击无效,补了一次真实触摸)"
        else "点了 [$i] ${e.label()},但界面没有任何反应 —— 这个元素点不动,换一个"
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

        // 走 argv 不走 sh:text 来自模型,而模型的上下文里有无障碍树读来的、
        // 攻击者可控的文字。拼进命令行就等于把 uid 2000 的 shell 交出去。
        Privileged.execArgs("input", "-d", "$displayId", "text", text)
        if (Actions.verify(e, text)) return "已在 [$i] 填入「$text」(用按键注入)"

        return "[$i] 三种写法都没写进去,这个控件可能不接受外部输入"
    }

    fun scroll(i: Int, forward: Boolean): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        return if (Actions.scroll(e, forward)) "已滚动 [$i]" else "[$i] 滚不动了(到头了)"
    }

    /** 带显示器维度的按键。这是唯一必须借 shell 的动作。 */
    fun key(code: Int): String {
        val out = Privileged.execArgs("input", "-d", "$displayId", "keyevent", "$code")
        return if (out.isBlank() || out == "NO_BRIDGE") {
            if (out == "NO_BRIDGE") "按键失败:特权桥没连上" else "已按键 $code"
        } else "按键 $code: ${out.trim()}"
    }

    fun back() = key(4)

    /**
     * 回到副屏自己的桌面。长时任务的下一轮开始时,副屏上往往还停在上一轮的界面,
     * 模型需要一个「重来」的手段,否则只能连按返回,遇到不响应返回的页面就卡死。
     *
     * 不用 performGlobalAction(HOME) —— 它没有显示器维度,会把**用户**送回桌面。
     * 也不用 `input -d <屏> keyevent 3`:实测这条对副屏无效(计算器纹丝不动),
     * HOME 键由窗口策略层处理,那一层认的是全局焦点屏而不是事件带的屏号。
     * 有效的是显式启动这块屏自己的桌面 Activity。
     *
     * 注意返回键不一样:`input -d <屏> keyevent 4` 是按屏走的,实测有效。
     */
    fun home(): String {
        val out = Privileged.execArgs(
            "am", "start", "--display", "$displayId",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"
        )
        return if (out.contains("Error") || out == "NO_BRIDGE") "回桌面失败: ${out.trim()}"
        else "已回到副屏桌面"
    }

    /**
     * 把 App 启到副屏。--activity-multiple-task 是关键:
     * 如果目标 App 已经在用户那块屏上开着,不加这个参数系统会把用户正在用的那个 task
     * 整个搬到副屏来 —— 用户会看着自己的微信凭空消失。实测踩过。
     */
    fun launch(pkg: String): String {
        if (Conflict.userIsUsing(svc, pkg)) {
            return "用户此刻正在前台用 $pkg,为避免把他的界面搬走,这一步先跳过;" +
                "换个不冲突的做法,或者 ask 请示"
        }
        if (!PKG.matches(pkg)) return "「$pkg」不是合法的包名"

        // 原来这里是 `am start ... $(cmd package resolve-activity --brief $pkg | tail -1)`,
        // 一条 sh 命令里既有命令替换又有模型给的 pkg。拆成两步:先解析组件名,
        // 在 Kotlin 里取最后一行,再按 argv 启动。两步都不经过 sh。
        val resolved = Privileged
            .execArgs("cmd", "package", "resolve-activity", "--brief", pkg)
            .trim().lines().lastOrNull()?.trim().orEmpty()
        if (!COMPONENT.matches(resolved)) return "解析不出 $pkg 的启动组件: $resolved"

        // --activity-multiple-task 是关键;NEW_TASK 由 am 自己加,没有 --activity-new-task 这个参数
        val out = Privileged.execArgs(
            "am", "start", "--display", "$displayId", "--activity-multiple-task", "-n", resolved
        )
        Log.i(TAG, "launch $pkg -> ${out.trim()}")
        // 往副屏启 App 会抢焦点并收起用户的输入法,和造屏一样,立刻还回去
        Privileged.handBackFocus()
        if (out.contains("Error") || out.contains("Exception")) return "启动 $pkg 失败: ${out.trim()}"

        // am start 是异步的:命令返回成功只表示 Intent 递出去了,不表示界面起来了。
        // 淘宝这类 App 冷启动要好几秒,而循环下一帧快照在几百毫秒后就拍 —— 模型会
        // 看到一块空屏,以为没启成功,于是再启一次,连着几次就被判定卡死。
        // 所以这里同步等到目标 App 真的出现在这块屏上为止。
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700)
            val s = snapshot()
            if (s.packages.any { it == pkg }) {
                return "已在副屏打开 $pkg,当前 ${s.elements.size} 个可交互元素"
            }
        }
        return "启动 $pkg 后等了 ${LAUNCH_TIMEOUT_MS / 1000} 秒界面还没出来,可能是启动慢或者被系统拦了"
    }

    companion object {
        private const val TAG = "WPHands"
        private const val LAUNCH_TIMEOUT_MS = 25_000L
        /** 点完等界面反应的时间。太短会把「慢」误判成「点不动」而多补一次触摸。 */
        private const val CLICK_SETTLE_MS = 500L

        /**
         * 纵深防御。argv 已经堵死了 shell 注入,这两条再挡住「拼出一个合法但不是
         * 你以为的那个命令」—— 比如在包名位置塞一个 --user 之类的选项。
         */
        private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*([.][A-Za-z0-9_]+)+")
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.\$]+")

        /**
         * 纵深防御。argv 已经堵死了 shell 注入,这两条再挡住「拼出一个合法但不是
         * 你以为的那个命令」—— 比如包名位置塞一个 `--user 0` 之类的选项。
         */
    }
}

/** 资源竞争的处理集中在这里,每一条都对应一次真机上量到的冲突 */
object Conflict {

    /**
     * 用户此刻在主屏上用的是哪个 App。
     *
     * 不走 `dumpsys activity | grep topResumedActivity`:那是**全局**的 top-resumed,
     * agent 在副屏上活动时它指的就是副屏,拿它判断「用户在用什么」会反过来。
     * 无障碍能按显示器取窗口,直接问 Display 0 上处于活动状态的那个窗口是谁,
     * 既准确又不需要 shell。
     */
    fun userForegroundPackage(svc: AccessibilityService): String? {
        val wins = svc.windowsOnAllDisplays.get(USER_DISPLAY) ?: return null
        return wins.firstOrNull { it.isActive }?.root?.packageName?.toString()
            ?: wins.firstOrNull { it.isFocused }?.root?.packageName?.toString()
    }

    fun userIsUsing(svc: AccessibilityService, pkg: String): Boolean =
        userForegroundPackage(svc) == pkg

    /** 用户那块屏永远是 0 —— 主显示器的 id 由系统固定 */
    const val USER_DISPLAY = 0
}

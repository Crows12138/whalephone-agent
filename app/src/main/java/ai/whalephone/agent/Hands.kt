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
    // 副屏是按物理屏尺寸造的(AgentService 用的就是这份 metrics),所以可以直接拿来当屏幕手势的画布
    private val metrics = ctx.resources.displayMetrics
    private var last: Perception.Snapshot? = null

    fun snapshot(): Perception.Snapshot = svc.snapshotOf(displayId).also { last = it }

    private fun el(i: Int) = last?.byIndex(i)

    /**
     * 一个动作有没有真的生效,唯一可靠的判据是**界面动没动** —— 返回值一律不信。
     *
     * 无障碍动作和注入的按键都可能「成功地什么也没做」:节点收下 ACTION_CLICK 但
     * 只挂了 onTouchListener、往不可滚动的容器上划、对不响应返回的页面按返回。
     * 这类失败是静默的,日志干净、返回值成功,只有屏幕知道。模型拿不到失败信号
     * 就会一直重复,直到被判卡死。
     *
     * 所以凡是可能静默失效的动作,都从这里过一遍,让失败变成显式的。
     */
    private inline fun changed(act: () -> Unit): Boolean {
        val eyes = svc as? EyesAndHands
        val before = runCatching { svc.snapshotOf(displayId).render() }.getOrNull()
        val t = SystemClock.uptimeMillis()
        act()
        Thread.sleep(SETTLE_MS)

        // 两个判据各有各的偏差,所以取并集:
        //   无障碍事件 —— 灵敏,纯视觉的变化(轮播图滚动)也能捕捉到,但会被
        //                 广告轮播、懒加载这类和本次动作无关的活动带出误报;
        //   渲染快照   —— 就是模型眼里的那份视图,和卡死检测同一个判据,
        //                 但页面慢了会在 500ms 内看不出变化。
        // 宁可偶尔漏判「没生效」,也不能误判「没生效」—— 后者会让 click 补第二次
        // 真实触摸,而那一下会把已经生效的操作再做一遍(购物 App 上就是重复下单)。
        val byEvent = eyes?.changedSince(displayId, t) ?: true
        val byRender = before == null || runCatching { svc.snapshotOf(displayId).render() }
            .getOrNull() != before
        if (byEvent != byRender) Log.i(TAG, "变化判据不一致 事件=$byEvent 快照=$byRender")
        return byEvent || byRender
    }

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
        if (changed { Actions.click(e) }) return "已点击 [$i] ${e.label()}"

        val b = Rect().also { e.node.getBoundsInScreen(it) }
        if (b.isEmpty) return "点了 [$i] ${e.label()},界面没反应,而且拿不到它的位置,补不了触摸"
        return if (changed {
                Privileged.execArgs("input", "-d", "$displayId", "tap", "${b.centerX()}", "${b.centerY()}")
            }) "已点击 [$i] ${e.label()}(无障碍点击无效,补了一次真实触摸)"
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

    /**
     * 坐标划动。和 scroll 是两个动作,分工明确:
     *   scroll —— 语义滚动,走 ACTION_SCROLL_*,只对**声明了自己可滚动**的容器有效
     *   swipe  —— 真实划动,轮播图、侧边抽屉、左滑删除这类不实现 ACTION_SCROLL 的交互只认它
     *
     * AndroidWorld 的动作空间里这两者也是分开的,不是冗余。
     * 不给 index 就在整块副屏上划。
     */
    fun swipe(i: Int?, direction: String): String {
        val b = if (i == null) Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
                else el(i)?.let { e -> Rect().also { e.node.getBoundsInScreen(it) } }
                    ?: return "没有序号 $i 这个元素"
        if (b.isEmpty) return "[$i] 拿不到位置,划不了"

        // 只划到中心距边缘的 35%,不贴边 —— 贴边会触发系统的侧滑返回手势
        val cx = b.centerX(); val cy = b.centerY()
        val dx = b.width() * 35 / 100; val dy = b.height() * 35 / 100
        val (ex, ey) = when (direction) {
            "up"    -> cx to cy - dy     // 手指往上划,内容往上走,看到的是下面的东西
            "down"  -> cx to cy + dy
            "left"  -> cx - dx to cy
            "right" -> cx + dx to cy
            else    -> return "方向只能是 up / down / left / right,收到的是「$direction」"
        }
        val where = if (i == null) "副屏" else "[$i]"
        return if (changed {
                Privileged.execArgs("input", "-d", "$displayId", "swipe", "$cx", "$cy", "$ex", "$ey", "300")
            }) "已在$where 上向 $direction 划了一下"
        else "在$where 上向 $direction 划了,但界面没有任何反应 —— 这里划不动,换个位置或换个动作"
    }

    /**
     * 回车 / 搜索键。输入框填完直接按它提交,比在无障碍树里找「搜索」按钮可靠 ——
     * 那个按钮不一定在树里,也不一定叫「搜索」。AndroidWorld 把 KEYBOARD_ENTER
     * 单列成一个动作,理由是有些控件光靠点是控制不了的。
     */
    fun enter() = key(66)

    fun doubleTap(i: Int): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        val b = Rect().also { e.node.getBoundsInScreen(it) }
        if (b.isEmpty) return "[$i] 拿不到位置"
        return if (changed {
                repeat(2) {
                    Privileged.execArgs("input", "-d", "$displayId", "tap", "${b.centerX()}", "${b.centerY()}")
                }
            }) "已双击 [$i] ${e.label()}"
        else "双击 [$i] 后界面没有任何反应"
    }

    fun scroll(i: Int, forward: Boolean): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        var accepted = false
        if (changed { accepted = Actions.scroll(e, forward) }) return "已滚动 [$i]"
        return if (accepted) "[$i] 收下了滚动但界面没动 —— 到头了,或者这个容器只认真实划动,试试 swipe"
        else "[$i] 滚不动(它没有声明自己可滚动)—— 试试 swipe"
    }

    /** 带显示器维度的按键。这是唯一必须借 shell 的动作。 */
    fun key(code: Int): String {
        var out = ""
        val moved = changed { out = Privileged.execArgs("input", "-d", "$displayId", "keyevent", "$code") }
        return when {
            out == "NO_BRIDGE" -> "按键失败:特权桥没连上"
            out.isNotBlank()   -> "按键 $code: ${out.trim()}"
            moved              -> "已按键 $code"
            else               -> "按了 $code,但界面没有任何反应"
        }
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
        /** 动作之后等界面反应的时间。太短会把「慢」误判成「没生效」。 */
        private const val SETTLE_MS = 500L

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

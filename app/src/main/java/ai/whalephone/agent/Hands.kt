package ai.whalephone.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
    /**
     * 这块屏当前那一帧的 JPEG,无障碍树读不出东西时给视觉模型看。
     *
     * 用函数传进来而不是让 Hands 自己去拿:那块屏是 AgentService 造的、也归它销毁,
     * Hands 不该知道它的存在。开发夹具那条路(EyesAndHands 自己建的 Hands)没有
     * 自己的屏,传 null,看图那条路在那边就是不可用 —— 不可用比拿到一块别人的屏好。
     */
    private val frame: (() -> ByteArray?)? = null,
) {
    private val ctxRef = ctx
    private val clipboard = ClipboardGuard(ctx)
    // 副屏是按物理屏尺寸造的(AgentService 用的就是这份 metrics),所以可以直接拿来当屏幕手势的画布
    private val metrics = ctx.resources.displayMetrics
    /** 最近一帧快照。动作实现要拿它判断「这个动作在当前界面上有没有意义」。 */
    var last: Perception.Snapshot? = null
        private set

    fun snapshot(): Perception.Snapshot {
        val s = svc.snapshotOf(displayId)
        // 无障碍一个包都报不出来的时候,不能直接告诉模型「这块屏是空的」——
        // 那可能只是节点树读不到。多花一趟 shell 问一下窗口管理器,它说了算。
        // 只在读不到的时候问,正常路径没有额外开销。
        val fixed = if (s.packages.isEmpty()) s.copy(wmSays = topOnDisplay()) else s
        last = fixed
        return fixed
    }

    /** 窗口管理器认为这块屏上最前面的是谁。读不到返回 null。 */
    private fun topOnDisplay(): String? {
        val out = Privileged.exec(
            "dumpsys activity activities | sed -n '/Display #$displayId /,/^Display #/p'" +
                " | grep -m1 topResumedActivity"
        ).trim()
        if (out.startsWith("NO_BRIDGE") || out.startsWith("EXEC_FAIL")) return null
        val cmp = Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+").find(out)?.groupValues?.get(1)
        // 副屏上常驻的 DeX 桌面不算「有 App」,它一直都在
        return cmp?.takeIf { !it.contains("launcher", true) && !it.contains("honeyspace", true) }
    }

    private fun el(i: Int) = last?.byIndex(i)

    /**
     * 往副屏注入一次真实输入。
     *
     * 这里曾经在注入后补一句「把焦点还给机主」。那是错的,两头都错:焦点推得回去、
     * 已经收起的软键盘推不回来;而那句补救本身是一个投递到主屏的按键事件,主屏此刻
     * 恰恰没有焦点窗口,于是超时成「应用未响应」—— 补救动作制造了比它要修的问题
     * 更严重的问题。真正的解法在 Conflict.yieldWhileOwnerTypes:事前避让,不是事后补。
     *
     * 注意无障碍动作(performAction)不走这里 —— 它根本不经过输入系统,不碰焦点。
     */
    private fun inject(vararg argv: String): String = Privileged.execArgs(*argv)

    /** 这一帧的 base64 JPEG,拿不到返回 null */
    fun frameB64(): String? = frame?.invoke()
        ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

    /**
     * 按归一化坐标点一下。模型看图报的是 0-1000 的相对位置,不是像素 ——
     * 截图缩过、不同机器分辨率也不同,让模型算像素等于把这些都塞给它。
     *
     * 这条路上没有节点可用,所以只能真实触摸;也正因为没有节点,点完必须自己
     * 验一下界面动没动 —— 树那条路还能靠 performAction 的返回值兜个底,这条路
     * 唯一的反馈就是屏幕。
     */
    fun tapAt(nx: Int, ny: Int): String {
        if (nx !in 0..1000 || ny !in 0..1000) return "坐标要在 0 到 1000 之间,你给的是 ($nx, $ny)"
        val x = nx * metrics.widthPixels / 1000
        val y = ny * metrics.heightPixels / 1000
        val moved = changed { inject("input", "-d", "$displayId", "tap", "$x", "$y") }
        // 点完清掉序号那条路的记忆:这一帧的元素身份是按位置记的,而看图这一步
        // 很可能已经把界面换掉了,留着会让下一次误判成「这个元素点不动」
        dead.clear(); pending = null; lastId = null
        return if (moved) "已在 ($nx, $ny) 点了一下"
        else "在 ($nx, $ny) 点了一下,界面没有反应 —— 那个位置多半没有能点的东西,换一处"
    }

    /** 机主在打字就先等着。每个动作前都过一遍,返回让了多少毫秒。 */
    fun yieldToOwner(): Long = Conflict.yieldWhileOwnerTypes(svc)

    /**
     * 把焦点还给机主那块屏 —— 只在 agent 接下来必定空闲时调用。
     * 关掉:`FOCUS_RETURN=0`。留这个口子是因为这条改动带风险(见 Privileged 里的说明),
     * 万一在某台机器上又量到 ANR,不用改代码就能退回去。
     */
    fun returnFocusToOwner() {
        if (Config.get(ctxRef, "FOCUS_RETURN", "1") == "0") return
        runCatching { Privileged.handBackFocusIfLost() }
            .onSuccess { if (it.startsWith("主屏原本")) Log.i(TAG, it) }
    }

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
        // 基线直接用这一步开头已经拍过的那份快照。
        // 原来在这里现拍一次 —— 那次遍历会让 last 里的节点句柄失效,
        // 于是紧接着的 performAction 落空:动作没生效,判据也就说「没变化」,
        // 一个自己造出来又自己确认的假象。实测三星计算器每个数字键都栽在这上面。
        val before = last?.render()
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

    /** 这一步点的是谁。等 Agent 算出界面动没动,再决定要不要把它记成「点不动」 */
    private var pending: String? = null

    /**
     * 点过、而且那一下**界面什么都没发生**的元素。界面一变就整体清空。
     *
     * 序号每一步都会重新编号,不能当身份用,所以键取「标签 + 屏幕位置」。
     */
    private val dead = mutableSetOf<String>()

    /**
     * Agent 每一步算出的「上一步界面动没动」回流到这里。
     *
     * 这条事实 Agent 本来就在算(它要靠这个判卡死、也要写回给模型看),
     * 只是一直没告诉 Hands。Hands 缺的恰恰就是它 —— 见下面 click 的注释。
     */
    fun noteIdle(idled: Boolean) {
        if (idled) pending?.let { dead += it } else dead.clear()
        lastId = pending
        pending = null
    }

    /** 紧邻的上一次点击。[dead] 在动画页面上会被频繁清空,这条兜住那种情况 */
    private var lastId: String? = null

    /** 元素的身份。序号不行(每步重编),标签也不够(一屏可能好几个同名按钮) */
    private fun idOf(e: Perception.Element, b: Rect) = "${e.label()}@${b.flattenToString()}"

    /**
     * 两级:先无障碍点击,不行才补一次真实触摸。
     *
     * 难点是**什么时候算「不行」**。`performAction` 的返回值不能信:淘宝详情页的
     * 按钮只挂 onTouchListener,节点收下动作、返回 true,什么也不会发生。而反过来,
     * 只要判错一次就会把一个**已经生效**的操作再做一遍 —— 实测三星计算器按 128
     * 补出来是 122,换成购物 App 就是重复下单。所以这个判据宁可漏,不能错。
     *
     * 判据改过两次:
     *
     * 一版用「界面动没动」直接判,漏判(界面其实变了却没测到)就补第二次触摸,
     * 不安全。二版改成看模型的行为 —— **它又点了同一个元素**,说明上一次确实
     * 没起作用,信号来自真实后果而不是我的检测。但二版只记了**紧邻的**上一次点击,
     * 而提示词恰恰教模型「点不动就换个元素、换条路」:真机上模型在两次重试
     * 「加入购物车」之间插了一次探索性点击,记录就被冲掉,兜底永远不触发 ——
     * 提示词教的行为把兜底的触发条件正好绕开了。
     *
     * 现版取两者的并集:「**点过之后界面没动**」([dead],跨步数记着)**或者**
     * 「紧邻的上一次点的就是它」([lastId],二版那条规则原样留着)。
     *
     * 两条都要,因为各自都有盲区:[dead] 靠「界面动没动」判定,而淘宝详情页有
     * 轮播和懒加载,界面每一步都在变,集合每步都被清空 —— 实测就是这样,它一个
     * 元素都记不住;[lastId] 则会被模型「换个元素试试」的行为冲掉。
     *
     * 并且认定「点不动」之后**不再白点一次无障碍**,直接走真实触摸。原来那版在
     * again 成立时是无障碍点击 + 真实触摸两下都做,那正是「重复下单」的来源;
     * 现在任何一步都只落一次动作。
     */
    fun click(i: Int): String {
        val e = el(i) ?: return "没有序号 $i 这个元素"
        val b = Rect().also { e.node.getBoundsInScreen(it) }
        val id = idOf(e, b)
        val known = id in dead || id == lastId
        pending = id

        if (!known) {
            val ok = Actions.click(e)
            Thread.sleep(SETTLE_MS)
            if (ok) return "已点击 [$i] ${e.label()}"
        }

        if (b.isEmpty) return "点不动 [$i],也拿不到它的位置"
        val moved = changed {
            inject("input", "-d", "$displayId", "tap", "${b.centerX()}", "${b.centerY()}")
        }
        return if (moved) "已点击 [$i] ${e.label()}(无障碍点击对它无效,改用真实触摸)"
        else "点了 [$i] ${e.label()},界面仍然没有反应 —— 这个元素点不动,换一个"
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
        inject("input", "-d", "$displayId", "text", text)
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
                inject("input", "-d", "$displayId", "swipe", "$cx", "$cy", "$ex", "$ey", "300")
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
                    inject("input", "-d", "$displayId", "tap", "${b.centerX()}", "${b.centerY()}")
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
        val moved = changed { out = inject("input", "-d", "$displayId", "keyevent", "$code") }
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
    /**
     * 把「用户说的名字」变成包名。
     *
     * 模型不可能知道这台机器上装的是哪个计算器 —— 实测它依次猜了
     * com.google.android.calculator 和 com.android.calculator2,而三星上叫
     * com.sec.android.app.popupcalculator。让模型猜包名本来就是设计错误:
     * 这个信息在设备上是现成的,查一下就有。AndroidWorld 的 OPEN_APP 收的
     * 也是 app 名字而不是包名,同一个道理。
     */
    private fun resolvePackage(q: String): Pair<String?, List<String>> {
        val pm = ctxRef.packageManager
        if (runCatching { pm.getPackageInfo(q, 0) }.isSuccess) return q to emptyList()

        val apps = runCatching {
            pm.queryIntentActivities(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
            ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        }.getOrDefault(emptyList())

        val k = q.lowercase()
        val hit = apps.firstOrNull { it.first.equals(q, true) }
            ?: apps.firstOrNull { it.first.lowercase().contains(k) || k.contains(it.first.lowercase()) }
            ?: apps.firstOrNull { it.second.lowercase().contains(k) }
        // 没命中就把名字相近的报回去,让模型自己挑,而不是让它继续瞎猜
        val near = apps.filter { a -> k.split(" ", "-", ".").any { it.length > 1 && a.first.lowercase().contains(it) } }
            .take(8).map { "${it.first}(${it.second})" }
        return hit?.second to near
    }

    fun launch(nameOrPkg: String): String {
        val (resolved, near) = resolvePackage(nameOrPkg)
        val pkg = resolved ?: return buildString {
            append("这台设备上找不到「$nameOrPkg」")
            if (near.isNotEmpty()) append(";名字相近的有:${near.joinToString("、")}")
            else append(",换个说法试试(可以直接写 app 的显示名,比如「计算器」)")
        }
        return launchPkg(pkg)
    }

    private fun launchPkg(pkg: String): String {
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
        if (out.contains("Error") || out.contains("Exception")) return "启动 $pkg 失败: ${out.trim()}"

        // am start 是异步的:命令返回成功只表示 Intent 递出去了,不表示界面起来了。
        // 淘宝这类 App 冷启动要好几秒,而循环下一帧快照在几百毫秒后就拍 —— 模型会
        // 看到一块空屏,以为没启成功,于是再启一次,连着几次就被判定卡死。
        // 所以这里同步等到目标 App 真的出现在这块屏上为止。
        //
        // 这段冷启动期以前是「高频把焦点钉回主屏」的地方。那条线程已经删掉:它每
        // 250 毫秒往主屏投一个按键事件,而主屏那时没有焦点窗口,等于每 250 毫秒
        // 给机主的前台 App 递一颗 ANR 定时炸弹。A/B 实测 ANR 1 -> 0。
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700)
            val s = snapshot()
            if (s.packages.any { it == pkg }) {
                return "已在副屏打开 $pkg,当前 ${s.elements.size} 个可交互元素"
            }
            // 窗口管理器说它已经在这块屏上了,只是无障碍这一刻读不到它的节点树。
            // 这也算启动成功 —— 再等下去只会等满超时,然后模型又去启一次,
            // 而每启一次都用 --activity-multiple-task 多堆一个 task,越堆越乱。
            if (s.wmSays == pkg) {
                return "$pkg 已经在副屏上了(窗口管理器确认),但这一刻读不到它的界面。" +
                    "别再启动它,先 wait 再看一眼"
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

    /**
     * 机主此刻是不是正在打字。两半都要成立:
     *
     *   状态 —— 他那块屏上有一个为他而开的输入法窗口([imeUpForOwner])
     *   活动 —— 最近确实有键落下([ownerStillAtIt])
     *
     * 少了任何一半,判据都会在一头出错;为什么,分别写在那两个函数上。
     */
    fun ownerTyping(svc: AccessibilityService): Boolean {
        val a = SystemClock.uptimeMillis()
        val up = imeUpForOwner(svc)
        val b = SystemClock.uptimeMillis()
        if (b - a > tIme) tIme = b - a
        // 键盘落下就是这一次输入结束了 —— 没上屏的东西已经没了或者已经上屏了,
        // 「他手里可能攥着没上屏的字」这个前提随之失效,会话状态清零。
        if (!up) { ownerKeyedThisSession = false; fpSeenOnce = false; return false }
        val r = ownerStillAtIt(svc)
        val c = SystemClock.uptimeMillis()
        if (c - b > tFp) tFp = c - b
        return r
    }

    // 判据两半各自最慢的一次(毫秒)。让路时长比阈值多出来的部分,要能指到具体是谁慢。
    private var tIme = 0L
    private var tFp = 0L

    /**
     * 机主还在敲吗 —— 让路判据的**活动**那一半。
     *
     * 「主屏上有输入法窗口」是状态,「机主在打字」是活动。只看状态,判据在两头都是错的:
     * 机主把键盘留在屏上走开,agent 会一直认为他在打字,每轮让满上限、一步都做不了;
     * 而机主连续打字超过那个上限时,等满之后照样会动手打断他 —— 上限这个补丁本身
     * 就是判据分不出活动与状态的证据。补上活动维度,两头才都对。
     *
     * 活动信号是**直接读他正在编辑的那个输入框**:内容或光标位置变了 = 有键落下。
     *
     * 走过一次弯路:先用的是无障碍事件流里的 `TYPE_VIEW_TEXT_CHANGED`。真机上量出来,
     * 往 Edge 地址栏打字**一条文本变化事件都不发**,只有 `TYPE_WINDOW_CONTENT_CHANGED`
     * (那条不能用 —— 主屏上时钟、通知、动画都会发,活动判定会永远为真)。
     * 事件发不发是 App 自己的选择,读节点不是:节点上的文本和选区是无障碍树里的状态,
     * 拿不到就是真的拿不到,不会「有内容但不通知」。
     *
     * 不用 `dumpsys power` 的 `mLastUserActivityTime`,也不用 `dumpsys input` 的事件
     * 时间戳:agent 自己会往主屏注入 `keyevent 0` 把焦点推回去,那些注入会刷新这两个
     * 计数器,判据会一直以为机主在动。读输入框没有这个自污染 —— agent 的动作全在副屏。
     *
     * **读不到输入框时,一律按「在打字」算。** 这条不能省:读不到时如果按「没人打字」
     * 算,让路会彻底失效,而日志一片正常、指标一片漂亮。这个项目已经在静默失效上
     * 栽过两次(数「字有没有丢」、TYPING 探针自己重算判据),宁可退回只看状态的旧行为。
     *
     * 「多久算停手」见 idleMs,分两档 —— 按没按过键,风险不一样。这里栽过一次:
     * 机主打拼音、字还没上屏,判据当他停手,副屏一造出来键盘被收、没上屏的拼音
     * 被当场提交(真机复现 3/3)。当时真正的错不在阈值,在检测器一是个**频率**判据
     * (5 秒 3 条),而打一个字母只发约 2 条事件 —— 慢慢打就永远凑不够。
     */
    private fun ownerStillAtIt(svc: AccessibilityService): Boolean {
        val now = SystemClock.uptimeMillis()
        var active = false

        // 检测器一:输入法自己的窗口在动。
        //
        // 这条最通用 —— 输入法是另一个进程,前台 App 挡不挡无障碍都影响不到它,
        // 而每敲一下候选栏、按键预览都在变。微信这类把内容挡掉的 App 只剩这一条能用,
        // 而那恰恰是机主最常打字的地方,所以它不是兜底,是主力,每次都查。
        //
        // 看的是「最后一次动的时刻」,不是「五秒内动了几次」。原来用频率(5 秒 3 条),
        // 真机上量到打一个字母只发约 2 条 —— 打得慢一点就永远凑不够,判据当场判他停手。
        // 频率阈值同时还在偷偷承担「打得快不快」的判断,而那个判断和「他停没停手」无关。
        val imeAt = EyesAndHands.instance?.ownerImeLastAt ?: 0L
        if (imeAt > imeSeenAt) {
            imeSeenAt = imeAt; imeArmed = true; ownerKeyedThisSession = true
            active = true; nIme++
        }

        // 检测器二:机主的输入框内容或光标变了。比检测器一精确,但要 App 肯给。
        val fp = ownerEditorFingerprint(svc)
        if (fp == null) {
            if (fpReadable) fpChangedAt = now   // 刚从「读得到」切成「读不到」,重新计时
            fpReadable = false; fpLast = null; nBlind++
        } else {
            fpReadable = true
            if (fp != fpLast) {
                fpLast = fp; active = true; nFp++
                // 第一次指纹变化是「刚看到这个输入框」,不是「他按了键」,不能算。
                if (fpSeenOnce) ownerKeyedThisSession = true else fpSeenOnce = true
            }
        }

        if (active) { fpChangedAt = now; idleLogged = false; return true }

        // 两条都没证明过自己在这台机器上能用,就退回原来的行为:一律按「在打字」算。
        if (!signalArmed()) return true
        if (fpChangedAt == 0L) { fpChangedAt = now; return true }

        // 检测器三:机主那块屏上的**窗口结构**变过。
        //
        // 补的是「重新点进同一个空输入框」这种:指纹一模一样,人却确实动了。
        // 真机上撞到过 —— 上一轮让路结束时输入框是空的,机主收起键盘又点回去,
        // 判据当场判他「早就停手了」,一秒没让。
        // 键盘弹起、对话框出现、切 App 都会报 TYPE_WINDOWS_CHANGED,由框架发出,
        // 不靠 App 配合。会不会太吵?实测主屏静置 30 秒一条事件都没有。
        val winAt = EyesAndHands.instance?.ownerWindowsChangedAt ?: 0L
        if (winAt > fpChangedAt) {
            fpChangedAt = winAt; idleLogged = false; nWin++; return true
        }
        val idle = now - fpChangedAt
        if (idle < idleMs(svc)) return true
        if (!idleLogged) {
            idleLogged = true
            Log.i("WPConflict", "键盘还开着,但 ${idle / 1000} 秒没人动过" +
                "(这次他" + (if (ownerKeyedThisSession) "按过键,阈值 " else "一个键都没按,阈值 ") +
                "${idleMs(svc) / 1000} 秒)—— 按机主已经停手算")
        }
        return false
    }

    /**
     * 机主此刻在编辑的那个输入框的「内容指纹」:文本 + 选区。读不到返回 null。
     *
     * 选区也要算进去:光标移动、选中一段、删到空,都是他在操作,而这些不一定改变文本。
     */
    private fun ownerEditorFingerprint(svc: AccessibilityService): String? = runCatching {
        // 不挑「那个 isFocused 的窗口」,而是把主屏上的非输入法窗口挨个问一遍
        // 「你里面有拿着输入焦点的节点吗」。
        //
        // 挑窗口这条路真机上会间歇性地什么都读不到:讯飞的候选窗口有时自己是
        // isFocused,把它排掉之后就找不到别的获焦窗口了,指纹变成 null ——
        // 而 null 按设计要退回「只看状态」,于是让路又变成等满上限。
        // 现象是让路时长忽长忽短,而两个检测器的计数都是 0,看不出原因。
        // 拿着输入焦点的节点全系统同一时刻只有一个,不必先猜它在哪个窗口里。
        val wins = svc.windowsOnAllDisplays.get(USER_DISPLAY) ?: return null
        for (w in wins) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val node = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            // 有节点、但既没有文本也没有选区 = 这个 App 把内容挡掉了,节点在那儿也问不出
            // 东西。微信的搜索框就是这样:屏幕上明明写着「行行行」,节点报 text=null、
            // 选区 -1。**这种情况必须当成读不到**,不能当成「输入框没变」——
            // 后者会让判据在机主正打字时判他停手,那正是这个项目最不能出的错。
            if (node.text == null && node.textSelectionStart < 0 && node.textSelectionEnd < 0)
                continue
            return "${node.text}|${node.textSelectionStart}|${node.textSelectionEnd}"
        }
        null
    }.getOrNull()

    private var fpLast: String? = null
    private var fpChangedAt = 0L

    /** 已经消费掉的最后一条输入法事件时刻。比它新的才算「又有键落下」。 */
    private var imeSeenAt = 0L

    /**
     * 这一次键盘弹起期间,机主到底有没有按过键。
     *
     * 靠的是「输入法窗口发了带包名的内容变化事件」。实测这台机器上,键盘**弹起**
     * 本身不发这种事件(弹起走的是 TYPE_WINDOWS_CHANGED,那条不带包名),
     * 只有真敲下去才发 —— 两者因此分得开。换一台机器上如果输入法弹起也发,
     * 这个标志会一直是 true,判据退化成只有 60 秒那一档:更保守,不会更激进。
     *
     * 清零只发生在判据**看到**键盘落下的那一刻,而判据只在让路时才跑。
     * 两次任务之间键盘起起落落没人看,标志会残留 —— 残留同样只会让它多等,
     * 所以不去补这个洞(补的话要在无障碍回调里每次 WINDOWS_CHANGED 都枚举一遍窗口,
     * 那条回调滚一次信息流就上千次,不值)。
     */
    @Volatile private var ownerKeyedThisSession = false

    /** 这一次键盘弹起期间,输入框指纹读到过没有 —— 第一次读到不算「他按了键」 */
    private var fpSeenOnce = false

    /** 输入法那条信号在这台机器上证明过自己能用了吗 */
    @Volatile private var imeArmed = false

    /** 上一次判据到底读没读到输入框。读不到时让路上限要退回保守值。 */
    @Volatile private var fpReadable = false

    /** 这台机器上活动信号能不能用(两条任一可用即可)。都用不了就退回只看状态。 */
    private fun signalArmed() = fpReadable || imeArmed

    /**
     * 键盘还开着、但一直没动静,等多久算「机主停手了」。
     *
     * 两个数,是因为「没动静」有两种含义,风险不一样:
     *
     *   机主一个键都没按过 —— 输入框自动获焦、键盘自己弹出来的居多(聊天页一进去就这样)。
     *                        他手里没有任何没上屏的东西,判错的代价只是键盘收一下。
     *
     *   机主按过键         —— 他手里**可能攥着一串没上屏的字**:拼音打进去了、词还没选。
     *                        判错的代价是那串拼音被当场提交(真机上撞到过,见 FINDINGS)。
     *                        而「正在组词」这个状态从系统外面读不到 —— 讯飞连
     *                        setComposingText 都不调(IMMS 的 mCursorCandStart 恒为 -1),
     *                        输入法窗口的无障碍树只有一个「返回」按钮,窗口几何组词前后
     *                        一模一样。三条都验过,所以只能用时间兜。
     *
     * **两个默认值现在都是 10 秒**,是机主定的,理由是「打了拼音不可能十秒不上屏」——
     * 这条判断成立,10 秒的停顿在正常打字节奏里就是已经停手了。
     * 所以两档的行为目前一致,留着这个区分是因为它们承担的风险不同:哪天要给
     * 「组词中」更多宽限(换台机器、换个输入法,或者机主改主意),动 DRAFT_IDLE_MS
     * 一个数就够,不用重新想一遍判据。
     *
     * 剩下的口子如实记着:按完键盯着候选栏超过 DRAFT_IDLE_MS 一动不动,那串没上屏的
     * 拼音还是会丢。这是机主权衡后接受的 —— 另一头的代价是他打完字放下手机、键盘还
     * 开着时,agent 白等同样长的时间。
     */
    private fun idleMs(svc: AccessibilityService): Long =
        if (ownerKeyedThisSession)
            Config.get(svc, "DRAFT_IDLE_MS", "10000").toLongOrNull() ?: 10_000L
        else
            Config.get(svc, "TYPING_IDLE_MS", "10000").toLongOrNull() ?: 10_000L

    /** 只打一次「按停手算」的日志,免得 500 毫秒一轮刷屏 */
    @Volatile private var idleLogged = false

    // 这一轮让路里,两个检测器各自把空闲计时重置了多少次。
    // 只在让路结束时报一次 —— 让路等了多久之外还得能看出「是被什么一直推后的」,
    // 否则等久了只能猜。逐次打日志的话机主连打一分钟就是上百行。
    private var nFp = 0
    private var nWin = 0
    private var nBlind = 0
    private var nIme = 0

    /** 给 TYPING 探针看的:活动信号这一路此刻是什么状况。只读,不改判据的状态。 */
    fun activityStatus(svc: AccessibilityService): String {
        val now = SystemClock.uptimeMillis()
        val busy = EyesAndHands.instance?.ownerImeEventsSince(now - 5_000) ?: 0
        val fp = ownerEditorFingerprint(svc)
        val idle = if (fpChangedAt == 0L) "还没测过" else "${(now - fpChangedAt) / 1000} 秒"
        return "输入法窗口近 5 秒动了 $busy 次" +
            (if (imeArmed) "" else "(这条还没证明可用)") +
            ";输入框" + (fp?.let { "可读(${it.take(40)})" } ?: "读不到") +
            ";距上次有人动过 $idle" +
            ";这次键盘期间机主" + (if (ownerKeyedThisSession) "按过键(容忍 " else "没按过键(容忍 ") +
            "${idleMs(svc) / 1000} 秒安静)"
    }

    /** 判据的**状态**那一半:机主那块屏上有没有一个为他而开的输入法窗口。 */
    private fun imeUpForOwner(svc: AccessibilityService): Boolean {
        val byA11y = runCatching {
            svc.windowsOnAllDisplays.get(USER_DISPLAY)
                ?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        }.getOrDefault(false)
        if (byA11y) { a11ySeenIme = true; return !imeServesUs(svc) }

        // 无障碍说「没有输入法窗口」时,不能就这么信。
        //
        // 这个判据本身还没在真机上量过,而它失败的方式是**静默的**:上报不到 IME 窗口
        // 时它永远返回 false,让路机制一次都不触发,机主看到的现象和没修一模一样,
        // 而日志里一片正常。这个项目已经在这类判据上栽过一次(数「字有没有丢」,
        // 26/26 通过,机主真手指一试就断)。
        //
        // 所以在无障碍**从未**报出过 IME 窗口之前,每次都拿 IMMS 的状态兜一遍底。
        // 它没有显示器维度,但这台机器上 mDisplayIdToShowIme 实测恒为 0,
        // 也就是说 IME 只会在机主那块屏上 —— 对这个判断够用。
        // 一旦无障碍证明过自己能报,就不再花这趟 shell 往返。
        if (a11ySeenIme) return false
        val shown = Privileged.exec("dumpsys input_method | grep -m1 mInputShown")
        val byShell = shown.contains("mInputShown=true")
        if (byShell) Log.w("WPConflict", "无障碍没报告输入法窗口,但 IMMS 说键盘开着 —— 以 IMMS 为准")
        // 兜底这条路同样要区分这个键盘是为谁开的,否则机主在 app 里刚打完任务点开始,
        // 照样被自己挡住 —— 只是换了条代码路径。
        return byShell && !imeServesUs(svc)
    }

    /** 无障碍是否曾经成功报出过 IME 窗口。没有的话就一直走 shell 兜底。 */
    @Volatile private var a11ySeenIme = false

    /**
     * 主屏上那个输入法,是在为 WhalePhone 自己的输入框服务吗。
     *
     * 这个区分是必需的,少了它主演示路径直接断掉:机主在 app 里打完任务、点「开始」,
     * 点按钮不会自动收键盘,于是键盘还开着 —— agent 判定「机主在打字」,等满上限,
     * 回一句「这一轮先不开工」。**他刚下的任务被他下达任务的动作挡住了。**
     *
     * 根子在于判据说的和想说的不是一回事:想说的是「机主正在往**他自己的**东西里打字,
     * 现在抢焦点会让他丢掉推不回来的输入状态」,而实际判的只是「主屏上有输入法窗口」。
     * 输入法为我们自己服务时,那个前提不成立 —— 那是我们自己的窗口,而且机主刚按下
     * 开始,他的意图就是让 agent 开工。
     *
     * 判不出来的时候按「是机主的」算:守着比放过安全。
     */
    private fun imeServesUs(svc: AccessibilityService): Boolean = runCatching {
        // 找法和 ownerEditorFingerprint 一致:先认「里面有拿着输入焦点的节点」的那个窗口,
        // 找不到再退回 isFocused。只按 isFocused 挑会间歇性挑空(讯飞的候选窗口有时
        // 自己就是 isFocused),挑空之后这里返回 false = 「这键盘是机主的」——
        // 于是机主刚在本 app 里打完任务、点下开始,又被自己的键盘挡住。
        val wins = svc.windowsOnAllDisplays.get(USER_DISPLAY) ?: return false
        val holder = wins.firstOrNull {
            it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null
        } ?: wins.firstOrNull {
            it.isFocused && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return false
        holder.root?.packageName?.toString() == svc.packageName
    }.getOrDefault(false)

    /**
     * 机主在打字就先让路,返回让了多少毫秒。
     *
     * 这是「不打扰」这条要求在代码里唯一真正管用的地方,原因是实测出来的:
     * 副屏上一出现可获焦窗口,窗口管理器就把顶层焦点屏切过去;主屏没有 OWN_FOCUS
     * (那是造屏时的 flag,主屏加不上),于是 `findFocusedWindowIfNeeded` 给它返回 null
     * —— 主屏当场丢掉自己的焦点窗口。机主的软键盘被 IMMS 收起、投递到主屏的按键事件
     * 开始超时(那就是「应用未响应」),是同一件事的两个后果。
     *
     * 关键在于这一切都发生在「副屏出现新窗口」的那一瞬间,且不可逆:实测焦点推得回去,
     * 已经收起的键盘推不回来。所以只能不在那个瞬间和机主撞上。
     *
     * 上限存在,是因为机主可能开着键盘就走开了。无限等下去等于任务永远做不完,
     * 不如等够了就做,并把让了多久如实写进这一步的结果里 —— 让不让、让多久,
     * 是应该被看见的行为,不是应该被藏起来的实现细节。
     */
    fun yieldWhileOwnerTypes(svc: AccessibilityService, timeoutMs: Long = 0): Long {
        idleLogged = false; nFp = 0; nWin = 0; nBlind = 0; nIme = 0; tIme = 0; tFp = 0
        if (!ownerTyping(svc)) return 0
        // timeoutMs = 0 表示由这里决定上限,取决于「键落下」这个信号能不能用:
        //   能用 —— 正常出口是机主停手,上限只是信号出意外时的兜底,可以放得很宽,
        //           这样机主连续打十分钟就让十分钟,不会到点被打断。
        //   不能用 —— 判据退回只看状态,上限就是唯一的出口,只能维持原来的 60 秒。
        val cap = when {
            timeoutMs > 0 -> timeoutMs
            signalArmed() -> 300_000L
            else -> 60_000L
        }
        val t0 = SystemClock.uptimeMillis()
        // 500 毫秒一轮:走 shell 兜底那条路时每轮是一次 dumpsys,不能太密;
        // 而机主打完字到 agent 恢复晚半秒,没有任何影响。
        var polls = 0
        var tSleep = 0L
        while (ownerTyping(svc) && SystemClock.uptimeMillis() - t0 < cap) {
            polls++
            val a = SystemClock.uptimeMillis(); Thread.sleep(500)
            val d = SystemClock.uptimeMillis() - a; if (d > tSleep) tSleep = d
        }
        val waited = SystemClock.uptimeMillis() - t0
        // 到点了他还在打,那这一下就是实打实的打扰。必须能在日志里一眼看见,
        // 不能和「他停手了我才动」混成同一条。
        // 走的是哪一档要写在这一行里。等了多久之外,还得能一眼看出「凭什么认为他停手了」——
        // 60 秒和 10 秒是两个完全不同的结论,混在一条日志里等于没说。
        // 先取,再调 ownerTyping:后者看到键盘落下会把这个标志清掉。
        val tier = if (ownerKeyedThisSession) "他这次按过键,等的是 ${idleMs(svc) / 1000} 秒那一档"
                   else "他这次一个键都没按,等的是 ${idleMs(svc) / 1000} 秒那一档"
        val why = if (ownerTyping(svc)) "等到 ${cap / 1000} 秒上限,他还在打 —— 这一下会打断他"
                  else "机主停手了,输入框已经 ${SystemClock.uptimeMillis() - fpChangedAt} 毫秒没动($tier)"
        // 卡顿信息只在真卡了的时候才打。它是为了把「让得久」和「跑得慢」分开 ——
        // 探针路径上量到过 sleep(500) 实际睡 37 秒(没有前台服务时三星会限调度),
        // 不分开的话让路时长忽长忽短,会被当成判据出了问题去查。
        val slow = if (tSleep > 1_500 || tIme > 500 || tFp > 500)
            ";这一轮被调度拖慢了 看键盘 $tIme/看输入框 $tFp/睡 500 实际 $tSleep 毫秒" else ""
        Log.i("WPConflict", "机主在打字,让了 $waited 毫秒($why;轮询 $polls 次," +
            "输入框变了 $nFp 次,主屏窗口变了 $nWin 次,读不到输入框 $nBlind 次(其中输入法在动 $nIme 次)$slow)")
        return waited
    }

    /** 用户那块屏永远是 0 —— 主显示器的 id 由系统固定 */
    const val USER_DISPLAY = 0
}

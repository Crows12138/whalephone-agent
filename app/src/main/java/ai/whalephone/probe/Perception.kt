package ai.whalephone.probe

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 把一块显示器的无障碍树压成一份模型读得懂的文本。
 *
 * 为什么是树不是截图:
 *   1. 虚拟屏的截图路径本来就窄(screencap 只认物理显示器,实测返回 80 字节空图),
 *      而无障碍树是唯一原生带显示器维度的读取通道;
 *   2. 结构化文本比图便宜一个数量级,长时任务要跑几十上百步,这个差距是决定性的;
 *   3. 树里带 clickable / editable / bounds,动作能按序号精确落点,不用模型猜坐标。
 * 截图留作兜底:WebView / Canvas / 游戏这类树里读不出内容的场景。
 *
 * 压缩是这一层的主要工作。原始树里一个按钮常常是三个节点:可点的 FrameLayout、
 * 它的不可点包装、以及真正带文字的 TextView,三行说的是同一件事。模型看到三个
 * 长得一样的序号只会犯错,而且 token 白花。这里的做法是:以可交互节点为锚,
 * 把它子树里的文字收上来当标签,子树里非交互的节点不再单独出行。
 */
object Perception {

    /** 一个可交互元素 */
    data class Element(
        val index: Int,
        val role: String,
        val text: String?,
        val desc: String?,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checked: Boolean?,
        val node: AccessibilityNodeInfo,
    ) {
        fun label(): String = listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            desc?.takeIf { it.isNotBlank() && it != text },
        ).joinToString(" / ").replace(WS, " ").trim().ifBlank { "—" }

        /** 给模型看的一行 */
        fun render(): String {
            val flags = buildString {
                if (editable) append("可输入 ")
                else if (clickable) append("可点 ")
                if (scrollable) append("可滚 ")
                checked?.let { append(if (it) "已选 " else "未选 ") }
            }.trim()
            return "[$index] $role \"${label()}\"" + if (flags.isEmpty()) "" else "  $flags"
        }
    }

    data class Snapshot(
        val displayId: Int,
        val packages: List<String>,
        val elements: List<Element>,
    ) {
        fun render(): String = buildString {
            appendLine("显示器 $displayId  前台: ${packages.joinToString(", ").ifBlank { "(空)" }}")
            if (elements.isEmpty()) appendLine("(无可交互元素)")
            elements.forEach { appendLine(it.render()) }
        }
        fun byIndex(i: Int): Element? = elements.getOrNull(i)
    }

    /** windows 由调用方从 getWindowsOnAllDisplays() 取,便于单独测试这一层 */
    fun snapshot(displayId: Int, windows: List<AccessibilityWindowInfo>?): Snapshot {
        val out = mutableListOf<Raw>()
        val pkgs = LinkedHashSet<String>()
        windows.orEmpty().forEach { w ->
            val root = w.root ?: return@forEach
            root.packageName?.toString()?.let { pkgs += it }
            walk(root, out, HashSet())
        }
        val elements = prune(out).take(MAX_ELEMENTS).mapIndexed { i, r ->
            Element(i, r.role, r.text, r.desc, r.bounds, r.clickable, r.editable,
                r.scrollable, r.checked, r.node)
        }
        return Snapshot(displayId, pkgs.toList(), elements)
    }

    /**
     * 去掉被可交互元素盖住的纯文本。图标按钮常见的结构是「可点的容器」和
     * 「画文字的 View」互为兄弟而不是父子,压不进 collect() 收标签那一步,
     * 只能按位置和文字在事后对消。
     */
    private fun prune(raw: List<Raw>): List<Raw> {
        val hits = raw.filter { it.clickable || it.editable || it.scrollable }
        return raw.filter { r ->
            val t = r.text?.trim().orEmpty()
            val inner = r.clickable || r.editable || r.scrollable
            if (!inner) {
                // 纯文本:被某个可交互元素在位置和文字上都盖住了,就不单独出行
                if (t.isEmpty()) return@filter false
                return@filter hits.none { h -> h.bounds.contains(r.bounds) && h.text?.contains(t) == true }
            }
            // 嵌套的可点元素:商品卡片常见「外层容器 + 内层视图」,标签一模一样。
            // 内层没带新信息就丢掉,只留能点到整张卡的那个。
            if (t.isEmpty()) return@filter true
            hits.none { h ->
                h !== r && h.bounds.contains(r.bounds) && h.bounds != r.bounds &&
                    h.text?.contains(t) == true && h.text.length >= t.length
            }
        }
    }

    private class Raw(
        val role: String, val text: String?, val desc: String?, val bounds: Rect,
        val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
        val checked: Boolean?, val node: AccessibilityNodeInfo,
    )

    /**
     * @param consumed 已经被某个祖先当作标签收走的文字。子节点的文字若已在其中,
     *                 说明它只是那个按钮的内部构造,不再单独占一行。
     */
    private fun walk(n: AccessibilityNodeInfo, out: MutableList<Raw>, consumed: MutableSet<String>) {
        if (out.size >= MAX_ELEMENTS) return
        if (!n.isVisibleToUser) return

        val bounds = Rect().also { n.getBoundsInScreen(it) }
        val interactive = n.isClickable || n.isEditable || n.isScrollable ||
            n.isCheckable || n.isLongClickable
        val own = listOfNotNull(
            n.text?.toString()?.takeIf { it.isNotBlank() },
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        )

        var mine = consumed
        if (interactive && !bounds.isEmpty) {
            // 以这个节点为锚,把子树里的文字收上来当标签
            val label = collect(n, 0).joinToString(" ").take(MAX_LABEL)
            out += Raw(
                role = simpleRole(n.className?.toString()),
                text = n.text?.toString()?.takeIf { it.isNotBlank() } ?: label.takeIf { it.isNotBlank() },
                desc = n.contentDescription?.toString(),
                bounds = bounds,
                clickable = n.isClickable || n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                checked = if (n.isCheckable) n.isChecked else null,
                node = n,
            )
            mine = HashSet(consumed).also { it += collect(n, 0) }
        } else if (own.isNotEmpty() && own.none { it in consumed } && !bounds.isEmpty) {
            // 纯文本:价格、标题、状态提示这些,模型要靠它们判断当前在哪一步
            out += Raw("Text", own.first(), null, bounds, false, false, false, null, n)
            mine = HashSet(consumed).also { it += own }
        }

        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { walk(it, out, mine) }
        }
    }

    /** 收一个子树里的可见文字,深度有限,避免把整页收成一个标签 */
    private fun collect(n: AccessibilityNodeInfo, depth: Int): List<String> {
        if (depth > COLLECT_DEPTH) return emptyList()
        val here = listOfNotNull(
            n.text?.toString()?.takeIf { it.isNotBlank() },
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        )
        if (here.isNotEmpty()) return here
        val acc = mutableListOf<String>()
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { acc += collect(it, depth + 1) }
            if (acc.size >= 3) break
        }
        return acc
    }

    /** android.widget.TextView -> TextView;省掉包名,每行省二十来个字符 */
    private fun simpleRole(cls: String?): String =
        cls?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "View"

    private val WS = Regex("""\s+""")

    private const val MAX_ELEMENTS = 200
    private const val MAX_LABEL = 60
    private const val COLLECT_DEPTH = 3
}

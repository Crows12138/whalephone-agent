package ai.whalephone.agent

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 界面用到的配色和几个画背景的小工具。
 *
 * 没有引 androidx / material:这个 app 的依赖只有 Shizuku 和 hiddenapibypass,
 * 为了圆角和一套颜色把整个 material 拖进来不划算 —— 手写这一层更小,也不用跟着
 * 它的主题体系走。代价是这里要自己处理深浅色,见 Palette。
 */
object Ui {

    fun night(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).toInt()

    /** 圆角实心块。stroke 传 0 表示不描边。 */
    fun round(fill: Int, radiusPx: Int, stroke: Int = 0, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
            if (stroke != 0) setStroke(strokeWidth, stroke)
        }

    /** 气泡:四角圆,靠说话人那一侧的下角收一点,视觉上指向来源 */
    fun bubble(fill: Int, r: Int, mine: Boolean) = GradientDrawable().apply {
        setColor(fill)
        val small = (r * 0.3f)
        cornerRadii = if (mine)
            floatArrayOf(r * 1f, r * 1f, r * 1f, r * 1f, small, small, r * 1f, r * 1f)
        else
            floatArrayOf(r * 1f, r * 1f, r * 1f, r * 1f, r * 1f, r * 1f, small, small)
    }

    /** 圆形实底。图标按钮的底都用它 —— round() 传个大半径也能圆,但 OVAL 不用跟着尺寸算 */
    fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** 带渐变的圆。悬浮球用 —— 一个纯色圆压在任何壁纸上都像贴上去的贴纸 */
    fun ovalGradient(from: Int, to: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            shape = GradientDrawable.OVAL
        }

    /** 圆形按钮的按下水波。不给圆形遮罩的话水波是方的,会从圆底四角漏出来 */
    fun ovalRipple(content: android.graphics.drawable.Drawable, ripple: Int) =
        RippleDrawable(ColorStateList.valueOf(ripple), content, oval(Color.WHITE))

    /**
     * 图标按钮:圆底 + 矢量图标。
     *
     * 图标一律走 tint 上色,同一份资源在浅色/深色、静止/进行中之间只换颜色不换资源。
     * 这些位置原来用的是 emoji 字符(🎤 ➤):emoji 是各家自己的贴图,三星画得又厚又艳,
     * 和这一套线条图标摆在一起像贴错了,而且它不吃 tint —— 深色模式下颜色跟不上。
     */
    fun iconBtn(ctx: Context, res: Int, tint: Int, bg: Int, ripple: Int, padDp: Float = 9f) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(ctx, padDp)
            setPadding(p, p, p, p)
            background = ovalRipple(oval(bg), ripple)
        }

    /** 换图标按钮的颜色(和图形)。res 传 null 表示只换色 */
    fun repaintIcon(v: ImageView, res: Int?, tint: Int, bg: Int, ripple: Int) {
        if (res != null) v.setImageResource(res)
        v.imageTintList = ColorStateList.valueOf(tint)
        v.background = ovalRipple(oval(bg), ripple)
    }

    /** 点得动的东西都要有按下反馈,否则在没有 material 的情况下会显得像图片 */
    fun tappable(bg: GradientDrawable, ripple: Int) =
        RippleDrawable(ColorStateList.valueOf(ripple), bg, null)

    fun text(ctx: Context, s: CharSequence, size: Float, color: Int, bold: Boolean = false) =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    fun row(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun col(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h).apply { if (weight > 0) this.weight = weight }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * targetSdk 35 起系统强制 edge-to-edge:内容直接画到状态栏和导航栏底下,不再留白。
     * 表现是顶部几行被状态栏盖住。平台自带 insets API 够用,不为这一处引 androidx。
     */
    fun insets(v: View, top: Boolean = true, bottom: Boolean = true) {
        v.setOnApplyWindowInsetsListener { view, ins ->
            val s = ins.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
            )
            view.setPadding(s.left, if (top) s.top else 0, s.right, if (bottom) s.bottom else 0)
            ins
        }
        v.requestApplyInsets()
    }
}

/**
 * 一套配色两份取值。深色不是把颜色反过来 —— 深色下同样的强调蓝会发灰,
 * 所以两份各自调,不做算法换算。
 */
class Palette(night: Boolean) {
    val bg        = if (night) 0xFF0E1116.toInt() else 0xFFF3F5F8.toInt()
    val surface   = if (night) 0xFF171B22.toInt() else Color.WHITE
    val surfaceAlt= if (night) 0xFF1F2530.toInt() else 0xFFEDF0F5.toInt()
    val accent    = if (night) 0xFF4C8DFF.toInt() else 0xFF2563EB.toInt()
    val onAccent  = Color.WHITE
    val textMain  = if (night) 0xFFE7EAF0.toInt() else 0xFF111827.toInt()
    val textSub   = if (night) 0xFF9AA4B2.toInt() else 0xFF6B7280.toInt()
    val line      = if (night) 0xFF2A313C.toInt() else 0xFFE3E7EE.toInt()
    val ok        = if (night) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt()
    val warn      = if (night) 0xFFFBBF24.toInt() else 0xFFD97706.toInt()
    val bad       = if (night) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
    val ripple    = if (night) 0x33FFFFFF else 0x1A000000
    /** 悬浮球的渐变两头。比 accent 稍微往紫走一点,球才不至于和界面里的蓝按钮撞脸 */
    val ballFrom  = if (night) 0xFF4C8DFF.toInt() else 0xFF3B82F6.toInt()
    val ballTo    = if (night) 0xFF7C5CFF.toInt() else 0xFF5B4BE8.toInt()

    companion object {
        fun of(ctx: Context) = Palette(Ui.night(ctx))
    }
}

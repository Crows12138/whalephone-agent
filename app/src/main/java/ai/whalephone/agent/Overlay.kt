package ai.whalephone.agent

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 主屏上的两个悬浮窗:悬浮球,和副屏取景窗。
 *
 * 这里是整个 app 里唯一**主动在机主那块屏上放东西**的地方,所以规矩要写死在最前面:
 *
 *   1. 两个窗口都带 FLAG_NOT_FOCUSABLE。主屏的焦点窗口一变,IMMS 就会重算输入法目标,
 *      机主正在打的字会被收掉 —— 这个 app 花了最多力气解决的就是这件事,不能自己
 *      从悬浮窗这条路又把它做一遍。唯一的例外是机主主动点开输入面板要打字,
 *      那一刻才临时把这个标志摘掉,关掉立刻加回来。
 *   2. 都是机主自己开的,而且随时能关。默认全关。
 *   3. 取景窗只读画面,不接受任何对副屏的操作 —— 它是个窗口,不是遥控器。
 *      副屏上的操作只由 agent 做,机主要插手就直接说话/打字下任务。
 *      **「接管」是这条规矩的边界而不是例外**:它不替机主点副屏,它把任务从副屏
 *      搬到主屏、让 agent 停手。做完之后副屏上什么都不剩,机主面对的是一个普通的
 *      前台 App —— 不存在「两个人同时操作同一块屏」的那一刻,而那正是规矩三要防的。
 *
 * 服务是前台服务,因为悬浮球的意义就是「不打开 app 也在」。通知走最低优先级,
 * 且点它就能关掉悬浮球 —— 常驻的东西必须有一个显而易见的出口。
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var ball: Ball? = null
    private var screen: ScreenWindow? = null

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        // 悬浮球也要知道「它在等回答」这类状态,而这些状态跟对话记录是同一份
        AgentBus.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTI_ID, notification())
        // intent 为空 = 进程被回收后系统按 START_STICKY 重新拉起来的。
        // 这时候要按机主上次的选择恢复,不能一声不响地消失 —— 悬浮球的全部意义
        // 就是「它一直在」,被系统回收一次就再也不回来的话,这个功能等于没有。
        val action = intent?.action ?: restoreAction()
        when (action) {
            ACT_BALL_ON -> if (ball == null)
                ball = Ball(this, wm, ::micMode).also { it.show() }
            ACT_BALL_OFF -> { ball?.hide(); ball = null }
            ACT_SCREEN_ON -> if (screen == null) screen = ScreenWindow(this, wm).also { it.show() }
            ACT_SCREEN_OFF -> { screen?.hide(); screen = null }
            ACT_STOP -> { stopAll(); return START_NOT_STICKY }
        }
        // 恢复那条路要两个窗口各自还原,上面的 when 一次只处理一个
        if (intent == null) {
            if (Config.get(this, KEY_BALL) == "1" && ball == null)
                ball = Ball(this, wm, ::micMode).also { it.show() }
            if (Config.get(this, KEY_SCREEN) == "1" && screen == null)
                screen = ScreenWindow(this, wm).also { it.show() }
        }
        ballOn = ball != null
        screenOn = screen != null
        notifyState(this)
        if (action == ACT_RESTORE && ball == null && screen == null) {
            // 配置说该有窗口却一个都没建起来 —— 多半是权限被收回了。
            // 这时候不能把配置清成 0,否则机主重新给权限之后还得再开一次。
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        // 两个都关了就没有留着的理由。常驻服务要能自己消失,不能靠机主去清。
        if (ball == null && screen == null) { stopAll(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification())
        return START_STICKY
    }

    /**
     * 识别期间把前台服务的类型临时升成 microphone。
     *
     * 不升的话录音会被系统挡掉,而且挡得很安静:appops 里 RECORD_AUDIO 是
     * **foreground 模式** —— 只有进程状态够前台才放行,而「有个悬浮窗」不算。
     * 真机上量到的就是 `RECORD_AUDIO: allow; rejectTime=+2s`,识别器那边只报一个
     * ERROR_INSUFFICIENT_PERMISSIONS,看着像没给权限,其实权限早给了。
     *
     * 只在真正在听的时候升,听完降回去 —— 常驻一个 microphone 类型的前台服务,
     * 系统会一直显示麦克风指示灯,而这个 app 平时根本不用麦克风。
     */
    private fun micMode(on: Boolean) {
        val type = if (on)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        runCatching { startForeground(NOTI_ID, notification(), type) }
            .onFailure { Log.w(TAG, "切不了前台服务类型,语音可能录不上", it) }
    }

    /** 上次机主留着的是哪几个窗口。都没留就什么都不恢复,服务自己停掉。 */
    private fun restoreAction(): String? =
        if (Config.get(this, KEY_BALL) == "1" || Config.get(this, KEY_SCREEN) == "1")
            ACT_RESTORE else null

    private fun stopAll() {
        ball?.hide(); ball = null
        screen?.hide(); screen = null
        ballOn = false; screenOn = false
        notifyState(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ball?.hide(); screen?.hide()
        ball = null; screen = null
        ballOn = false; screenOn = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "悬浮窗", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "悬浮球和副屏取景窗还开着的时候显示" }
            )
        }
        val off = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = listOfNotNull(
            if (ball != null) "悬浮球" else null,
            if (screen != null) "副屏取景窗" else null,
        ).joinToString(" · ").ifBlank { "悬浮窗" }
        return Notification.Builder(this, CH)
            .setContentTitle("$what 开着")
            .setContentText("点这里全部收起")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(off)
            .build()
    }

    companion object {
        private const val TAG = "WPOverlay"
        private const val CH = "overlay"
        private const val NOTI_ID = 3

        const val ACT_BALL_ON = "ai.whalephone.agent.BALL_ON"
        const val ACT_BALL_OFF = "ai.whalephone.agent.BALL_OFF"
        const val ACT_SCREEN_ON = "ai.whalephone.agent.SCREEN_ON"
        const val ACT_SCREEN_OFF = "ai.whalephone.agent.SCREEN_OFF"
        const val ACT_STOP = "ai.whalephone.agent.OVERLAY_STOP"
        /** 开机 / 进程被回收后按上次的选择恢复 */
        const val ACT_RESTORE = "ai.whalephone.agent.OVERLAY_RESTORE"

        /** 配置项:两个开关各自记住上次的状态,方便重启后照旧 */
        const val KEY_BALL = "OVERLAY_BALL"
        const val KEY_SCREEN = "OVERLAY_SCREEN"

        @Volatile var ballOn = false; private set
        @Volatile var screenOn = false; private set

        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun onStateChanged(l: () -> Unit) = listeners.add(l)
        fun offStateChanged(l: () -> Unit) = listeners.remove(l)
        private fun notifyState(ctx: Context) {
            Config.set(ctx, KEY_BALL, if (ballOn) "1" else "0")
            Config.set(ctx, KEY_SCREEN, if (screenOn) "1" else "0")
            Handler(Looper.getMainLooper()).post { listeners.forEach { it() } }
        }

        fun granted(ctx: Context) = Settings.canDrawOverlays(ctx)

        /** 去要悬浮窗权限。这个权限只能由机主在系统设置里给,没有别的路。 */
        fun requestPermission(ctx: Context) = ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        fun setBall(ctx: Context, on: Boolean) = send(ctx, if (on) ACT_BALL_ON else ACT_BALL_OFF)
        fun setScreen(ctx: Context, on: Boolean) = send(ctx, if (on) ACT_SCREEN_ON else ACT_SCREEN_OFF)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).setAction(action)
            // 关的时候服务可能已经没了,startForegroundService 会白起一次再自己停,
            // 不影响正确性但会闪一下通知 —— 所以只在开的时候用前台启动。
            if (action.endsWith("_ON")) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * 悬浮窗的共同部分:不抢焦点、可拖动、贴边。
 *
 * FLAG_NOT_FOCUSABLE 是这里的核心约束,不是随手加的选项 —— 见 OverlayService 的说明。
 * FLAG_LAYOUT_NO_LIMITS 让窗口可以拖到状态栏/导航栏区域,否则贴边会贴出一条缝。
 */
abstract class FloatWindow(protected val ctx: Context, private val wm: WindowManager) {

    protected val pal = Palette.of(ctx)
    protected val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var root: View? = null
    protected var added = false; private set

    abstract fun build(): View

    open fun show() {
        if (added) return
        val v = build()
        root = v
        runCatching { wm.addView(v, lp) }
            .onFailure { Log.e("WPOverlay", "悬浮窗加不上去(没给权限?)", it); return }
        added = true
    }

    open fun hide() {
        val v = root ?: return
        runCatching { wm.removeView(v) }
        root = null; added = false
    }

    protected fun apply() { root?.let { runCatching { wm.updateViewLayout(it, lp) } } }

    /** 临时让窗口可获焦(机主要在里面打字时)。用完必须还回去。 */
    protected fun focusable(on: Boolean) {
        lp.flags = if (on)
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        apply()
    }

    protected fun screenSize(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /**
     * 把一个 View 变成「拖着能挪整个窗口」。
     *
     * 用触摸斜率区分拖动和点击:超过 touchSlop 才算拖,否则当点击交给 onClick。
     * 不这么分的话手指稍微一抖点击就丢了,而悬浮球主要靠点。
     */
    protected fun draggable(v: View, onTap: (() -> Unit)? = null, onDrop: (() -> Unit)? = null) {
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var ox = 0; var oy = 0; var dragging = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; ox = lp.x; oy = lp.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); apply() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDrop?.invoke()
                    else if (e.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * 副屏取景窗。
 *
 * 副屏是看不见的 —— 那正是它存在的理由,但也意味着机主没法知道 agent 到底在干什么,
 * 只能读通知里的一行字。这个窗口把那块屏的画面按帧搬到主屏一角,机主想看就看。
 *
 * 画面来源是 AgentDisplay 的 ImageReader,和 agent 自己截图用的是同一个出口 ——
 * 不新开一路采集,也就不多占一份内存。取帧走 captureLive:复用同一张位图,
 * 每帧 10 MB 的分配换成 10 MB 的拷贝(见那边的说明)。
 *
 * **不是遥控器。** 窗口上没有任何能操作副屏的东西 —— 理由写在 OverlayService 顶部。
 * 唯一的按钮是「接管」,而它做的事恰恰相反:把那个任务从副屏搬到机主眼前,然后让
 * agent 停手。它不是「替机主点副屏」,是「副屏这件事到此为止,剩下你自己来」。
 */
class ScreenWindow(ctx: Context, wm: WindowManager) : FloatWindow(ctx, wm) {

    private val fps = 5
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var canvasView: Frame
    private lateinit var hint: TextView
    private lateinit var takeBtn: TextView

    private var full = -1
    private var hasDisplay: Boolean? = null
    private var hintShown: Boolean? = null

    private val pump = object : Runnable {
        override fun run() {
            if (!added) return
            // 有没有副屏这件事在这里判,不在 onDraw 里判 —— 画的时候改别人的可见性
            // 会在同一帧里触发一次重新布局,安卓会警告 requestLayout during layout。
            val now = AgentService.liveDisplay != null

            // 那条提示带两种话:没副屏时解释这里为什么空着,有副屏时报接管为什么没成。
            // 后者是补上来的 —— 按钮在这个窗口上,而结果原来只进对话记录,
            // 机主看到的就是「点了没反应」。回话要落在按钮旁边。
            val note = if (now) Conflict.handoverNote() else "副屏还没建起来 —— 下个任务开始时会出现"
            val showHint = note != null
            if (now != hasDisplay || showHint != hintShown) {
                hasDisplay = now
                hintShown = showHint
                hint.visibility = if (showHint) View.VISIBLE else View.GONE
                canvasView.visibility = if (now) View.VISIBLE else View.GONE
                // 没有副屏就没有可接管的东西,按钮跟着画面一起收起来
                takeBtn.visibility = if (now) View.VISIBLE else View.GONE
                // 没有副屏的时候把窗口缩成一条提示,别在机主屏幕上占一大块空白
                lp.height = if (now) full else WindowManager.LayoutParams.WRAP_CONTENT
                apply()
            }
            if (note != null) {
                if (hint.text != note) hint.text = note
                // 「没成」和「本来就空着」是两回事,颜色上分开
                hint.setTextColor(if (now) pal.warn else pal.textSub)
            }
            // 文案每帧从事实推出来,不由点击那一下写死。
            //
            // 原来是点的时候把它改成「交接中…」,再指望上面那个 if 把它改回来 ——
            // 而那个 if 只在「有没有副屏」跳变时才进得去。接管失败时副屏根本没变,
            // 于是按钮永远停在「交接中…」。真机上撞到了。
            //
            // 病根不是少了一个「失败也重置」的分支,是**文案被当成状态用而这个状态
            // 没有归属**:谁改回去没有定义。而「正在交接」这件事本来就有归属 ——
            // Conflict.handingOver(),接管失败时 clearHandover() 会清掉它。
            // 按钮读它就行,不必自己记。
            if (now) {
                val want = if (Conflict.handingOver()) "交接中…" else "接管"
                if (takeBtn.text != want) takeBtn.text = want
                canvasView.invalidate()
            }
            handler.postDelayed(this, (1000L / fps))
        }
    }

    override fun build(): View {
        val r = Ui.dp(ctx, 14f)
        val (sw, sh) = screenSize()
        // 默认占屏宽三分之一,高度按主屏比例 —— 副屏就是照主屏的形状造的
        val w = (sw * 0.34f).toInt()
        val h = (w * sh.toFloat() / sw).toInt()
        lp.width = w
        full = h + Ui.dp(ctx, 30f)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.x = sw - w - Ui.dp(ctx, 12f)
        lp.y = Ui.dp(ctx, 90f)

        val box = Ui.col(ctx).apply {
            background = Ui.round(pal.surface, r, pal.line, Ui.dp(ctx, 1f))
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, v.width, v.height, r.toFloat())
            }
        }

        // 「接管」= 把这个任务搬到机主眼前 + agent 停手。搬成没搬成由服务那边判,
        // 这里只负责发出去和给一句「在办」—— 搬成了副屏就没了,窗口自己会缩成提示条。
        takeBtn = Ui.text(ctx, "接管", 11.5f, pal.accent, bold = true).apply {
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 4f), Ui.dp(ctx, 10f), Ui.dp(ctx, 4f))
            background = Ui.tappable(
                Ui.round(Color.TRANSPARENT, Ui.dp(ctx, 12f), pal.accent, Ui.dp(ctx, 1f)),
                pal.ripple)
            isClickable = true
            visibility = View.GONE
            // 这里不动文案:交接有没有真的开始由 Conflict 说了算,pump 每帧读它。
            // 点了之后最多 200 毫秒(5 帧/秒)才变字,换来的是「它说交接中就真的在交接」。
            setOnClickListener { AgentService.takeover(ctx) }
        }

        val bar = Ui.row(ctx).apply {
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 5f), Ui.dp(ctx, 4f), Ui.dp(ctx, 5f))
            addView(Ui.text(ctx, "副屏", 12f, pal.textSub, bold = true), Ui.lp(Ui.WRAP, Ui.WRAP))
            addView(View(ctx), Ui.lp(0, 1, 1f))
            addView(takeBtn, Ui.lp(Ui.WRAP, Ui.WRAP))
            addView(Ui.iconBtn(ctx, R.drawable.ic_close, pal.textSub, Color.TRANSPARENT,
                pal.ripple, padDp = 7f).apply {
                setOnClickListener { OverlayService.setScreen(ctx, false) }
            }, Ui.lp(Ui.dp(ctx, 30f), Ui.dp(ctx, 30f)))
        }

        canvasView = Frame(ctx)
        hint = Ui.text(ctx, "副屏还没建起来 —— 下个任务开始时会出现", 11f, pal.textSub).apply {
            setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 12f), Ui.dp(ctx, 12f), Ui.dp(ctx, 12f))
        }

        box.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(hint, Ui.lp(Ui.MATCH, Ui.WRAP))
        box.addView(canvasView, Ui.lp(Ui.MATCH, 0, 1f))

        // 整个窗口都能拖,不只是顶上那一条 —— 这块窗口大半个身子是画面,
        // 机主的手第一下本来就落在画面上。事件之所以能落到这里:画面那个 View
        // 和标题栏都不处理触摸,只有 ✕ 自己吃掉,所以关掉那一下不会变成拖动。
        draggable(box, onDrop = { clamp() })
        return box
    }

    /**
     * 松手后把窗口拉回屏内。
     *
     * 整块都能拖之后更容易一把甩出去,而这个窗口带 FLAG_LAYOUT_NO_LIMITS ——
     * 甩出屏幕就再也点不着了,只能去设置里把取景窗关了再开。至少留一条边在屏内。
     */
    private fun clamp() {
        val (sw, sh) = screenSize()
        val w = if (lp.width > 0) lp.width else Ui.dp(ctx, 120f)
        val keep = Ui.dp(ctx, 48f)
        lp.x = min(max(lp.x, keep - w), sw - keep)
        lp.y = min(max(lp.y, 0), sh - keep)
        apply()
    }

    override fun show() {
        super.show()
        if (added) handler.post(pump)
    }

    override fun hide() {
        handler.removeCallbacks(pump)
        super.hide()
    }

    /** 直接画位图,不走 ImageView —— 省一次 setImageBitmap 带来的整层重建 */
    private inner class Frame(c: Context) : View(c) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()
        private val dst = RectF()

        override fun onDraw(canvas: Canvas) {
            val d = AgentService.liveDisplay
            val bmp: Bitmap? = d?.let { runCatching { it.captureLive() }.getOrNull() }
            if (d == null || bmp == null || bmp.isRecycled) {
                canvas.drawColor(pal.surfaceAlt)
                return
            }
            canvas.drawColor(Color.BLACK)
            // 位图右侧可能带 rowStride 补白,按可见宽度裁掉,否则画面会被压扁一条
            src.set(0, 0, min(d.liveVisibleWidth, bmp.width), bmp.height)
            val scale = min(width.toFloat() / src.width(), height.toFloat() / src.height())
            val dw = src.width() * scale; val dh = src.height() * scale
            dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * 悬浮球。
 *
 * 收起时是一颗可拖的球。点开**默认进语音**,不是进打字 —— 机主开这颗球的理由就是
 * 「不想打开 app,说一句话就走」,而弹键盘本身就是一次打断:窗口得获焦、输入法要
 * 重挂目标、他底下那个 App 的焦点被拿走。所以展开分成两态:
 *
 *   语音态(点球默认进这个):窗口保持 FLAG_NOT_FOCUSABLE,整条路不碰焦点、不碰
 *     输入法,就在球原来那个高度就地展开,立刻开始听,识别到的字实时显示。
 *   键盘态:机主自己点「键盘」才进。这一刻才摘掉 NOT_FOCUSABLE、显式叫输入法,
 *     并把面板贴到屏幕下沿给键盘让位置;退出时立刻还回去。
 *
 * 原来这两态是一态:点球直接进键盘态,于是面板从球那儿跳到屏幕底部、键盘弹出来、
 * 焦点被拿走,三件事一起发生,看着像点错了 —— 而这三件事恰恰是这个 app 的立场里
 * 最该避免的。语音是主路径,键盘是备选,现在按这个分。
 *
 * 识别完不自动派任务:识别错一个字,这里的代价是「它真的去你手机上操作了」。
 * 所以最后一步留给机主点一下「发送」。
 */
class Ball(
    ctx: Context,
    wm: WindowManager,
    /** 通知宿主服务进出「在听」状态 —— 录音要靠它把前台服务类型升成 microphone */
    private val micMode: (Boolean) -> Unit,
) : FloatWindow(ctx, wm) {

    private enum class Mode { REST, VOICE, TYPE }

    private lateinit var bubble: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var voiceBox: LinearLayout
    private lateinit var typeBar: LinearLayout
    private lateinit var micBtn: MicOrb
    private lateinit var heard: TextView
    private lateinit var state: TextView
    private lateinit var sendBtn: TextView
    private lateinit var input: EditText

    private var mode = Mode.REST
    private var voice: Voice? = null
    /** agent 在等回答时球换个颜色 —— 机主多半没开着 app,这是他唯一看得见的地方 */
    private val onBus: (AgentBus.Line?) -> Unit = { paintBubble() }
    private var pulse: ValueAnimator? = null
    /** 识别器报过音量没有。报了就用真实音量驱动麦克风的大小,没报才退回自己呼吸 */
    private var gotLevel = false
    private var restX = 0
    private var restY = 0

    private val ui = Handler(Looper.getMainLooper())
    /** 语音这条路走到头(没听清 / 没权限)之后自己收起来 —— 一直摊在机主屏幕上是打扰 */
    private val autoRest = Runnable { if (mode == Mode.VOICE) toRest() }
    private val restSoon = Runnable { toRest() }

    /** 收起状态:不获焦、可拖到状态栏底下 */
    private val restFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /**
     * 语音态:**仍然不获焦**。机主底下那个 App 该是焦点还是焦点,他打了一半的字也还在。
     * WATCH_OUTSIDE_TOUCH 只是为了「点别处收起来」—— 不获焦的窗口本来就不吃别处的触摸,
     * 那一下照样送到底下的 App。
     */
    private val voiceFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /**
     * 键盘态:要获焦(不然输入法不会为它弹出来),但必须**不吃掉别处的触摸** ——
     * 可获焦的窗口默认是 touch-modal,而这条面板横贯屏宽,不加 NOT_TOUCH_MODAL 的话
     * 机主点屏幕上任何地方都会被它接住。
     */
    private val typeFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    override fun build(): View {
        val (sw, sh) = screenSize()
        lp.x = sw - Ui.dp(ctx, 58f); lp.y = (sh * 0.55f).toInt()
        restX = lp.x; restY = lp.y

        bubble = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_whale)
            imageTintList = android.content.res.ColorStateList.valueOf(pal.onAccent)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 留白比一般图标按钮小:鲸尾是个扁的形状,按 24 格等比缩进去之后
            // 上下本来就空着一截,再多留白它在球里就只剩一点点
            val p = Ui.dp(ctx, 8f)
            setPadding(p, p, p, p)
            elevation = Ui.dp(ctx, 8f).toFloat()
        }
        paintBubble()
        AgentBus.subscribe(onBus)
        draggable(bubble, onTap = { toVoice() }, onDrop = { snap() })

        panel = buildPanel()
        panel.visibility = View.GONE

        return Root(ctx).apply {
            addView(panel, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(bubble, Ui.lp(Ui.dp(ctx, 52f), Ui.dp(ctx, 52f)))
        }
    }

    /**
     * 键盘态下窗口可获焦,于是它会收到返回键 —— 机主按返回的第一意图是「关掉这个面板」,
     * 不是退出他底下那个 App。不接这一下的话返回会穿过去,把他正在看的东西退掉。
     * 两态下点面板外面(ACTION_OUTSIDE)都收起。
     */
    private inner class Root(c: Context) : LinearLayout(c) {
        init { orientation = VERTICAL }
        override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
            if (mode == Mode.TYPE && e.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                e.action == android.view.KeyEvent.ACTION_UP) { toRest(); return true }
            return super.dispatchKeyEvent(e)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (mode != Mode.REST && e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                toRest(); return true
            }
            return super.onTouchEvent(e)
        }
    }

    private fun paintBubble() {
        bubble.background =
            if (AgentBus.asking != null) Ui.ovalGradient(pal.warn, pal.bad)
            else Ui.ovalGradient(pal.ballFrom, pal.ballTo)
    }

    private fun buildPanel(): LinearLayout {
        voiceBox = buildVoiceBox()
        typeBar = buildTypeBar()
        return Ui.col(ctx).apply {
            background = Ui.round(pal.surface, Ui.dp(ctx, 24f), pal.line, Ui.dp(ctx, 1f))
            elevation = Ui.dp(ctx, 10f).toFloat()
            addView(voiceBox, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(typeBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
    }

    // ---- 语音态 ------------------------------------------------------------

    private fun buildVoiceBox(): LinearLayout {
        micBtn = MicOrb(ctx).apply {
            foreground = Ui.ovalRipple(Ui.oval(Color.TRANSPARENT), pal.ripple)
            setOnClickListener {
                ui.removeCallbacks(autoRest)
                if (voice != null) { stopVoice(); say("停了,点麦克风重说") } else startVoice()
            }
        }
        state = Ui.text(ctx, "", 11f, pal.textSub)
        heard = Ui.text(ctx, "", 16f, pal.textMain).apply {
            setLineSpacing(0f, 1.1f)
            visibility = View.GONE
        }
        val close = Ui.iconBtn(ctx, R.drawable.ic_close, pal.textSub, Color.TRANSPARENT,
            pal.ripple, padDp = 8f).apply { setOnClickListener { toRest() } }

        val words = Ui.col(ctx).apply {
            addView(state, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(heard, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 2f) })
        }
        val top = Ui.row(ctx).apply {
            addView(micBtn, Ui.lp(Ui.dp(ctx, 54f), Ui.dp(ctx, 54f)))
            addView(words, Ui.lp(0, Ui.WRAP, 1f).apply {
                marginStart = Ui.dp(ctx, 12f); marginEnd = Ui.dp(ctx, 8f)
            })
            addView(close, Ui.lp(Ui.dp(ctx, 34f), Ui.dp(ctx, 34f)))
        }

        val keyboard = pill("键盘", R.drawable.ic_keyboard, accent = false) {
            toType(heard.text.toString())
        }
        sendBtn = pill("发送", R.drawable.ic_send, accent = true) { send(heard.text.toString()) }
        sendBtn.visibility = View.GONE
        val acts = Ui.row(ctx).apply {
            addView(keyboard, Ui.lp(Ui.WRAP, Ui.dp(ctx, 34f)))
            addView(View(ctx), Ui.lp(0, 1, 1f))
            addView(sendBtn, Ui.lp(Ui.WRAP, Ui.dp(ctx, 34f)))
        }

        return Ui.col(ctx).apply {
            val p = Ui.dp(ctx, 12f)
            setPadding(p, p, p, p)
            addView(top, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(acts, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(ctx, 10f) })
        }
    }

    private fun pill(t: String, res: Int, accent: Boolean, onClick: () -> Unit) =
        Ui.text(ctx, t, 13f, if (accent) pal.onAccent else pal.textSub).apply {
            gravity = Gravity.CENTER
            val fg = if (accent) pal.onAccent else pal.textSub
            background = Ui.tappable(Ui.round(
                if (accent) pal.accent else pal.surfaceAlt, Ui.dp(ctx, 17f)), pal.ripple)
            setPadding(Ui.dp(ctx, 15f), 0, Ui.dp(ctx, 17f), 0)
            // 图标按 16dp 摆,不用 intrinsic bounds —— 那是 24dp,挨着 13sp 的字太大
            val d = ctx.getDrawable(res)!!.mutate().apply {
                setTint(fg)
                val n = Ui.dp(ctx, 16f)
                setBounds(0, 0, n, n)
            }
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = Ui.dp(ctx, 6f)
            setOnClickListener { ui.removeCallbacks(autoRest); onClick() }
        }

    private fun toVoice() {
        val (sw, sh) = screenSize()
        mode = Mode.VOICE
        ui.removeCallbacks(autoRest); ui.removeCallbacks(restSoon)
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        voiceBox.visibility = View.VISIBLE
        typeBar.visibility = View.GONE
        heard("")
        sendBtn.visibility = View.GONE
        sendBtn.text = if (AgentBus.asking != null) "回答" else "发送"
        lp.flags = voiceFlags
        lp.width = sw - Ui.dp(ctx, 20f)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Ui.dp(ctx, 10f)
        // 就地展开:面板出现在球原来那个高度上,不跳到屏幕别处去
        lp.y = min(max(restY - Ui.dp(ctx, 8f), Ui.dp(ctx, 48f)), sh - Ui.dp(ctx, 200f))
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        apply()
        startVoice()
    }

    private fun startVoice() {
        if (voice != null) return
        heard("")
        sendBtn.visibility = View.GONE
        if (!Voice.available(ctx)) { say("这台机器上没有语音识别,点「键盘」打字"); armRest(); return }
        if (!Voice.micGranted(ctx)) { say("还没给录音权限 —— 打开 app,设置里给一次"); armRest(); return }
        micMode(true)
        // 麦克风还没开,这时候说「在听」是假的。真的开了由 onReady 通知(还会响一
        // 声),文案和呼吸动画都等到那时候再上 —— 否则机主对着一个还没开的麦克风说话
        say("正在打开麦克风,先别说…")
        voice = Voice(
            ctx,
            onPartial = { t -> heard(t) },
            onFinal = { t ->
                stopVoice()
                if (t.isBlank()) { say("没听清,点麦克风再说一次"); armRest() }
                else { heard(t); say("要它做这个吗?"); sendBtn.visibility = View.VISIBLE }
            },
            onError = { msg -> stopVoice(); say(msg); armRest() },
            onLevel = { rms -> level(rms) },
            onReady = {
                listening(true)
                // 它在等回答的时候,说出来的话是答案不是新任务,得让机主看得出来
                say(AgentBus.asking?.let { "在听你的回答 · 它问:" + it } ?: "在听…")
            },
        ).also { it.start() }
    }

    private fun stopVoice() {
        val had = voice != null
        voice?.stop(); voice = null
        listening(false)
        if (had) micMode(false)
    }

    /** 在听的时候麦克风要看得出来在动 —— 否则机主不知道它到底听没听见 */
    private fun listening(on: Boolean) {
        pulse?.cancel(); pulse = null
        micBtn.listening = on
        micBtn.level = 0f
        if (!on) { gotLevel = false; return }
        // 识别器不一定报音量。报之前光晕自己呼吸,报了就交给真实音量(见 level)
        pulse = ValueAnimator.ofFloat(0.15f, 0.85f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a -> if (!gotLevel) micBtn.level = a.animatedValue as Float }
            start()
        }
    }

    /** onRmsChanged 的量纲是 dB,各家实现不一,这里只当作「有多大声」压进一个小范围 */
    private fun level(rms: Float) {
        if (!gotLevel) { gotLevel = true; pulse?.cancel(); pulse = null }
        micBtn.level = (rms.coerceIn(-2f, 10f) + 2f) / 12f
    }

    /**
     * 麦克风按钮。在听的时候外面一圈光晕跟着音量涨落 ——
     * 机主得看得出来它真听见了,光有一行「在听…」的字看不出死活。
     */
    private inner class MicOrb(c: Context) : View(c) {
        var listening = false
            set(v) { field = v; invalidate() }

        /** 已经归一化到 0..1 的音量 */
        var level = 0f
            set(v) { field = v; invalidate() }

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = ctx.getDrawable(R.drawable.ic_mic)!!.mutate()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            val core = r * 0.74f
            if (listening) {
                p.color = pal.bad
                p.alpha = 56
                canvas.drawCircle(cx, cy, core + (r - core) * (0.2f + 0.8f * level), p)
            }
            p.color = if (listening) pal.bad else pal.surfaceAlt
            canvas.drawCircle(cx, cy, core, p)
            val g = (core * 1.2f).toInt()
            glyph.setTint(if (listening) pal.onAccent else pal.accent)
            glyph.setBounds((cx - g / 2).toInt(), (cy - g / 2).toInt(),
                (cx + g / 2).toInt(), (cy + g / 2).toInt())
            glyph.draw(canvas)
        }
    }

    private fun say(s: String) { state.text = s }

    /** 识别到的原话。没有字就整行收起来 —— 空着一行会把「在听…」顶得偏上 */
    private fun heard(t: String) {
        heard.text = t
        heard.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
    }

    private fun armRest() = ui.postDelayed(autoRest, 6_000)

    // ---- 键盘态 ------------------------------------------------------------

    private fun buildTypeBar(): LinearLayout {
        val back = Ui.iconBtn(ctx, R.drawable.ic_mic, pal.accent, pal.surfaceAlt, pal.ripple)
            .apply { setOnClickListener { toVoice() } }
        input = EditText(ctx).apply {
            hint = "说一句你要它做什么"
            setHintTextColor(pal.textSub)
            setTextColor(pal.textMain)
            textSize = 15f
            setSingleLine()
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> send(text.toString()); true }
        }
        val go = Ui.iconBtn(ctx, R.drawable.ic_send, pal.onAccent, pal.accent, pal.ripple, padDp = 10f)
            .apply { setOnClickListener { send(input.text.toString()) } }
        return Ui.row(ctx).apply {
            visibility = View.GONE
            setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 7f), Ui.dp(ctx, 10f), Ui.dp(ctx, 7f))
            addView(back, Ui.lp(Ui.dp(ctx, 38f), Ui.dp(ctx, 38f)))
            addView(input, Ui.lp(0, Ui.WRAP, 1f).apply {
                marginStart = Ui.dp(ctx, 10f); marginEnd = Ui.dp(ctx, 10f)
            })
            addView(go, Ui.lp(Ui.dp(ctx, 38f), Ui.dp(ctx, 38f)))
        }
    }

    /**
     * 进打字。这是整个 app 里唯一一处主动拿主屏焦点的地方,前提是机主自己点的「键盘」,
     * 而且一退出立刻还回去。面板贴屏幕下沿,输入法弹出来时正好把它顶在键盘上面,
     * 不用自己算键盘高度。
     */
    private fun toType(prefill: String) {
        val (sw, _) = screenSize()
        stopVoice()
        mode = Mode.TYPE
        ui.removeCallbacks(autoRest); ui.removeCallbacks(restSoon)
        bubble.visibility = View.GONE
        panel.visibility = View.VISIBLE
        voiceBox.visibility = View.GONE
        typeBar.visibility = View.VISIBLE
        lp.flags = typeFlags
        lp.width = sw - Ui.dp(ctx, 20f)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.x = Ui.dp(ctx, 10f)
        lp.y = Ui.dp(ctx, 12f)
        // 不写这一句,输入法认为这个窗口不需要它:面板出来了键盘不弹(真机上量到过)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        apply()
        input.setText(prefill)
        input.setSelection(input.text.length)
        input.requestFocus()
        // ADJUST_RESIZE 只是允许弹,真正把它叫起来还得显式要一次
        input.post { ime()?.showSoftInput(input, 0) }
    }

    // ---- 收起 --------------------------------------------------------------

    private fun toRest() {
        mode = Mode.REST
        ui.removeCallbacks(autoRest); ui.removeCallbacks(restSoon)
        stopVoice()
        input.setText("")
        heard("")
        say("")
        sendBtn.visibility = View.GONE
        runCatching { ime()?.hideSoftInputFromWindow(input.windowToken, 0) }
        panel.visibility = View.GONE
        voiceBox.visibility = View.GONE
        typeBar.visibility = View.GONE
        bubble.visibility = View.VISIBLE
        lp.flags = restFlags
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.TOP or Gravity.START
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        lp.x = restX; lp.y = restY
        apply()
    }

    private fun ime() =
        ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)

    /** 松手后贴到最近的一边。悬浮球飘在屏幕中间会挡东西。 */
    private fun snap() {
        if (mode != Mode.REST) return
        val (sw, sh) = screenSize()
        lp.x = if (lp.x + Ui.dp(ctx, 26f) < sw / 2) Ui.dp(ctx, 6f) else sw - Ui.dp(ctx, 58f)
        lp.y = max(Ui.dp(ctx, 40f), min(lp.y, sh - Ui.dp(ctx, 120f)))
        restX = lp.x; restY = lp.y
        apply()
    }

    private fun send(g0: String) {
        val g = g0.trim()
        if (g.isBlank()) { say("先说要做什么"); return }
        val q = AgentBus.asking
        if (q == null && Config.get(ctx, Config.KEY_API_KEY).isBlank()) {
            say("还没配模型密钥,先打开 app 设置一次"); return
        }
        stopVoice()
        if (q != null) AgentService.answer(ctx, g) else AgentService.start(ctx, g)
        heard(g)
        say(if (q != null) "答复给它了,它接着做" else "已经派下去了,进度看通知")
        sendBtn.visibility = View.GONE
        input.setText("")
        ui.postDelayed(restSoon, 900)
    }

    override fun hide() {
        ui.removeCallbacks(autoRest); ui.removeCallbacks(restSoon)
        AgentBus.unsubscribe(onBus)
        stopVoice()
        super.hide()
    }
}

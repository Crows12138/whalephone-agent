package ai.whalephone.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread

/**
 * agent 的宿主。前台服务,因为它必须在用户切走之后继续跑 ——
 * 「和主人同时使用同一台手机」的前提就是它不能只在自己被看着的时候活着。
 *
 * 它管三样东西:副屏的生命周期、决策循环的线程、以及和用户之间那条
 * 唯一允许的窄通道 —— 通知。
 *
 * 为什么是通知而不是弹窗:弹窗会抢焦点、盖住用户正在看的东西,那前面所有
 * 为了不打扰做的工作就全白做了。通知是安卓里唯一一个不打断、可稍后处理、
 * 且打扰级别由用户自己掌握的通道。agent 需要拍板时把问题放这儿,用户
 * 什么时候看都行。
 */
class AgentService : Service() {

    private var worker: Thread? = null
    private var feed: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_STOP) { Watch.clear(this); stop(); return START_NOT_STICKY }

        val goal = intent?.getStringExtra(EXTRA_GOAL).orEmpty()
        if (goal.isBlank()) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTI_ID, notify("准备中", goal))
        if (worker?.isAlive == true) { note("已经有任务在跑了"); return START_STICKY }

        stopping = false
        worker = thread(name = "wp-agent") { runTask(goal) }
        return START_STICKY
    }

    private fun runTask(goal: String) {
        val probe = EyesAndHands.instance
        if (probe == null) { finish("无障碍服务没开,agent 没有眼睛"); return }
        val llm = Config.llm(this) ?: run { finish("没配 LLM_API_KEY"); return }

        // 开发期口子:接管一块已经由 ADB 造好的副屏,跳过 Shizuku 授权那一步。
        // 生产路径(下面的 else)一步不少,这里只是让「感知-决策-操作」这条链
        // 能在没拿到授权时先单独验证 —— 两个未知搅在一起排查不了。
        val devId = Config.get(this, KEY_DEV_DISPLAY).toIntOrNull()
        val displayId: Int
        if (devId != null) {
            displayId = devId
            Log.w(TAG, "开发模式:接管已有副屏 $devId,不经过特权桥")
            note("开发模式 · 副屏 $devId")
        } else {
            if (!Privileged.connect(this)) { finish("特权桥没连上,检查 Shizuku 是否在运行并已授权"); return }
            // 副屏跨任务复用。造屏那一下会收起用户的输入法(虽然随后立刻还了回去,
            // 但仍是一次可感知的抖动),每个任务重造一次就是每个任务抖一次。
            // 造一次留着,只在用户明确停止时销毁。
            val d = shared ?: AgentDisplay.create(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels,
                resources.displayMetrics.densityDpi,
            )?.also { shared = it } ?: run { finish("副屏创建失败"); return }
            displayId = d.displayId
            Log.i(TAG, "副屏 ${d.displayId} 就绪: ${d.guarantees()}")
            note("副屏 ${d.displayId} 就绪 · ${d.guarantees()}")
        }

        startFeedIfAsked()

        val hands = Hands(probe, displayId, this)
        val agent = Agent(hands, llm, goal)
        agent.onStep = { s -> if (!stopping) update("第 ${s.n} 步 · ${s.action}", s.result.take(80)) }
        agent.onAsk = { q -> ask(q) }

        val out = runCatching { agent.run() }.getOrElse {
            Log.e(TAG, "循环挂了", it)
            Agent.Outcome(false, "异常: ${it.message}", emptyList())
        }
        Log.i(TAG, "结束 done=${out.done} ${out.message}")

        // 长时任务:这一轮结束不等于任务结束
        if (Watch.plan(this) != null) {
            val changed = Watch.record(this, out.message)
            if (Watch.roundsLeft(this) <= 0) {
                Watch.clear(this)
                finish("盯完了。最后一次的结果:${out.message}")
            } else {
                if (changed) alert("有变化", out.message)
                update("盯着呢 · ${Watch.statusLine(this)}", out.message)
                Log.i(TAG, "本轮结束,${if (changed) "有变化" else "没变化"}")
            }
            return
        }
        finish(out.message)
    }

    /**
     * 演示取景窗。副屏是看不见的 —— 这正是它的意义,但也意味着录演示视频时
     * 没法证明 agent 真的在做事。打开这个开关,任务期间把副屏的画面按秒写成 PNG,
     * 电脑端 `scripts/vd_view.py` 拉过去显示,录成画中画。
     *
     * 它只是一个取景窗:agent 不依赖它,关掉照常工作。数据线在演示里只用来
     * 传这张图,不参与 agent 的任何一步。
     */
    private fun startFeedIfAsked() {
        if (Config.get(this, KEY_DEMO_FEED) != "1") return
        val d = shared ?: run { Log.w(TAG, "开发模式下没有自己的副屏,取景窗开不了"); return }
        // 同一时刻只该有一个取景线程。服务被反复 start 时,旧线程如果还活着,
        // 会和新线程抢同一个文件 —— 而且它持有的可能是一块已经销毁的屏。
        feed?.interrupt()
        feed = thread(name = "wp-feed") {
            var blanks = 0
            while (!stopping && worker?.isAlive != false) {
                val ok = runCatching { d.captureTo(FEED_PATH) }.getOrDefault(false)
                val n = runCatching { java.io.File(FEED_PATH).length() }.getOrDefault(0L)
                // 纯黑的 1080x2340 PNG 只有十几 KB,正常界面是几百 KB 到 1 MB+。
                // 取景窗黑掉过一次而我当时不知道,所以这里把它变成可观测的。
                if (ok && n in 1..40_000) {
                    if (blanks++ == 0) Log.w(TAG, "取景窗抓到近乎空白的一帧 屏=${d.displayId} $n 字节")
                } else if (ok) blanks = 0
                if (!ok) Log.v(TAG, "取景窗这一轮没有新帧")
                runCatching { Thread.sleep(700) }.onFailure { return@thread }
            }
        }
        Log.i(TAG, "取景窗已开 -> $FEED_PATH")
    }

    /**
     * 收尾必须主动销毁副屏。实测过:用 scrcpy 持有虚拟屏时如果保留内容,
     * 销毁那一刻副屏上开着的所有 App 会一股脑掉到用户主屏上,用户会突然
     * 看见一堆自己没开过的界面。这里走 DisplayManager 的正常销毁,
     * 副屏上的 task 随屏一起消失,主屏不受影响。
     */
    private fun stop() {
        stopping = true
        worker?.interrupt()
        shared?.release(); shared = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish(msg: String) {
        // 一次性任务结束也留着副屏:用户很可能接着下一个任务,重造一次就多抖一次。
        // 真正销毁只发生在用户点「停止」,或者服务被系统回收。
        update("任务结束", msg, ongoing = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * 只有用户明确点了停止(stop() 里置 stopping)才销毁副屏。
     *
     * 这里原来是无条件 release —— 而 finish() 每个任务结束都会 stopSelf(),
     * 于是「副屏跨任务复用」这条设计从来没生效过:每个任务都重造一次屏,
     * 也就每个任务都让用户的输入法抖一次。这正是本项目声称要避免的那种打扰。
     */
    override fun onDestroy() {
        if (stopping) { shared?.release(); shared = null }
        feed = null
        super.onDestroy()
    }

    // ---- 通知 ----

    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm().createNotificationChannel(
                NotificationChannel(CH, "手机助理", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "后台任务进度。低优先级,不会出横幅打断你。" }
            )
            nm().createNotificationChannel(
                NotificationChannel(CH_ASK, "需要你拍板", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notify(title: String, body: String, ongoing: Boolean = true): Notification {
        channel()
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AgentService::class.java).setAction(ACT_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CH)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(ongoing)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "停止", stopIntent).build())
            .build()
    }

    private fun update(title: String, body: String, ongoing: Boolean = true) =
        nm().notify(NOTI_ID, notify(title, body, ongoing))

    private fun note(body: String) = update("手机助理", body)

    /** 需要用户注意但不需要他拍板的事,比如盯着的价格变了 */
    private fun alert(title: String, body: String) {
        channel()
        nm().notify(
            NOTI_ASK,
            Notification.Builder(this, CH_ASK)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun ask(q: String) {
        channel()
        nm().notify(
            NOTI_ASK,
            Notification.Builder(this, CH_ASK)
                .setContentTitle("需要你拍板")
                .setContentText(q)
                .setStyle(Notification.BigTextStyle().bigText(q))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val TAG = "WPSvc"
        private const val CH = "agent"
        private const val CH_ASK = "agent_ask"
        private const val NOTI_ID = 1
        private const val NOTI_ASK = 2
        const val ACT_STOP = "ai.whalephone.agent.STOP"
        const val EXTRA_GOAL = "goal"
        const val KEY_DEV_DISPLAY = "DEV_DISPLAY_ID"
        const val KEY_DEMO_FEED = "DEMO_FEED"

        /** 副屏跨任务复用,所以挂在伴生对象上而不是实例上 */
        @Volatile private var shared: AgentDisplay? = null
        const val FEED_PATH = "/sdcard/Download/wp_vd.png"

        fun start(ctx: Context, goal: String) {
            ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra(EXTRA_GOAL, goal))
        }

        /** 长时任务的一轮,由 Watch 的闹钟触发 */
        fun startRound(ctx: Context, goal: String) = start(ctx, goal)
    }
}

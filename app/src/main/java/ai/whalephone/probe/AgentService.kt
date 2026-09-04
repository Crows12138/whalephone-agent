package ai.whalephone.probe

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

    private var display: AgentDisplay? = null
    private var worker: Thread? = null
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
        val probe = ProbeService.instance
        if (probe == null) { finish("无障碍服务没开,agent 没有眼睛"); return }
        if (!Privileged.connect(this)) { finish("特权桥没连上,检查 Shizuku 是否在运行并已授权"); return }
        val llm = Config.llm(this) ?: run { finish("没配 LLM_API_KEY"); return }

        val m = resources.displayMetrics
        val d = AgentDisplay.create(m.widthPixels, m.heightPixels, m.densityDpi)
            ?: run { finish("副屏创建失败"); return }
        display = d
        Log.i(TAG, "副屏 ${d.displayId} 就绪: ${d.guarantees()}")
        note("副屏 ${d.displayId} 就绪 · ${d.guarantees()}")

        val hands = Hands(probe, d.displayId, this)
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
            display?.release(); display = null
            val next = Watch.scheduleNext(this)
            if (next == null) {
                finish("盯完了。最后一次的结果:${out.message}")
            } else {
                if (changed) alert("有变化", out.message)
                update("盯着呢 · 下次 $next", out.message)
                Log.i(TAG, "本轮结束,${if (changed) "有变化" else "没变化"},下次 $next")
            }
            return
        }
        finish(out.message)
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
        display?.release(); display = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish(msg: String) {
        display?.release(); display = null
        update("任务结束", msg, ongoing = false)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() { display?.release(); super.onDestroy() }

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
        const val ACT_STOP = "ai.whalephone.probe.STOP"
        const val EXTRA_GOAL = "goal"

        fun start(ctx: Context, goal: String) {
            ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra(EXTRA_GOAL, goal))
        }

        /** 长时任务的一轮,由 Watch 的闹钟触发 */
        fun startRound(ctx: Context, goal: String) = start(ctx, goal)
    }
}

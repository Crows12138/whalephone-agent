package ai.whalephone.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    /**
     * 机主的回答从这里递给 agent 线程。容量 1 —— 同一时刻只可能有一个待答的问题。
     *
     * 用队列而不是共享变量:回答来自三个地方(界面、悬浮球、通知里的直接回复),
     * 全是别的线程;而等待方要的是「阻塞到有答案或者超时」,这正是 poll 的语义。
     */
    private val answers = java.util.concurrent.ArrayBlockingQueue<String>(1)

    /**
     * 做完一件事之后的保温期。
     *
     * 原来每个任务结束就把摊子收了(stopSelf),下一句话要重来一遍:重连特权桥、
     * 重开无障碍、进程要是被回收还得重造副屏 —— 而重造副屏是全流程里唯一
     * 必然打断机主的动作。可追加任务本来就是常态:「那第二个多少钱」「顺便看看评分」。
     *
     * 所以做完不收摊,先醒着等一会儿。这段时间里再来任务是零开销的,而且它还记得
     * 刚才做了什么。到点没人追,才真收工。
     */
    private val idle = Handler(Looper.getMainLooper())
    private val settle = Runnable { settleNow() }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AgentBus.attach(this)
        if (intent?.action == ACT_STOP) { Watch.clear(this); stop(); return START_NOT_STICKY }

        if (intent?.action == ACT_ANSWER) return takeAnswer(intent)

        if (intent?.action == ACT_TAKEOVER) return takeover()

        val goal = intent?.getStringExtra(EXTRA_GOAL).orEmpty()
        if (goal.isBlank()) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTI_ID, notify("准备中", goal))
        if (worker?.isAlive == true) { note("已经有任务在跑了"); return START_STICKY }

        AgentBus.post(AgentBus.Kind.GOAL, goal)
        AgentBus.setRunning(true)
        stopping = false
        Conflict.clearHandover()       // 上一轮可能是被接管掐掉的,闸门不复位新任务一步都走不了
        idle.removeCallbacks(settle)   // 保温期里追进来的任务:摊子还在,直接开工
        worker = thread(name = "wp-agent") { runTask(goal) }
        return START_STICKY
    }

    /**
     * 收下机主的回答。
     *
     * 先 startForeground 再判断:这个 intent 是用 startForegroundService 送进来的
     * (通知里的直接回复、悬浮球都可能在 app 不在前台时触发),不在几秒内转前台
     * 系统会直接判死。
     */
    private fun takeAnswer(intent: Intent): Int {
        startForeground(NOTI_ID, notify("手机助理", "收到你的回答"))
        val text = (RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
            ?: intent.getStringExtra(EXTRA_TEXT).orEmpty()).trim()
        if (text.isNotEmpty() && worker?.isAlive == true && answers.offer(text)) return START_STICKY
        // 没有在等的任务了(答得太晚,或者任务已经收工)。不把它当成新任务 ——
        // 「是」「第二个」这种回答单独拿出来当目标是没有意义的,反而会真的去做点什么。
        if (text.isNotEmpty()) note("这个问题已经过期了,没人在等这句回答")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * 机主接管:把副屏上这个任务搬到他眼前,agent 就地停手。
     *
     * **顺序不能反。** stop() 里会销毁副屏,而副屏一销毁,上面的 task 跟着一起消失
     * (那是刻意的,见 stop 的说明)—— 先停再搬就没东西可搬了。
     *
     * **搬不动就绝不停 agent。** 那样机主两头落空:界面没到眼前,任务还断了。
     * 所以这里唯一的判据是 moveTopTaskToMain 的返回值,不是「我发出去了」。
     *
     * 搬过去之后不做恢复:任务停在第几步、做到哪一屏,都留在对话记录里给机主看。
     * 「接管完再还回去接着跑」是另一件事 —— 它要求 agent 能从任意一屏重新接上,
     * 那是感知层的能力,不是这个按钮的事。
     */
    private fun takeover(): Int {
        startForeground(NOTI_ID, notify("手机助理", "正在把任务交到你手上"))
        val d = shared
        if (d == null) {
            note("副屏还没建起来,没有可接管的任务")
            stopForeground(STOP_FOREGROUND_DETACH)
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        // 先关闸、等在飞的动作落地,再搬 —— 顺序反了就会有一下点在机主屏幕上,
        // 理由写在 Conflict.beginHandover
        if (!Conflict.beginHandover())
            Log.w(TAG, "接管:等了 3 秒还有动作没落地,照搬(它多半卡住了,而卡住正是机主要接管的原因)")

        val r = Privileged.moveTopTaskToMain(d.displayId)
        if (!r.startsWith("OK")) {
            Conflict.clearHandover()   // 没搬成就把闸门重新打开,agent 接着跑
            // 两处都要说:对话记录留全文备查,取景窗上说给此刻正盯着按钮的人听
            Conflict.handoverFailed(r.substringAfter("MOVE_FAIL:").trim().ifBlank { r })
            note("接管没成功,agent 继续跑着 —— $r")
            return START_STICKY
        }
        AgentBus.post(AgentBus.Kind.NOTE, "你接管了,它停手了", r.removePrefix("OK").trim())
        stop()
        return START_NOT_STICKY
    }

    /**
     * 问一句,然后**停在这里等他回答**。
     *
     * 这是「ask 不再是任务的终点」那一改的落点:线程挂着,trace、notes、副屏、
     * 已经打开的界面全都原样留着,答案一到就接着往下做。等待期间什么都不做,
     * 不碰机主也不占他的屏。
     *
     * 但不能无限等 —— 一个永远挂着的前台服务比没有答案更糟(副屏留着、无障碍开着,
     * 机主那边微信还付不了款)。等不到就收工,并且把「等的是什么」写进结束语。
     */
    private fun waitForAnswer(q: String): String? {
        answers.clear()
        ask(q)
        AgentBus.setAsking(q)
        update("等你拍板", q)
        val a = runCatching {
            answers.poll(ASK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        AgentBus.setAsking(null)
        nm().cancel(NOTI_ASK)
        if (a == null || stopping) return null
        AgentBus.post(AgentBus.Kind.REPLY, a)
        update("接着做", a)
        return a
    }

    /**
     * 这一段对话里已经做完的事(目标 -> 结果),给模型当上下文。
     *
     * 直接从对话记录里读,不再单独存一份:那两份迟早会不一致,而机主看到的就是
     * 对话记录那一份。只取最近两件 —— 再往前的,副屏上早就不是那个界面了。
     */
    private fun doneInThisThread(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var g: String? = null
        for (l in AgentBus.snapshot()) when (l.kind) {
            AgentBus.Kind.GOAL -> g = l.title
            AgentBus.Kind.RESULT, AgentBus.Kind.FAIL -> { g?.let { out += it to l.title }; g = null }
            else -> {}
        }
        return out.takeLast(2)
    }

    private fun runTask(goal: String) {
        // 顺序是有讲究的:先连特权桥,再用它去开无障碍。
        // 无障碍这个 setting 只有 shell 身份写得动,而桥就是那个身份 ——
        // 反过来先要无障碍就成了死锁:权限关着的时候,连「谁来开它」都不在了。
        Privileged.connect(this)
        if (!A11yGate.open(this)) Log.i(TAG, "没能自动打开无障碍,按机主自己的设置来")
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
            // 副屏是特权桥那个进程持有的。Shizuku 重启、或者桥被系统回收之后,
            // 屏就没了,但 app 这边的 AgentDisplay 对象还在 —— 它会拿着一个幽灵屏号
            // 继续跑,`am start --display N` 报的是 Permission Denial,看着像权限问题,
            // 其实是对象已经不在了。复用之前先问系统这块屏还在不在。
            shared?.let { old ->
                // 必须问桥,不能问自己的 DisplayManager:副屏带 FLAG_PRIVATE,属主是 shell,
                // app 进程 getDisplay(id) 永远返回 null。原来就是这么判的,于是「跨任务
                // 复用副屏」这条设计在真机上一次都没生效过 —— 每个任务都重造一块,
                // 而造屏正是全流程里唯一必须避让机主的动作,本该一辈子一次。
                if (!Privileged.displayAlive(old.displayId)) {
                    Log.w(TAG, "副屏 ${old.displayId} 已经不存在了(特权桥重启过?),重造一块")
                    runCatching { old.release() }
                    shared = null
                }
            }
            // 造屏这一下本身就会抢焦点:副屏一出现可获焦窗口,主屏就丢掉自己的焦点窗口,
            // 机主的软键盘当场被 IMMS 收起。实测过 —— 只建一块屏、什么都不启动,就够了。
            // 所以它和后面每个动作一样,要先等机主打完字。
            // (漏掉这一处的后果是整套让路白做:动作全让了,开场第一秒还是把人打断。)
            val d = shared ?: run {
                // 等到机主打完字再造。等不到就不造 —— 这是唯一一处「等超时之后还硬做」
                // 会真的打断机主的地方(造屏必然抢一次焦点,收一次键盘),
                // 而「不打扰」是硬要求,任务能不能做完不是。所以宁可这一轮不开工。
                val svc = EyesAndHands.instance
                if (svc != null) {
                    val w = Conflict.yieldWhileOwnerTypes(svc, DISPLAY_WAIT_MS)
                    if (w > 0) note("机主在打字,等了 ${w / 1000} 秒")
                    if (Conflict.ownerTyping(svc)) {
                        finish("机主一直在打字,这一轮先不开工 —— 现在造副屏会收起他的键盘。等他空下来再叫我")
                        return
                    }
                }
                // 强制竖屏。displayMetrics 反映的是**服务此刻的配置**,机主把手机横过来
                // 那一刻造屏,宽高就是反的 —— 真机上造出过一块 2340x1080 的副屏,
                // 手机 App 在上面走的是平板/DeX 布局,和机主自己看到的完全两样,
                // 模型按平板布局做的决策也就对不上。副屏是给手机 App 用的,
                // 它的形状不该由机主此刻怎么拿手机决定。
                val m = resources.displayMetrics
                val w = minOf(m.widthPixels, m.heightPixels)
                val h = maxOf(m.widthPixels, m.heightPixels)
                AgentDisplay.create(w, h, m.densityDpi)?.also { shared = it }
            } ?: run { finish("副屏创建失败"); return }
            displayId = d.displayId
            Log.i(TAG, "副屏 ${d.displayId} 就绪: ${d.guarantees()}")
            note("副屏 ${d.displayId} 就绪 · ${d.guarantees()}")
        }

        startFeedIfAsked()

        // 把送给视觉模型的那张图落盘(files/last-eyes.jpg)。视觉这条路以前是完全不可
        // 观测的:模型说「点了 [12]」,而 [12] 在图上到底标在哪、标号有没有画歪,
        // 谁都看不见。标号画歪的失败是静默的 —— 它会稳定地点错一个元素,
        // 而日志里一切正常。所以这张图必须存得下来。
        val hands = Hands(probe, displayId, this) { marks ->
            shared?.frameJpeg(marks = marks)?.also {
                if (marks.isNotEmpty()) runCatching {
                    java.io.File(filesDir, "last-eyes.jpg").writeBytes(it)
                }
            }
        }
        val agent = Agent(hands, llm, goal, history = doneInThisThread(), vlm = Config.vlm(this))
        agent.onStep = { s ->
            if (!stopping) {
                update("第 ${s.n} 步 · ${s.action}", s.result.take(80))
                AgentBus.post(AgentBus.Kind.STEP, "第 ${s.n} 步 · ${s.action}", s.result)
            }
        }
        agent.onAsk = { q -> waitForAnswer(q) }

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
                finish("盯完了。最后一次的结果:${out.message}", ok = true)
            } else {
                if (changed) alert("有变化", out.message)
                update("盯着呢 · ${Watch.statusLine(this)}", out.message)
                Log.i(TAG, "本轮结束,${if (changed) "有变化" else "没变化"}")
            }
            return
        }
        finish(out.message, ok = out.done)
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
        idle.removeCallbacks(settle)
        AgentBus.setRunning(false)
        worker?.interrupt()
        // 机主明确点了停止,长时任务也已经被 Watch.clear 清掉,权限没有留着的理由
        runCatching { A11yGate.close(this) }
        shared?.release(); shared = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 醒着等下一句。
     *
     * 无障碍在这里仍然是关掉的(见 finish),因为它开着的时候机主的微信付不了款 ——
     * 那是他手机上看得见的副作用,不能为了省一秒钟的重开而挂着几分钟。留下的是
     * 服务本身、特权桥的连接和那块副屏,追加任务时省掉的正是这几样里最贵的。
     * 想连无障碍也一起留着的,在设置里关掉「自动开关无障碍」就是。
     */
    private fun keepWarm(msg: String) {
        val ms = Config.get(this, KEY_IDLE_KEEP, IDLE_KEEP_MS.toString()).toLongOrNull()
            ?: IDLE_KEEP_MS
        if (ms <= 0L) { settleNow(); return }
        update("做完了,还醒着", "$msg\n\n随时可以再说一句;${ms / 60000} 分钟没有新任务就自己收工")
        idle.removeCallbacks(settle)
        idle.postDelayed(settle, ms)
    }

    /**
     * 真收工。副屏仍然留着(只有机主点停止才销毁),但服务不再占着前台。
     *
     * **上下文不在这里清。** 收摊只是把资源放掉,和「这段对话结束了没有」是两件事:
     * 机主可能半小时后回来接一句「那第二个呢」。清空只发生在他自己点「新任务」,
     * 见 AgentBus.newThread。
     */
    private fun settleNow() {
        idle.removeCallbacks(settle)
        Log.i(TAG, "保温到点,收摊(对话记录留着)")
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun finish(msg: String, ok: Boolean = false) {
        // 所有提前收尾的理由(机主一直在打字、副屏没造成、没有眼睛)原先在日志里
        // 一个字都不留 —— 只有正常跑完那条路径打了「结束 done=」。
        // 出问题时看到的就是「agent 没反应」,查不到它为什么没干活。收尾在这里统一记一次。
        Log.i(TAG, "收工: $msg")
        // 副屏留着复用,它上面的窗口会一直占着全局焦点,主屏就没有获焦窗口了 ——
        // 机主下一次按键正好落在「Application does not have a focused window」上。
        // 只在这里推一次,而且只在主屏确实没焦点时推。
        runCatching { Log.i(TAG, Privileged.handBackFocusIfLost()) }
        // 权限跟着任务走:干完就把无障碍关掉,机主那边才付得了款。
        // 副屏不一样,它留着 —— 关权限是为了消除一个对机主可见的副作用,
        // 留副屏是为了少一次对机主可见的抖动,两件事都朝同一个方向。
        runCatching { A11yGate.close(this) }
        // 一次性任务结束也留着副屏:用户很可能接着下一个任务,重造一次就多抖一次。
        // 真正销毁只发生在用户点「停止」,或者服务被系统回收。
        AgentBus.post(if (ok) AgentBus.Kind.RESULT else AgentBus.Kind.FAIL, msg)
        AgentBus.setRunning(false)
        keepWarm(msg)
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

    private fun note(body: String) {
        update("手机助理", body)
        AgentBus.post(AgentBus.Kind.NOTE, body)
    }

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

    /**
     * 把问题递到机主面前。三条路同时开:通知、界面里的对话流、悬浮球。
     *
     * 通知这一条带 RemoteInput —— 他可以直接在通知里把答案打进去,不用打开任何界面。
     * 这和这个 app 的立场是一致的:需要他拍板不等于有权把他从正在做的事里拽出来。
     */
    private fun ask(q: String) {
        AgentBus.post(AgentBus.Kind.ASK, q)
        channel()
        val reply = RemoteInput.Builder(KEY_REPLY).setLabel("直接回它").build()
        val pi = PendingIntent.getService(
            this, 2, Intent(this, AgentService::class.java).setAction(ACT_ANSWER),
            // 必须 MUTABLE:系统要把机主打的字塞进这个 intent 再发出来
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm().notify(
            NOTI_ASK,
            Notification.Builder(this, CH_ASK)
                .setContentTitle("需要你拍板")
                .setContentText(q)
                .setStyle(Notification.BigTextStyle().bigText(q))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                        null as android.graphics.drawable.Icon?, "回答", pi
                    ).addRemoteInput(reply).build()
                )
                .build()
        )
    }

    companion object {
        private const val TAG = "WPSvc"
        private const val CH = "agent"
        private const val CH_ASK = "agent_ask"
        private const val NOTI_ID = 1
        private const val NOTI_ASK = 2
        /** 造副屏之前最多等机主多久。比单步等待长得多:造屏一个任务只发生一次,
         *  多等一会儿换「一次都不打断」是划算的。 */
        const val DISPLAY_WAIT_MS = 180_000L
        const val ACT_STOP = "ai.whalephone.agent.STOP"
        const val ACT_ANSWER = "ai.whalephone.agent.ANSWER"
        const val ACT_TAKEOVER = "ai.whalephone.agent.TAKEOVER"
        const val EXTRA_TEXT = "text"
        /** 通知里直接回复用的 key */
        const val KEY_REPLY = "reply"
        /** 问完最多等机主多久。等不到就收工,不留一个永远挂着的前台服务 */
        const val ASK_WAIT_MS = 15 * 60_000L
        /** 做完之后醒着等多久。这段时间里追加任务是零开销的 */
        const val IDLE_KEEP_MS = 5 * 60_000L
        const val KEY_IDLE_KEEP = "IDLE_KEEP_MS"
        /** 清单里静态注册的入口用的动作,见 CommandReceiver */
        const val ACT_RUN_EXTERNAL = "ai.whalephone.agent.RUN"
        const val EXTRA_GOAL = "goal"
        const val KEY_DEV_DISPLAY = "DEV_DISPLAY_ID"
        const val KEY_DEMO_FEED = "DEMO_FEED"

        /** 副屏跨任务复用,所以挂在伴生对象上而不是实例上 */
        @Volatile private var shared: AgentDisplay? = null

        /** 取景窗要按帧读这块屏。只读,不许外面动它的生命周期。 */
        val liveDisplay: AgentDisplay? get() = shared
        const val FEED_PATH = "/sdcard/Download/wp_vd.png"

        /**
         * Android 12 起,后台进程不许起前台服务,`startForegroundService` 会直接抛
         * `ForegroundServiceStartNotAllowedException` —— **在广播接收器里抛就是崩溃**,
         * 整个进程被打死。真机上撞到过:app 退到后台、两个悬浮窗又都关着,
         * 一条 `am broadcast` 下去,app 当场崩了。
         *
         * 有前台窗口(机主正开着界面)或者已经有一个前台服务在跑(悬浮球那个服务
         * 就是前台服务)的时候不受限,所以三条真实入口 —— 界面、悬浮球、通知里回话
         * —— 本来就不会走到这里。会走到的是广播这条开发入口和长时任务的亮屏那一轮。
         *
         * 拦不住这条系统限制,但**不能让它变成崩溃**:失败就如实说一句,让人看得懂
         * 该怎么办。返回值给调用方,想提示机主的自己提示。
         */
        fun start(ctx: Context, goal: String): Boolean = launch(ctx,
            Intent(ctx, AgentService::class.java).putExtra(EXTRA_GOAL, goal))

        private fun launch(ctx: Context, i: Intent): Boolean = try {
            ctx.startForegroundService(i); true
        } catch (e: Exception) {
            Log.w(TAG, "起不来前台服务(app 在后台且没有悬浮窗时系统不允许):" +
                "打开一次界面,或者把悬浮球打开", e)
            false
        }

        /** 回答 agent 刚问的那句话。界面、悬浮球、通知里的直接回复都走这里 */
        fun answer(ctx: Context, text: String): Boolean = launch(ctx,
            Intent(ctx, AgentService::class.java)
                .setAction(ACT_ANSWER)
                .putExtra(EXTRA_TEXT, text))

        /** 机主要自己接手:把副屏上那个任务搬到主屏,agent 停手 */
        fun takeover(ctx: Context): Boolean = launch(ctx,
            Intent(ctx, AgentService::class.java).setAction(ACT_TAKEOVER))

        /** 长时任务的一轮,由 Watch 的闹钟触发 */
        fun startRound(ctx: Context, goal: String) = start(ctx, goal)
    }
}

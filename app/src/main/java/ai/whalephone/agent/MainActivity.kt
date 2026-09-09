package ai.whalephone.agent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 机主这一侧的界面。
 *
 * 它只在主屏上、只在机主自己打开的时候出现;agent 跑起来之后不会有任何窗口自己冒出来。
 * 界面做成对话流,是因为这个 app 的交互确实就是对话式的:机主说一句话,agent 分成
 * 若干步去做,每一步都该看得见。原来这些只在 logcat 和一条通知里,机主看不到过程,
 * 只能看到结果 —— 而「它到底有没有在动」是这个产品最需要回答的问题。
 *
 * 过程数据来自 AgentBus(进程内),不是广播:界面关掉再打开要能看到之前发生过什么。
 */
class MainActivity : Activity() {

    private val pal by lazy { Palette.of(this) }
    private lateinit var feedBox: LinearLayout
    private lateinit var feedScroll: ScrollView
    private lateinit var pill: TextView
    private lateinit var detail: TextView
    private lateinit var detailBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: android.widget.ImageView
    private lateinit var micBtn: android.widget.ImageView
    private lateinit var screenChip: TextView
    private lateinit var ballChip: TextView
    private lateinit var watchChip: TextView
    private var voice: Voice? = null
    private var rendered = 0

    private val onBus: (AgentBus.Line?) -> Unit = { line ->
        if (line == null) redraw() else { addLine(line); toBottom() }
        syncSendButton()
    }
    private val onOverlay: () -> Unit = { syncChips() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        AgentBus.attach(this)
        setContentView(buildRoot())
        redraw()
        refreshStatus()
    }

    // ---- 布局 ----

    private fun buildRoot(): View {
        val root = Ui.col(this).apply { setBackgroundColor(pal.bg) }
        root.addView(buildHeader(), Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(buildDetail(), Ui.lp(Ui.MATCH, Ui.WRAP))

        feedBox = Ui.col(this).apply {
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 8f))
        }
        feedScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(feedBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        root.addView(feedScroll, Ui.lp(Ui.MATCH, 0, 1f))
        root.addView(buildChips(), Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(buildInputBar(), Ui.lp(Ui.MATCH, Ui.WRAP))
        Ui.insets(root)
        return root
    }

    private fun buildHeader(): View {
        val pad = Ui.dp(this, 14f)
        pill = Ui.text(this, "检查中", 11f, pal.textSub).apply {
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f))
            background = Ui.tappable(Ui.round(pal.surfaceAlt, Ui.dp(this@MainActivity, 12f)), pal.ripple)
            setOnClickListener { detailBox.visibility = if (detailBox.isShown) View.GONE else View.VISIBLE }
        }
        // 唯一一个清空上下文的地方。默认什么都不清 —— 机主随时可能追一句,
        // 「它还记不记得刚才那件事」该由他说了算
        val fresh = Ui.text(this, "新任务", 12f, pal.textSub).apply {
            setPadding(Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f),
                Ui.dp(this@MainActivity, 10f), Ui.dp(this@MainActivity, 4f))
            background = Ui.tappable(Ui.round(pal.surfaceAlt, Ui.dp(this@MainActivity, 12f)), pal.ripple)
            setOnClickListener {
                if (AgentBus.running) { toast("先让它把手上这件事做完"); return@setOnClickListener }
                AgentBus.newThread()
                input.setText("")
            }
        }
        val gear = Ui.text(this, "⚙", 18f, pal.textSub).apply {
            setPadding(Ui.dp(this@MainActivity, 10f), 0, 0, 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        return Ui.row(this).apply {
            setPadding(pad, Ui.dp(this@MainActivity, 10f), pad, Ui.dp(this@MainActivity, 10f))
            setBackgroundColor(pal.bg)
            addView(Ui.text(this@MainActivity, "手机助理", 19f, pal.textMain, bold = true))
            addView(View(this@MainActivity), Ui.lp(0, 1, 1f))
            addView(fresh, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
                marginEnd = Ui.dp(this@MainActivity, 6f)
            })
            addView(pill)
            addView(gear)
        }
    }

    /** 状态明细默认收起 —— 一切正常时它是噪音,出问题时点一下药丸就能展开。 */
    private fun buildDetail(): View {
        val pad = Ui.dp(this, 14f)
        detail = Ui.text(this, "", 12f, pal.textSub).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val shizuku = chip("打开 Shizuku") { onShizuku() }
        detailBox = Ui.col(this).apply {
            visibility = View.GONE
            setPadding(pad, 0, pad, Ui.dp(this@MainActivity, 10f))
            addView(Ui.col(this@MainActivity).apply {
                background = Ui.round(pal.surface, Ui.dp(this@MainActivity, 12f), pal.line, Ui.dp(this@MainActivity, 1f))
                setPadding(pad, pad, pad, pad)
                addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP))
                addView(Ui.row(this@MainActivity).apply {
                    setPadding(0, Ui.dp(this@MainActivity, 10f), 0, 0)
                    addView(shizuku)
                }, Ui.lp(Ui.MATCH, Ui.WRAP))
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        return detailBox
    }

    private fun buildChips(): View {
        screenChip = chip("看副屏") { toggleOverlay(screen = true) }
        ballChip = chip("悬浮球") { toggleOverlay(screen = false) }
        watchChip = chip("盯着") { watchDialog() }
        return Ui.row(this).apply {
            setPadding(Ui.dp(this@MainActivity, 14f), 0, Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 6f))
            addView(screenChip); addView(space()); addView(ballChip); addView(space()); addView(watchChip)
        }
    }

    private fun buildInputBar(): View {
        val pad = Ui.dp(this, 10f)
        micBtn = Ui.iconBtn(this, R.drawable.ic_mic, pal.accent, pal.surfaceAlt, pal.ripple)
            .apply { setOnClickListener { toggleVoice() } }
        input = EditText(this).apply {
            hint = "说一句你要它做什么"
            setHintTextColor(pal.textSub)
            setTextColor(pal.textMain)
            textSize = 15f
            background = null
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { onSendOrStop(); true } else false
            }
        }
        sendBtn = Ui.iconBtn(this, R.drawable.ic_send, pal.onAccent, pal.accent, pal.ripple, padDp = 10f)
            .apply { setOnClickListener { onSendOrStop() } }
        val bar = Ui.row(this).apply {
            setPadding(pad, Ui.dp(this@MainActivity, 6f), pad, Ui.dp(this@MainActivity, 6f))
            background = Ui.round(pal.surface, Ui.dp(this@MainActivity, 24f), pal.line, Ui.dp(this@MainActivity, 1f))
            addView(micBtn, Ui.lp(Ui.dp(this@MainActivity, 38f), Ui.dp(this@MainActivity, 38f)))
            addView(input, Ui.lp(0, Ui.WRAP, 1f).apply {
                marginStart = Ui.dp(this@MainActivity, 8f); marginEnd = Ui.dp(this@MainActivity, 8f)
            })
            addView(sendBtn, Ui.lp(Ui.dp(this@MainActivity, 38f), Ui.dp(this@MainActivity, 38f)))
        }
        return Ui.col(this).apply {
            setPadding(Ui.dp(this@MainActivity, 12f), 0, Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 10f))
            addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
    }

    private fun chip(t: String, onClick: () -> Unit) = Ui.text(this, t, 12f, pal.textSub).apply {
        setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 6f),
            Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 6f))
        background = Ui.tappable(
            Ui.round(pal.surface, Ui.dp(this@MainActivity, 14f), pal.line, Ui.dp(this@MainActivity, 1f)), pal.ripple)
        setOnClickListener { onClick() }
    }

    private fun space() = View(this).also { it.layoutParams = Ui.lp(Ui.dp(this, 8f), 1) }

    // ---- 对话流 ----

    private fun redraw() {
        feedBox.removeAllViews()
        rendered = 0
        val all = AgentBus.snapshot()
        if (all.isEmpty()) feedBox.addView(welcome(), Ui.lp(Ui.MATCH, Ui.WRAP))
        all.forEach { addLine(it) }
        toBottom()
    }

    private fun welcome(): View {
        val box = Ui.col(this).apply {
            background = Ui.round(pal.surface, Ui.dp(this@MainActivity, 16f), pal.line, Ui.dp(this@MainActivity, 1f))
            setPadding(Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 16f),
                Ui.dp(this@MainActivity, 16f), Ui.dp(this@MainActivity, 16f))
        }
        box.addView(Ui.text(this, "它在另一块屏上替你干活", 15f, pal.textMain, bold = true))
        box.addView(Ui.text(this,
            "任务跑在一块你看不见的副屏上,你这块屏该刷什么刷什么 —— 焦点、键盘、剪贴板都不会被动。\n想看它在做什么,点上面的「看副屏」。",
            13f, pal.textSub).apply { setPadding(0, Ui.dp(this@MainActivity, 8f), 0, Ui.dp(this@MainActivity, 12f)) })
        listOf(
            // 例子按**主人平时说话的样子**写:说清要什么、不要什么,不写步骤。
            // 写成「打开淘宝 → 搜索 → 点第一个」只会教人把 agent 当遥控器用,
            // 而它能做的是比完再挑;第一句摆在最前,因为加购是真的改变外部状态的那种。
            "淘宝上找个 AirPods Pro 2,别要二手和资源机,挑个靠谱的加进购物车",
            "美团看看附近的川菜馆,人均 80 以内评分最高的是哪家",
            "日历里看看我明天几点有事,中间有没有空出两个钟头",
        ).forEach { ex ->
            box.addView(Ui.text(this, ex, 13f, pal.accent).apply {
                setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 9f),
                    Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 9f))
                background = Ui.tappable(Ui.round(pal.surfaceAlt, Ui.dp(this@MainActivity, 12f)), pal.ripple)
                setOnClickListener { input.setText(ex); input.setSelection(ex.length) }
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(this@MainActivity, 6f) })
        }
        return box
    }

    private fun addLine(l: AgentBus.Line) {
        if (rendered == 0) feedBox.removeAllViews()
        rendered++
        val r = Ui.dp(this, 16f)
        val v: View = when (l.kind) {
            AgentBus.Kind.GOAL, AgentBus.Kind.REPLY -> Ui.text(this, l.title, 14f, pal.onAccent).apply {
                background = Ui.bubble(pal.accent, r, mine = true)
                setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                    Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f))
            }
            AgentBus.Kind.STEP -> stepView(l)
            AgentBus.Kind.NOTE -> Ui.text(this, l.title, 12f, pal.textSub).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@MainActivity, 4f), 0, Ui.dp(this@MainActivity, 4f))
            }
            AgentBus.Kind.ASK -> card(l.title, "需要你拍板", pal.warn)
            AgentBus.Kind.RESULT -> card(l.title, "完成", pal.ok)
            AgentBus.Kind.FAIL -> card(l.title, "没做成", pal.bad)
        }
        val lp = Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            topMargin = Ui.dp(this@MainActivity, 6f)
            if (l.kind == AgentBus.Kind.GOAL || l.kind == AgentBus.Kind.REPLY) {
                gravity = Gravity.END
                marginStart = Ui.dp(this@MainActivity, 48f)
            } else {
                width = Ui.MATCH
            }
        }
        feedBox.addView(v, lp)
    }

    /** 步骤行做得比气泡轻:一轮任务有十几步,每一步都做成卡片会把结果淹掉 */
    private fun stepView(l: AgentBus.Line): View {
        val box = Ui.col(this).apply {
            background = Ui.round(pal.surface, Ui.dp(this@MainActivity, 12f))
            setPadding(Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f),
                Ui.dp(this@MainActivity, 12f), Ui.dp(this@MainActivity, 8f))
        }
        box.addView(Ui.text(this, l.title, 12f, pal.accent, bold = true))
        if (l.body.isNotBlank())
            box.addView(Ui.text(this, l.body, 13f, pal.textMain).apply {
                setPadding(0, Ui.dp(this@MainActivity, 2f), 0, 0)
            })
        return box
    }

    private fun card(body: String, tag: String, color: Int): View {
        val box = Ui.col(this).apply {
            background = Ui.round(pal.surface, Ui.dp(this@MainActivity, 14f), color, Ui.dp(this@MainActivity, 1f))
            setPadding(Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 10f),
                Ui.dp(this@MainActivity, 14f), Ui.dp(this@MainActivity, 12f))
        }
        box.addView(Ui.text(this, tag, 11f, color, bold = true))
        box.addView(Ui.text(this, body, 14f, pal.textMain).apply {
            setTextIsSelectable(true)
            setPadding(0, Ui.dp(this@MainActivity, 4f), 0, 0)
        })
        return box
    }

    private fun toBottom() = feedScroll.post { feedScroll.fullScroll(View.FOCUS_DOWN) }

    // ---- 动作 ----

    private fun onSendOrStop() {
        // 它在等回答的时候,这个框的意思是「回答它」,不是「下新任务」,
        // 也不是「停止」—— 任务没结束,只是挂着
        AgentBus.asking?.let {
            val a = input.text.toString().trim()
            if (a.isBlank()) { toast("写一句回答它"); return }
            input.setText("")
            AgentService.answer(this, a)
            return
        }
        if (AgentBus.running) {
            startService(Intent(this, AgentService::class.java).setAction(AgentService.ACT_STOP))
            return
        }
        val g = input.text.toString().trim()
        if (g.isBlank()) { toast("先写任务"); return }
        if (Config.get(this, Config.KEY_API_KEY).isBlank()) {
            toast("还没配 LLM 密钥"); startActivity(Intent(this, SettingsActivity::class.java)); return
        }
        val n = Config.get(this, KEY_ROUNDS).toIntOrNull() ?: 0
        if (n > 1) {
            val iv = Config.get(this, KEY_EVERY).toIntOrNull() ?: Watch.MIN_GAP_MIN
            Watch.start(this, g, n, iv)
        } else Watch.clear(this)
        input.setText("")
        AgentService.start(this, g)
    }

    private fun syncSendButton() {
        val q = AgentBus.asking
        val stop = AgentBus.running && q == null
        Ui.repaintIcon(sendBtn, if (stop) R.drawable.ic_stop else R.drawable.ic_send,
            pal.onAccent, if (stop) pal.bad else if (q != null) pal.warn else pal.accent, pal.ripple)
        if (voice == null) input.hint = hint()
    }

    /** 输入框的提示语只有一个出处 —— 它同时被「在等回答」和「正在听」改写 */
    private fun hint() = AgentBus.asking?.let { "回答它:$it" } ?: "说一句你要它做什么"

    private fun toggleOverlay(screen: Boolean) {
        if (!OverlayService.granted(this)) {
            AlertDialog.Builder(this)
                .setTitle("要悬浮窗权限")
                .setMessage("副屏取景窗和悬浮球都要「显示在其他应用上层」这个权限。\n只有你能在系统设置里给它,app 自己拿不到。")
                .setPositiveButton("去给") { _, _ -> OverlayService.requestPermission(this) }
                .setNegativeButton("算了", null)
                .show()
            return
        }
        if (screen) OverlayService.setScreen(this, !OverlayService.screenOn)
        else OverlayService.setBall(this, !OverlayService.ballOn)
    }

    private fun syncChips() {
        fun paint(v: TextView, on: Boolean, label: String) {
            v.text = if (on) "$label ·开" else label
            v.setTextColor(if (on) pal.accent else pal.textSub)
            v.background = Ui.tappable(
                Ui.round(if (on) pal.surfaceAlt else pal.surface, Ui.dp(this, 14f),
                    if (on) pal.accent else pal.line, Ui.dp(this, 1f)), pal.ripple)
        }
        paint(screenChip, OverlayService.screenOn, "看副屏")
        paint(ballChip, OverlayService.ballOn, "悬浮球")
        val n = Config.get(this, KEY_ROUNDS).toIntOrNull() ?: 0
        paint(watchChip, n > 1, if (n > 1) "盯 $n 轮" else "盯着")
    }

    private fun watchDialog() {
        val pad = Ui.dp(this, 20f)
        val rounds = EditText(this).apply {
            hint = "盯几轮(留空 = 只跑一次)"; setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Config.get(this@MainActivity, KEY_ROUNDS))
        }
        val every = EditText(this).apply {
            hint = "两轮至少隔几分钟(最少 ${Watch.MIN_GAP_MIN})"; setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Config.get(this@MainActivity, KEY_EVERY))
        }
        AlertDialog.Builder(this)
            .setTitle("长时任务")
            .setMessage("每次亮屏检查一轮,结果有变化才提醒你。")
            .setView(Ui.col(this).apply {
                setPadding(pad, Ui.dp(this@MainActivity, 8f), pad, 0)
                addView(rounds); addView(every)
            })
            .setPositiveButton("好") { _, _ ->
                Config.set(this, KEY_ROUNDS, rounds.text.toString().trim())
                Config.set(this, KEY_EVERY, every.text.toString().trim())
                syncChips()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleVoice() {
        if (voice != null) { stopVoice(); return }
        if (!Voice.available(this)) { toast("这台机器上没有可用的语音识别"); return }
        if (!Voice.micGranted(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC); return
        }
        Ui.repaintIcon(micBtn, null, pal.onAccent, pal.bad, pal.ripple)
        input.hint = "正在打开麦克风…"
        voice = Voice(this,
            onPartial = { t -> input.setText(t); input.setSelection(input.text.length) },
            onFinal = { t ->
                stopVoice()
                if (t.isBlank()) toast("没听清,再说一次")
                else { input.setText(t); input.setSelection(t.length) }
            },
            onError = { m -> stopVoice(); toast(m) },
            onReady = { input.hint = "在听…" },
        ).also { it.start() }
    }

    private fun stopVoice() {
        voice?.stop(); voice = null
        Ui.repaintIcon(micBtn, null, pal.accent, pal.surfaceAlt, pal.ripple)
        input.hint = hint()
    }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(req, p, r)
        if (req == REQ_MIC && r.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
            toggleVoice()
    }

    // ---- 状态 ----

    override fun onStart() {
        super.onStart()
        AgentBus.subscribe(onBus)
        OverlayService.onStateChanged(onOverlay)
    }

    override fun onStop() {
        super.onStop()
        AgentBus.unsubscribe(onBus)
        OverlayService.offStateChanged(onOverlay)
        stopVoice()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus(); syncChips(); syncSendButton()
    }

    /**
     * Shizuku 的服务必须由 shell(uid 2000)或 root 拉起,没有任何 app 能替他启动 ——
     * 那是它的安全边界。所以这里不去「自动启动」,只把机主送到能启动的地方。
     */
    private fun onShizuku() {
        if (Privileged.shizukuAlive()) { Privileged.requestPermission(); refreshStatus(); return }
        val i = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (i == null) { toast("没装 Shizuku,先装它"); return }
        startActivity(i)
    }

    private fun refreshStatus() {
        val a11y = EyesAndHands.instance != null
        val alive = Privileged.shizukuAlive()
        val granted = Privileged.shizukuGranted()
        // 无障碍平时本来就是关的 —— A11yGate 开工时自己开、收工自己关,这样机主不干活的
        // 时候不会因为挂着一个能点击的无障碍服务而付不了微信。把这个正常静息状态报成
        // 「未就绪」会让他去找哪里坏了,而实际上没有任何事要他处理。
        val autoA11y = Config.get(this, A11yGate.KEY_AUTO, "1") != "0" && Privileged.ready
        val allGood = Privileged.ready && (a11y || autoA11y)
        pill.text = if (allGood) "就绪" else if (alive) "还差一步" else "未就绪"
        pill.setTextColor(if (allGood) pal.ok else pal.warn)
        detail.text = buildString {
            appendLine("无障碍(眼睛和手)  " + when {
                a11y -> "已就绪"; autoA11y -> "开工时自动开(不常驻)"; else -> "未就绪"
            })
            appendLine("Shizuku 在运行      ${tick(alive)}")
            appendLine("Shizuku 已授权      ${tick(granted)}")
            append("特权桥(造副屏)     ${tick(Privileged.ready)}")
            if (!alive) append("\n\nShizuku 每次重启手机都要重开:进它的 app,用「通过无线调试启动」")
            if (!a11y && !autoA11y)
                append("\n手动开无障碍:设置 → 辅助功能 → 已安装的应用程序 → WhalePhone Agent")
        }
        if (alive && granted && !Privileged.ready) {
            thread { Privileged.connect(this); runOnUiThread { refreshStatus() } }
        }
    }

    private fun tick(b: Boolean) = if (b) "已就绪" else "未就绪"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val REQ_MIC = 42
        const val KEY_ROUNDS = "WATCH_ROUNDS"
        const val KEY_EVERY = "WATCH_EVERY_MIN"
    }
}

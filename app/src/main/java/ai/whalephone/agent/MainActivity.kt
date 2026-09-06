package ai.whalephone.agent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 配置和启停。这个界面只在主屏上,由用户主动打开 ——
 * agent 跑起来之后不再有任何 UI 出现在主屏,进度只走通知。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var scroller: ScrollView
    private lateinit var goal: EditText
    private lateinit var shizukuBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            // 不这么写的话,输入框会在创建时自动拿走焦点,ScrollView 跟着滚下去,
            // 顶上的状态面板被顶出可视区 —— 而那正是第一眼要看的东西。
            isFocusableInTouchMode = true
        }

        fun label(t: String) = TextView(this).apply {
            text = t; setPadding(0, pad / 2, 0, pad / 6); setTextColor(Color.GRAY); textSize = 12f
        }

        fun field(key: String, hint: String, def: String = "", password: Boolean = false) =
            EditText(this).apply {
                setText(Config.get(this@MainActivity, key, def))
                this.hint = hint
                setSingleLine()
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setOnFocusChangeListener { _, has ->
                    if (!has) Config.set(this@MainActivity, key, text.toString().trim())
                }
            }

        root.addView(Button(this).apply {
            text = "刷新状态"
            setOnClickListener { refresh() }
        })
        shizukuBtn = Button(this).apply {
            text = "授权 Shizuku"
            setOnClickListener { onShizuku() }
        }
        root.addView(shizukuBtn)
        root.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        // 这个 Intent 只到得了无障碍首页,而本 app 的服务在二级页里,机主找不到。
        // 想直接跳到那一项要用 ACTION_ACCESSIBILITY_DETAILS_SETTINGS —— 它需要
        // OPEN_ACCESSIBILITY_DETAILS_SETTINGS,实测这台机器上是 signature|installer,
        // 普通 app 拿不到。二级页本身在这台 ROM 上也没有独立 Activity(是首页里的
        // 一个 fragment),深链只能靠 ROM 私有参数,换台机器就断。所以老老实实写路径。
        root.addView(label("手动开的话:设置 → 辅助功能 → 已安装的应用程序 → WhalePhone Agent"))

        root.addView(label("LLM 接口(OpenAI 兼容)"))
        val base = field(Config.KEY_BASE_URL, "https://api.deepseek.com/v1", "https://api.deepseek.com/v1")
        val key = field(Config.KEY_API_KEY, "sk-...", password = true)
        val model = field(Config.KEY_MODEL, "deepseek-chat", "deepseek-chat")
        root.addView(base); root.addView(key); root.addView(model)

        root.addView(label("任务"))
        goal = EditText(this).apply {
            hint = "用一句话说你要它做什么"
            setText("打开淘宝,搜索「AirPods Pro 2」,看看第一个商品多少钱,告诉我价格")
            minLines = 2
        }
        root.addView(goal)

        root.addView(label("长时任务(留空 = 只跑一次;每次亮屏检查一轮)"))
        val rounds = EditText(this).apply { hint = "盯几轮"; setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER }
        val every = EditText(this).apply { hint = "两轮至少隔几分钟(最少 ${Watch.MIN_GAP_MIN})"
            setSingleLine(); inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(rounds); root.addView(every)

        root.addView(Button(this).apply {
            text = "在副屏上开始"
            setOnClickListener {
                listOf(base to Config.KEY_BASE_URL, key to Config.KEY_API_KEY, model to Config.KEY_MODEL)
                    .forEach { (v, k) -> Config.set(this@MainActivity, k, v.text.toString().trim()) }
                val g = goal.text.toString().trim()
                if (g.isBlank()) { toast("先写任务"); return@setOnClickListener }
                val n = rounds.text.toString().toIntOrNull() ?: 0
                if (n > 1) {
                    val iv = (every.text.toString().toIntOrNull() ?: Watch.MIN_GAP_MIN)
                    Watch.start(this@MainActivity, g, n, iv)
                    toast("开始盯:$n 轮,亮屏时检查,间隔  ${maxOf(iv, Watch.MIN_GAP_MIN)} 分钟起")
                } else {
                    Watch.clear(this@MainActivity)
                    toast("已启动,进度看通知")
                }
                AgentService.start(this@MainActivity, g)
            }
        })
        root.addView(Button(this).apply {
            text = "停止"
            setOnClickListener {
                startService(Intent(this@MainActivity, AgentService::class.java).setAction(AgentService.ACT_STOP))
            }
        })

        // 状态面板钉在滚动区外面。它是这个界面最该被第一眼看到的东西
        // (四项里少任何一项 agent 都跑不起来),放进 ScrollView 里就会被
        // 下面的输入框挤走 —— 试过让它滚回顶部,不如从结构上不让它滚。
        status = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(pad, pad, pad, pad / 2)
        }

        scroller = ScrollView(this).apply {
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })
        }
        setContentView(container)
        applyInsets(container)
        root.requestFocus()
        refresh()
    }

    /**
     * targetSdk 35 起系统强制 edge-to-edge,内容直接画到状态栏和标题栏底下,
     * 不再自动留白。表现是界面顶部几行被盖住 —— 这里正好盖住的是状态面板,
     * 而那是唯一告诉用户「还差哪一步」的地方。
     *
     * 用平台自带的 insets API 自己补,不为这一处引 androidx。
     */
    private fun applyInsets(v: android.view.View) {
        val bar = android.util.TypedValue().let { tv ->
            if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true))
                android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
            else 0
        }
        v.setOnApplyWindowInsetsListener { view, insets ->
            val sys = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
            )
            view.setPadding(sys.left, sys.top + bar, sys.right, sys.bottom)
            insets
        }
        v.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Shizuku 没在运行的时候,「授权」这个按钮是点不动的:权限请求要发给 Shizuku 的
     * 服务,服务都没起来,点下去只会静默失败 —— 机主看到的是「按了没反应」。
     *
     * 而**没有任何 app 能替他启动那个服务**。它必须由 shell(uid 2000)或 root 拉起;
     * 普通 app 能拉起它的话,Shizuku 这套东西就没有意义了。所以这里不去「自动启动」,
     * 只把他送到能启动的地方:打开 Shizuku,在里面用「通过无线调试启动」自己拉起
     * (Android 11 起支持,不需要电脑)。
     */
    private fun onShizuku() {
        if (Privileged.shizukuAlive()) { Privileged.requestPermission(); refresh(); return }
        val i = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (i == null) { toast("没装 Shizuku,先装它"); return }
        startActivity(i)
    }

    private fun refresh() {
        val a11y = EyesAndHands.instance != null
        val alive = Privileged.shizukuAlive()
        val granted = Privileged.shizukuGranted()
        // 无障碍平时**本来就是关的** —— A11yGate 在开工时自己开、收工自己关,
        // 这样机主不干活的时候不会因为挂着一个能点击的无障碍服务而付不了微信。
        // 所以这一行显示「未就绪」是在把正常静息状态报成故障:机主会去找哪里坏了,
        // 而实际上没有任何东西要他处理。能自动开的时候就得这么说。
        val autoA11y = Config.get(this, A11yGate.KEY_AUTO, "1") != "0" && Privileged.ready
        status.text = buildString {
            appendLine("无障碍服务(眼睛和手)  " + when {
                a11y -> "已就绪"
                autoA11y -> "开工时自动开(不常驻)"
                else -> "未就绪"
            })
            appendLine("Shizuku 在运行          ${tick(alive)}")
            appendLine("Shizuku 已授权          ${tick(granted)}")
            appendLine("特权桥(造副屏/按键)   ${tick(Privileged.ready)}")
            // 「未就绪」本身不告诉他该做什么。Shizuku 的服务每次重启手机都会没,
            // 这是 Shizuku 的性质不是这个 app 的毛病 —— 但不说出来,机主只会看到
            // 一个点不动的授权按钮。
            if (!alive) appendLine("Shizuku 每次重启手机都要重开一次:点下面那个按钮进去,用「通过无线调试启动」")
        }
        shizukuBtn.text = if (alive) "授权 Shizuku" else "打开 Shizuku 去启动它"
        if (alive && granted && !Privileged.ready) {
            thread { Privileged.connect(this); runOnUiThread { refresh() } }
        }
    }

    private fun tick(b: Boolean) = if (b) "已就绪" else "未就绪"

    private companion object { const val SHIZUKU_PKG = "moe.shizuku.privileged.api" }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package ai.whalephone.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 设置。从主界面右上角的齿轮进来。
 *
 * 主界面只留「说一句话」这一件事,配置全挪到这里 —— 原来它们混在一屏里,
 * 机主每次下任务都要从五个输入框中间找那个任务框。
 */
class SettingsActivity : Activity() {

    private val pal by lazy { Palette.of(this) }
    private val fields = mutableListOf<Pair<String, EditText>>()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        val pad = Ui.dp(this, 16f)

        val col = Ui.col(this).apply { setPadding(pad, pad, pad, pad) }

        // 返回箭头。原来这一页只能靠系统手势退出 —— 一个页面不该只有看不见的出口,
        // 何况这台机器上手势条被 DeX 任务栏挡了一半。
        val back = Ui.text(this, "←", 22f, pal.textMain).apply {
            val h = Ui.dp(this@SettingsActivity, 8f)
            setPadding(0, h, Ui.dp(this@SettingsActivity, 14f), h)
            setOnClickListener { finish() }
        }
        col.addView(Ui.row(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(back)
            addView(Ui.text(this@SettingsActivity, "设置", 20f, pal.textMain, bold = true))
        })

        section(col, "模型接口(OpenAI 兼容)")
        col.addView(card(
            *labeled("base_url", field(Config.KEY_BASE_URL, "https://api.deepseek.com/v1", "https://api.deepseek.com/v1")),
            *labeled("密钥", field(Config.KEY_API_KEY, "sk-...", password = true)),
            *labeled("模型名", field(Config.KEY_MODEL, "deepseek-chat", "deepseek-chat")),
            note("DeepSeek / Kimi / 智谱 / OpenRouter / 自建 vLLM 是同一套协议,换 base_url 和 model 即可。密钥只存在这台手机上。"),
        ))

        section(col, "看图那条路(可选)")
        col.addView(card(
            *labeled("模型名(留空=不开这条路)", field(Config.KEY_VLM_MODEL, "比如 glm-4v / qwen-vl-max")),
            *labeled("base_url(留空=沿用上面那个)", field(Config.KEY_VLM_BASE_URL, "留空即可")),
            *labeled("密钥(留空=沿用上面那个)", field(Config.KEY_VLM_API_KEY, "留空即可", password = true)),
            note("有些 App 的页面整页是自绘的(淘宝的商品规格弹层、购物车就是)," +
                "对系统的无障碍接口一个带文字的元素都读不出来 —— 纯文本模型在那种页面上是瞎的。" +
                "填了视觉模型之后,遇到这种页面它会改看副屏截图、按坐标操作,其余时候仍然走文本(更快更准也更便宜)。" +
                "DeepSeek 没有视觉模型,所以这里通常要填另一家。"),
        ))

        section(col, "让路:键盘还开着但没动静,等多久算你停手了")
        col.addView(card(
            *labeled("你一个键都没按过(毫秒)", field("TYPING_IDLE_MS", "10000", "10000", number = true)),
            *labeled("你按过键(毫秒)", field("DRAFT_IDLE_MS", "10000", "10000", number = true)),
            note("按过键单列一档,是因为那时你手里可能攥着一串没上屏的拼音,而「正在组词」这个状态从系统外面读不到。调大它等于给选词留更多时间,代价是你打完字放下手机时它多等同样久。"),
        ))

        section(col, "做完一件事之后")
        col.addView(card(
            *labeled("醒着等多久再收工(毫秒,0=做完就收)",
                field(AgentService.KEY_IDLE_KEEP, "300000",
                    AgentService.IDLE_KEEP_MS.toString(), number = true)),
            note("追加任务是常态。醒着的这几分钟里再说一句,省掉重连特权桥、重开无障碍、" +
                "以及可能的重造副屏 —— 造副屏是唯一一个必然会打断你的动作。它还记得刚做过什么," +
                "所以「那第二个多少钱」这种话接得上。无障碍在这期间仍然是关的(不然微信付不了款)," +
                "想连它也留着就把下面的自动开关关掉。"),
        ))

        section(col, "悬浮窗")
        val permLine = note("")
        col.addView(card(
            actionRow("给「显示在其他应用上层」权限") { OverlayService.requestPermission(this) },
            actionRow("给录音权限(悬浮球语音输入要用)") {
                if (Voice.micGranted(this)) toast("已经给过了")
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
            },
            permLine,
        ))

        section(col, "语音识别")
        col.addView(card(
            actionRow("打开系统的语音输入设置") { openVoiceSettings() },
            note("识别准不准、能不能用,都由设备上装的引擎决定,不由这个 app 的参数决定。" +
                "换引擎要在系统设置里换 —— app 自己绕过去直接绑服务反而更糟(实测硬失败)," +
                "所以这里只给一个入口,不做第二份设置。"),
        ))
        this.permNote = permLine

        section(col, "演示")
        col.addView(card(
            switchRow("把副屏画面写成 PNG(给电脑端取景用)", AgentService.KEY_DEMO_FEED),
            switchRow("不自动开关无障碍,由我自己管", A11yGate.KEY_AUTO, invert = true),
            note("副屏画面会写到 ${AgentService.FEED_PATH}。平时不用开,录演示视频才用。"),
        ))

        val root = ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            addView(col, android.widget.LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        Ui.insets(root)
        setContentView(root)
    }

    private var permNote: TextView? = null

    /** 系统自带的「语音输入」设置。不是每台机器都有这一页,没有就说清楚 */
    private fun openVoiceSettings() {
        val i = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
        if (i.resolveActivity(packageManager) != null) startActivity(i)
        else toast("这台机器没有这一页,到「设置 - 通用管理 - 语音输入」里找")
    }

    override fun onResume() {
        super.onResume()
        permNote?.text = buildString {
            append("悬浮窗权限:").append(if (OverlayService.granted(this@SettingsActivity)) "已给" else "还没给")
            append("\n录音权限:").append(if (Voice.micGranted(this@SettingsActivity)) "已给" else "还没给")
            append("\n语音识别:").append(if (Voice.available(this@SettingsActivity)) "这台机器上可用" else "这台机器上没有")
        }
    }

    /**
     * 输入框上面加一行小字。
     *
     * 不能只靠 hint:hint 在框里有内容时就不显示了,而「让路」那两个框默认值一样,
     * 填着值的时候屏幕上就是两个一模一样的 10000,谁也分不出哪个是哪个。
     */
    private fun labeled(label: String, f: EditText): Array<View> = arrayOf(
        Ui.text(this, label, 11f, pal.textSub).apply {
            setPadding(0, Ui.dp(this@SettingsActivity, 10f), 0, 0)
        },
        f,
    )

    /** 失焦即存。没有「保存」按钮 —— 有保存按钮就一定有人忘记点。 */
    private fun field(key: String, hint: String, def: String = "",
                      password: Boolean = false, number: Boolean = false) =
        EditText(this).apply {
            setText(Config.get(this@SettingsActivity, key, def))
            this.hint = hint
            setHintTextColor(pal.textSub)
            setTextColor(pal.textMain)
            textSize = 14f
            setSingleLine()
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, has ->
                if (!has) Config.set(this@SettingsActivity, key, text.toString().trim())
            }
            fields += key to this
        }

    override fun onPause() {
        super.onPause()
        // 失焦存不够:机主直接按返回时最后一个框还握着焦点,那一改就丢了
        fields.forEach { (k, v) -> Config.set(this, k, v.text.toString().trim()) }
    }

    private fun section(parent: android.widget.LinearLayout, t: String) {
        parent.addView(Ui.text(this, t, 12f, pal.textSub, bold = true).apply {
            setPadding(Ui.dp(this@SettingsActivity, 4f), Ui.dp(this@SettingsActivity, 18f),
                0, Ui.dp(this@SettingsActivity, 6f))
        })
    }

    private fun card(vararg views: View): View = Ui.col(this).apply {
        background = Ui.round(pal.surface, Ui.dp(this@SettingsActivity, 14f), pal.line,
            Ui.dp(this@SettingsActivity, 1f))
        val p = Ui.dp(this@SettingsActivity, 14f)
        setPadding(p, p, p, p)
        views.forEach { addView(it, Ui.lp(Ui.MATCH, Ui.WRAP)) }
    }

    private fun note(t: String) = Ui.text(this, t, 11f, pal.textSub).apply {
        setPadding(0, Ui.dp(this@SettingsActivity, 8f), 0, 0)
    }

    private fun actionRow(t: String, onClick: () -> Unit) = Ui.text(this, t, 14f, pal.accent).apply {
        setPadding(0, Ui.dp(this@SettingsActivity, 8f), 0, Ui.dp(this@SettingsActivity, 8f))
        setOnClickListener { onClick() }
    }

    /** 没引 material,开关就用一行文字加勾。够用,而且和别处一致。 */
    private fun switchRow(t: String, key: String, invert: Boolean = false): View {
        val tv = Ui.text(this, "", 14f, pal.textMain)
        fun render() {
            val raw = Config.get(this, key, if (invert) "1" else "0")
            val on = if (invert) raw == "0" else raw == "1"
            tv.text = (if (on) "☑  " else "☐  ") + t
            tv.setTextColor(if (on) pal.textMain else pal.textSub)
        }
        tv.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        tv.setOnClickListener {
            val raw = Config.get(this, key, if (invert) "1" else "0")
            val on = if (invert) raw == "0" else raw == "1"
            val next = !on
            Config.set(this, key, if (invert) (if (next) "0" else "1") else (if (next) "1" else "0"))
            render()
        }
        render()
        return tv
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

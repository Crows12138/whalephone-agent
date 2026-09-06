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

        col.addView(Ui.text(this, "设置", 20f, pal.textMain, bold = true))

        section(col, "模型接口(OpenAI 兼容)")
        col.addView(card(
            *labeled("base_url", field(Config.KEY_BASE_URL, "https://api.deepseek.com/v1", "https://api.deepseek.com/v1")),
            *labeled("密钥", field(Config.KEY_API_KEY, "sk-...", password = true)),
            *labeled("模型名", field(Config.KEY_MODEL, "deepseek-chat", "deepseek-chat")),
            note("DeepSeek / Kimi / 智谱 / OpenRouter / 自建 vLLM 是同一套协议,换 base_url 和 model 即可。密钥只存在这台手机上。"),
        ))

        section(col, "让路:键盘还开着但没动静,等多久算你停手了")
        col.addView(card(
            *labeled("你一个键都没按过(毫秒)", field("TYPING_IDLE_MS", "10000", "10000", number = true)),
            *labeled("你按过键(毫秒)", field("DRAFT_IDLE_MS", "10000", "10000", number = true)),
            note("按过键单列一档,是因为那时你手里可能攥着一串没上屏的拼音,而「正在组词」这个状态从系统外面读不到。调大它等于给选词留更多时间,代价是你打完字放下手机时它多等同样久。"),
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

package ai.whalephone.probe

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容的 chat/completions 客户端。
 *
 * 刻意不绑定具体厂商:base_url + model 都是配置项,DeepSeek / Kimi / 智谱 / OpenRouter
 * / 自建 vLLM 都是同一套协议。用 HttpURLConnection 而不是 OkHttp,是因为整个请求
 * 就一个 POST,没必要为此背一个网络库 —— APK 越小,装到别人手机上验收越省事。
 */
class Llm(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    class Message(val role: String, val content: String)

    fun chat(messages: List<Message>, temperature: Double = 0.0, timeoutMs: Int = 60_000): String {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().also { arr ->
                messages.forEach { m ->
                    arr.put(JSONObject().put("role", m.role).put("content", m.content))
                }
            })
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use(BufferedReader::readText)
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code: ${text.take(500)}")
                throw RuntimeException("LLM HTTP $code")
            }
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    companion object { private const val TAG = "WPLlm" }
}

/**
 * 配置。手机上没有环境变量,等价物是这里 —— 三个键既能在 app 里填,
 * 也能用 adb 广播灌进来,方便无人值守地跑测试。
 */
object Config {
    const val KEY_BASE_URL = "LLM_BASE_URL"
    const val KEY_API_KEY = "LLM_API_KEY"
    const val KEY_MODEL = "LLM_MODEL"

    private const val PREFS = "whalephone"

    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun get(ctx: android.content.Context, key: String, def: String = "") =
        prefs(ctx).getString(key, def) ?: def

    fun set(ctx: android.content.Context, key: String, value: String) =
        prefs(ctx).edit().putString(key, value).apply()

    fun llm(ctx: android.content.Context): Llm? {
        val base = get(ctx, KEY_BASE_URL, "https://api.deepseek.com/v1")
        val key = get(ctx, KEY_API_KEY)
        val model = get(ctx, KEY_MODEL, "deepseek-chat")
        return if (key.isBlank()) null else Llm(base, key, model)
    }
}

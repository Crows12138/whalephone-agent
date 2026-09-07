package ai.whalephone.agent

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
    /**
     * [imageB64] 不为空时按 OpenAI 的多模态格式发:content 从一个字符串变成
     * 「文本块 + 图片块」的数组。同一套协议,智谱 / 通义 / Kimi / OpenRouter 都认。
     */
    class Message(val role: String, val content: String, val imageB64: String? = null)

    /**
     * 带重试。一次瞬时网络错误不该让整个任务前功尽弃 —— 实测跑到第 7 步时
     * 一个 Connection reset 就把前面全丢了,而手机上网络本来就会抖。
     *
     * 只重试传输层错误和 5xx / 429。4xx(密钥错、参数错)重试多少次都一样,
     * 立刻抛出去让用户看见真正的原因。
     */
    fun chat(messages: List<Message>, temperature: Double = 0.0, timeoutMs: Int = 60_000): String {
        var last: Throwable? = null
        repeat(RETRIES) { n ->
            if (n > 0) Thread.sleep(1000L * (1 shl (n - 1)))   // 1s, 2s
            try {
                return once(messages, temperature, timeoutMs)
            } catch (t: Throwable) {
                if (t is Permanent) throw t.cause ?: t
                last = t
                Log.w(TAG, "第 ${n + 1} 次调用失败(${t.message}),${if (n + 1 < RETRIES) "重试" else "放弃"}")
            }
        }
        throw last ?: RuntimeException("LLM 调用失败")
    }

    /** 不该重试的错误(4xx),用它包一层带出去 */
    private class Permanent(cause: Throwable) : RuntimeException(cause)

    private fun once(messages: List<Message>, temperature: Double, timeoutMs: Int): String {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("messages", JSONArray().also { arr ->
                messages.forEach { m ->
                    arr.put(JSONObject().put("role", m.role).put("content", content(m)))
                }
            })
        }.toString()

        val bytes = body.toByteArray()
        // 带图的请求值得留一行:图有没有真的挂上去、多大,出问题时这是第一个要看的
        messages.count { it.imageB64 != null }.let {
            if (it > 0) Log.i(TAG, "带图请求 $it 张,共 ${bytes.size / 1024} KB,模型 $model")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = timeoutMs
            doOutput = true
            // 带图的请求有几百 KB。不定长的话 HttpURLConnection 会先在内存里攒完整个
            // 请求体再发,手机上没必要冒这个险。
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use(BufferedReader::readText)
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code: ${text.take(500)}")
                val e = RuntimeException("LLM HTTP $code: ${text.take(200)}")
                throw if (code in 400..499 && code != 429) Permanent(e) else e
            }
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun content(m: Message): Any = m.imageB64?.let { b64 ->
        JSONArray()
            .put(JSONObject().put("type", "text").put("text", m.content))
            .put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$b64")))
    } ?: m.content

    companion object {
        private const val TAG = "WPLlm"
        private const val RETRIES = 3
    }
}

/**
 * 配置。手机上没有环境变量,等价物是这里 —— 三个键既能在 app 里填,
 * 也能用 adb 广播灌进来,方便无人值守地跑测试。
 */
object Config {
    const val KEY_BASE_URL = "LLM_BASE_URL"
    const val KEY_API_KEY = "LLM_API_KEY"
    const val KEY_MODEL = "LLM_MODEL"

    /**
     * 看图那条路单独一组键。
     *
     * 不能复用上面那三个:主模型挑的是推理和便宜(DeepSeek 就没有视觉模型),
     * 而这条路只在无障碍树读不出东西时才走,一次任务可能一次都不用。硬把主模型
     * 换成视觉模型,是让常态路径为一个例外买单。
     *
     * 只有 [KEY_VLM_MODEL] 是必填的:base_url 和密钥留空就沿用主模型那一组,
     * 这样同一家同时供文本和视觉(智谱 / Kimi / 通义 / OpenRouter)时只用填一格。
     */
    const val KEY_VLM_BASE_URL = "VLM_BASE_URL"
    const val KEY_VLM_API_KEY = "VLM_API_KEY"
    const val KEY_VLM_MODEL = "VLM_MODEL"

    private const val PREFS = "whalephone"

    fun prefs(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun get(ctx: android.content.Context, key: String, def: String = "") =
        prefs(ctx).getString(key, def) ?: def

    fun set(ctx: android.content.Context, key: String, value: String) =
        prefs(ctx).edit().putString(key, value).apply()

    fun remove(ctx: android.content.Context, key: String) =
        prefs(ctx).edit().remove(key).apply()

    /** 没填模型名就是没开这条路,返回 null —— agent 会退回今天的行为,不会假装能看图 */
    fun vlm(ctx: android.content.Context): Llm? {
        val model = get(ctx, KEY_VLM_MODEL)
        if (model.isBlank()) return null
        val base = get(ctx, KEY_VLM_BASE_URL)
            .ifBlank { get(ctx, KEY_BASE_URL, "https://api.deepseek.com/v1") }
        val key = get(ctx, KEY_VLM_API_KEY).ifBlank { get(ctx, KEY_API_KEY) }
        return if (key.isBlank()) null else Llm(base, key, model)
    }

    fun llm(ctx: android.content.Context): Llm? {
        val base = get(ctx, KEY_BASE_URL, "https://api.deepseek.com/v1")
        val key = get(ctx, KEY_API_KEY)
        val model = get(ctx, KEY_MODEL, "deepseek-chat")
        return if (key.isBlank()) null else Llm(base, key, model)
    }
}

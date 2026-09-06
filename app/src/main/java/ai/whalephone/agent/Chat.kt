package ai.whalephone.agent

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话记录落盘的那一份。
 *
 * 为什么非落盘不可:机主说的话、agent 做过的事、以及模型下一轮能看到的上下文,
 * 是同一串东西的三个用途。只活在内存里的话,「默认不清、点新任务才清」这句话
 * 就是假的 —— 真实规则会变成「系统什么时候回收进程就什么时候清」,而那个时刻
 * 既不由我们定,机主也看不见。前台服务收摊之后这个进程就是个普通进程,随时会走。
 *
 * 结构是一串「段」,当前这一段是最后一个;点「新任务」就追加一段空的。
 * 旧的段留着(上限 [MAX_THREADS] 段),界面现在只显示当前这一段 ——
 * 以后要做「翻旧账」,数据已经在这儿了,不用改存储。
 */
object Chat {

    private const val TAG = "WPChat"
    private const val FILE = "chat.json"

    /** 留多少段。够翻回去几天,又不至于让这个文件长到要考虑分页 */
    private const val MAX_THREADS = 20

    /** 单段留多少行。一次长任务几十步,几百行足够,再多对机主也没意义 */
    private const val MAX_LINES = 400

    private var file: File? = null
    private val threads = mutableListOf<MutableList<AgentBus.Line>>()

    /** 写盘放到自己的线程上:调用方是 agent 的工作线程和界面线程,都不该等磁盘 */
    private val io by lazy {
        Handler(HandlerThread("wp-chat").apply { start() }.looper)
    }
    private val flush = Runnable { writeNow() }

    @Synchronized
    fun attach(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, FILE)
        read()
        if (threads.isEmpty()) threads += mutableListOf<AgentBus.Line>()
    }

    @Synchronized
    fun current(): List<AgentBus.Line> = threads.lastOrNull()?.toList() ?: emptyList()

    @Synchronized
    fun append(l: AgentBus.Line) {
        if (file == null) return          // 还没 attach:内存里那份照常走,只是不落盘
        val t = threads.lastOrNull() ?: mutableListOf<AgentBus.Line>().also { threads += it }
        t += l
        while (t.size > MAX_LINES) t.removeAt(0)
        save()
    }

    /** 开新的一段。旧的留着,不删 */
    @Synchronized
    fun newThread() {
        if (threads.lastOrNull()?.isEmpty() == true) return   // 已经是空白的一段了
        threads += mutableListOf<AgentBus.Line>()
        while (threads.size > MAX_THREADS) threads.removeAt(0)
        save()
    }

    private fun save() {
        io.removeCallbacks(flush)
        io.postDelayed(flush, 400)        // 一步动作会连着发好几行,攒一下再写
    }

    @Synchronized
    private fun writeNow() {
        val f = file ?: return
        val root = JSONArray()
        threads.forEach { t ->
            val arr = JSONArray()
            t.forEach { l ->
                arr.put(JSONObject().apply {
                    put("k", l.kind.name); put("t", l.title); put("b", l.body); put("at", l.at)
                })
            }
            root.put(arr)
        }
        runCatching { f.writeText(root.toString()) }
            .onFailure { Log.w(TAG, "对话写不进去,这次的记录只在内存里", it) }
    }

    private fun read() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = JSONArray(f.readText())
            for (i in 0 until root.length()) {
                val arr = root.getJSONArray(i)
                val t = mutableListOf<AgentBus.Line>()
                for (j in 0 until arr.length()) {
                    val o = arr.getJSONObject(j)
                    val kind = runCatching { AgentBus.Kind.valueOf(o.getString("k")) }.getOrNull()
                        ?: continue
                    t += AgentBus.Line(kind, o.optString("t"), o.optString("b"), o.optLong("at"))
                }
                threads += t
            }
        }.onFailure {
            // 存档坏了不该让 app 起不来。丢掉重来,顶多是少了一段历史
            Log.w(TAG, "对话存档读不了,从空的开始", it)
            threads.clear()
        }
    }
}

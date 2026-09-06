package ai.whalephone.agent

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * agent 正在做什么,给界面看的那一份。
 *
 * 为什么需要它:决策循环跑在 AgentService 的工作线程上,而界面可能开着、可能没开、
 * 可能开了又关。原来这条信息只有两个出口 —— logcat(用户看不到)和通知(一行标题
 * 一行正文,只有最新的那条)。要在界面上看到「它走到第几步、每一步做了什么」,
 * 就得有一份进程内的、活得比 Activity 久的记录。
 *
 * 只在进程内,不广播:AgentService 和界面本来就同一个进程,发广播是绕远路,
 * 而且广播没有「补历史」的语义 —— 界面重新打开时要能看到之前发生过什么。
 */
object AgentBus {

    enum class Kind {
        /** 机主下的任务 */          GOAL,
        /** agent 的一步动作 */      STEP,
        /** 过程里的说明 */          NOTE,
        /** 需要机主拍板 */          ASK,
        /** 机主对上一个 ASK 的回答 */ REPLY,
        /** 任务做完了 */            RESULT,
        /** 没做成 */                FAIL,
    }

    data class Line(
        val kind: Kind,
        val title: String,
        val body: String = "",
        val at: Long = System.currentTimeMillis(),
    )

    /** 只留最近这些条。跑长时任务时行数会一直涨,不设上限会把内存吃掉。 */
    private const val CAP = 400

    private val items = ArrayDeque<Line>()
    private val listeners = CopyOnWriteArrayList<(Line?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** 现在有没有任务在跑。界面拿它决定输入框是「发送」还是「停止」。 */
    @Volatile var running = false
        private set

    /**
     * agent 此刻正在等机主回答的那句话,没在等就是 null。
     *
     * 界面和悬浮球都要知道这件事:有问题挂着的时候,输入框的意思不是「下新任务」,
     * 而是「回答它」。这个状态必须活在进程里而不是某个 Activity 里 —— 机主大概率
     * 根本没开着 app,他是在通知里或者悬浮球上看到这个问题的。
     */
    @Volatile var asking: String? = null
        private set

    fun setAsking(q: String?) {
        if (asking == q) return
        asking = q
        fire(null)
    }

    fun snapshot(): List<Line> = synchronized(items) { items.toList() }

    fun post(kind: Kind, title: String, body: String = "") {
        val line = Line(kind, title, body)
        synchronized(items) {
            items.addLast(line)
            while (items.size > CAP) items.removeFirst()
        }
        fire(line)
    }

    fun setRunning(v: Boolean) {
        if (running == v) return
        running = v
        fire(null)
    }

    fun clear() {
        synchronized(items) { items.clear() }
        fire(null)
    }

    /** 回调一律切到主线程再发 —— 调用方是 agent 的工作线程,订阅方是界面。 */
    private fun fire(line: Line?) = main.post { listeners.forEach { it(line) } }

    /** line 为 null 表示「不是新增一条,是整体变了,重画」。 */
    fun subscribe(l: (Line?) -> Unit) = listeners.add(l)

    fun unsubscribe(l: (Line?) -> Unit) = listeners.remove(l)
}

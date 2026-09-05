package ai.whalephone.agent

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * 无障碍权限的开关,跟着任务走:开跑前打开,跑完关掉。
 *
 * 为什么值得做这件事:一个能 `performAction(ACTION_CLICK)` 的无障碍服务,在微信收银台
 * 眼里和盗刷木马没有区别 —— 开着它就付不了款(真机上撞到过)。而 agent 一天里真正在
 * 干活的时间很短,让权限常开等于为了几分钟的工作,让机主全天付不了钱。
 * 权限的生命周期本来就该等于用它的那段时间。
 *
 * 三条必须守住的规矩,每一条都对应一种「好心办坏事」:
 *
 *   - **机主自己开着的,我们不关。** 只关我们自己开的那一次。
 *   - **别人的无障碍服务不能被波及。** 这个 setting 是一张冒号分隔的表,读屏、按键映射
 *     都在里面。整表覆盖会把别人的服务一起关掉 —— 对依赖读屏的人来说这是断人手脚。
 *     所以只在表里增删自己这一项。
 *   - **进程被杀也要能收场。** 我们打开过就在 SharedPreferences 里留个记号;
 *     下次起来看到记号还在,说明上次没关成,这次收尾时一并关掉。
 *
 * 打不开也不算错:机主可能压根没授权过,那时 AgentService 会照旧报「无障碍服务没开」。
 */
object A11yGate {

    private const val TAG = "WPGate"
    private const val KEY = "enabled_accessibility_services"
    private const val ENABLED = "accessibility_enabled"
    private val SELF_CN = ComponentName(
        "ai.whalephone.agent", "ai.whalephone.agent.EyesAndHands")
    private val SELF = SELF_CN.flattenToString()

    /**
     * 这一项是不是我们自己。**不能拿字符串相等去判**。
     *
     * 同一个组件在这张表里有两种等价写法:`pkg/pkg.Cls` 和缩写的 `pkg/.Cls`。
     * 机主从设置里开的、脚本写进去的、AccessibilityManagerService 自己归一化过的,
     * 三者未必是同一种写法 —— 真机上实测到 AMS 会在写入后把短名展开成全名,
     * 顺带把表里解析不出组件的项直接剔掉。
     * 按字符串比就会出现「表里明明有我们,却认不出来」:开的时候重复追加一遍,
     * 关的时候摘不掉,权限留在那里而且不报错。
     */
    private fun isSelf(entry: String) =
        ComponentName.unflattenFromString(entry.trim()) == SELF_CN

    /** 关掉自动开关,让权限保持常开。给不在乎微信支付、更在乎长时任务的人留的口子。 */
    const val KEY_AUTO = "A11Y_AUTO"

    /** 上次是我们打开的吗 —— 决定这次要不要关。跨进程存,因为进程可能被杀。 */
    private const val MARK = "a11y_opened_by_us"

    private fun auto(ctx: Context) = Config.get(ctx, KEY_AUTO, "1") != "0"

    /**
     * 读那张表。返回 null = **读不到**,和「读到了,是空的」是两回事。
     *
     * 这个区分不是洁癖:下面 close() 要拿读到的表算「摘掉自己之后还剩谁」,
     * 剩下空的就 `settings delete` 整条。如果把「桥没连上」也当成空,
     * 那就会在什么都不知道的情况下把整张表删掉 —— 机主的读屏服务跟着一起没。
     * 读不到的时候唯一安全的动作是不动。
     */
    private fun read(): String? =
        Privileged.execArgs("settings", "get", "secure", KEY).trim().let {
            when {
                it.startsWith("NO_BRIDGE") || it.startsWith("EXEC_FAIL") -> null
                it == "null" -> ""
                else -> it
            }
        }

    private fun services(): List<String>? =
        read()?.split(':')?.filter { it.isNotBlank() }

    private fun write(list: List<String>) {
        if (list.isEmpty()) {
            Privileged.execArgs("settings", "delete", "secure", KEY)
            Privileged.execArgs("settings", "put", "secure", ENABLED, "0")
        } else {
            Privileged.execArgs("settings", "put", "secure", KEY, list.joinToString(":"))
            Privileged.execArgs("settings", "put", "secure", ENABLED, "1")
        }
    }

    /**
     * 把无障碍打开,等到服务真的连上为止。返回是否可用。
     *
     * 等待是必需的:`settings put` 返回时服务还没绑定,立刻去拿 `EyesAndHands.instance`
     * 拿到的是 null —— 那会被上层误判成「机主没授权」。
     */
    fun open(ctx: Context, timeoutMs: Long = 15_000): Boolean {
        // 已经开着:可能是机主自己开的,也可能是我们上次没关成。
        // 后者要认领下来,否则那次的记号永远清不掉。
        if (EyesAndHands.instance != null) {
            if (Config.get(ctx, MARK) == "1") Log.i(TAG, "上次打开后没关成,这次收尾时一并关掉")
            return true
        }
        if (!auto(ctx)) return false
        if (!Privileged.ready) { Log.w(TAG, "特权桥没连上,开不了无障碍"); return false }

        val before = services() ?: run { Log.w(TAG, "读不到无障碍服务表,不动它"); return false }
        if (before.any { isSelf(it) }) {
            // 表里有我们,但服务没起来 —— 多半是 accessibility_enabled 被关了
            Privileged.execArgs("settings", "put", "secure", ENABLED, "1")
        } else {
            write(before + SELF)
        }
        Config.set(ctx, MARK, "1")
        Log.i(TAG, "已打开无障碍(原有 ${before.size} 个服务,没动它们)")

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (EyesAndHands.instance != null) return true
            Thread.sleep(200)
        }
        Log.w(TAG, "打开了但 ${timeoutMs / 1000} 秒内服务没连上")
        return false
    }

    /**
     * 收工。只关我们自己开的那一次,只摘自己那一项。
     *
     * 长时任务("盯着降价")期间不能关:它的触发器是无障碍服务里注册的亮屏广播,
     * 关掉权限等于把那条任务链掐断。这一条是这个自动开关的边界,写在这里而不是
     * 让调用方记得。
     */
    fun close(ctx: Context) {
        if (Config.get(ctx, MARK) != "1") return
        if (Watch.plan(ctx) != null) { Log.i(TAG, "还有长时任务在盯着,权限先留着"); return }
        // 读不到就一步都不动,MARK 也留着 —— 下次起来还认得出这摊没收拾完。
        val left = (services() ?: run { Log.w(TAG, "读不到无障碍服务表,这次不收尾,记号留着"); return })
            .filterNot { isSelf(it) }
        write(left)
        Config.remove(ctx, MARK)
        Log.i(TAG, "已关闭无障碍(剩下 ${left.size} 个别的服务,没动它们)")
    }
}

package ai.whalephone.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长时任务。
 *
 * 「盯着这个东西降价,到价了叫我」和一次性任务的区别不在步骤多,在于要跨小时
 * 甚至跨天存活。第一版用 AlarmManager 定时唤醒,实测发现这个方案是错的:
 *
 * **副屏和主屏共用同一个电源组。** 这台机器上 `dumpsys power` 只有 `groupId: 0`;
 * `OWN_DISPLAY_GROUP` 分的是窗口意义上的显示器组,不是电源组,`DEVICE_DISPLAY_GROUP`
 * 也没能分出独立电源组。所以主屏一灭,副屏跟着灭,上面的 Activity 被停掉 ——
 * 闹钟准时响了也没用,那一轮什么都做不了。想让副屏单独亮着也不行:从副屏的
 * display context 取 SCREEN_BRIGHT_WAKE_LOCK,连主屏一起点亮了。
 *
 * 所以改成**亮屏触发**:agent 的可工作时间就等于用户的亮屏时间。
 *
 * 这个约束和题目其实是对齐的 —— 要解决的本来就是「用户正在用手机的时候」,
 * 而用户在用手机时屏幕必然是亮的。副屏蹭的是已经付过电费的那块屏,
 * 手机闲置时 agent 一点电都不耗,这比定时轮询更省。
 *
 * 代价是时效性由用户的使用习惯决定:一整夜不碰手机,这一夜就没有任何一轮。
 * 对「盯降价」这类需求可以接受(用户睡着时降价了,他醒来第一次亮屏就知道);
 * 对「几点几分必须做完」的需求不适用,那类任务本来也不该用这条路。
 */
object Watch {

    private const val TAG = "WPWatch"
    private const val K_GOAL = "watch_goal"
    private const val K_LEFT = "watch_rounds_left"
    private const val K_GAP = "watch_gap_min"
    private const val K_LAST_AT = "watch_last_at"
    private const val K_LAST = "watch_last_result"

    /** 两轮之间至少隔这么久,免得用户频繁点亮屏幕就被刷屏 */
    const val MIN_GAP_MIN = 10

    data class Plan(val goal: String, val roundsLeft: Int, val gapMin: Int)

    fun start(ctx: Context, goal: String, rounds: Int, gapMin: Int) {
        Config.prefs(ctx).edit()
            .putString(K_GOAL, goal)
            .putInt(K_LEFT, rounds)
            .putInt(K_GAP, gapMin.coerceAtLeast(MIN_GAP_MIN))
            .remove(K_LAST)
            .putLong(K_LAST_AT, 0L)
            .apply()
        Log.i(TAG, "开始盯:$rounds 轮,每次亮屏检查一次,两轮至少隔 $gapMin 分钟")
    }

    fun plan(ctx: Context): Plan? {
        val p = Config.prefs(ctx)
        val goal = p.getString(K_GOAL, null) ?: return null
        return Plan(goal, p.getInt(K_LEFT, 0), p.getInt(K_GAP, MIN_GAP_MIN))
    }

    fun clear(ctx: Context) {
        Config.prefs(ctx).edit()
            .remove(K_GOAL).remove(K_LEFT).remove(K_LAST).remove(K_LAST_AT).apply()
    }

    /**
     * 记一轮的结果。返回 true 表示和上一轮不同,值得响一声。
     * 第一轮总是算「有变化」—— 用户刚下的任务,第一份结果他要看。
     */
    fun record(ctx: Context, result: String): Boolean {
        val p = Config.prefs(ctx)
        val last = p.getString(K_LAST, null)
        p.edit()
            .putString(K_LAST, result)
            .putInt(K_LEFT, (p.getInt(K_LEFT, 1) - 1).coerceAtLeast(0))
            .putLong(K_LAST_AT, System.currentTimeMillis())
            .apply()
        return last == null || last != result
    }

    fun roundsLeft(ctx: Context) = Config.prefs(ctx).getInt(K_LEFT, 0)

    /** 还剩几轮、上次什么时候跑的,给通知用 */
    fun statusLine(ctx: Context): String {
        val p = plan(ctx) ?: return ""
        val at = Config.prefs(ctx).getLong(K_LAST_AT, 0L)
        val when_ = if (at == 0L) "还没跑过"
                    else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        return "还剩 ${p.roundsLeft} 轮 · 上次 $when_ · 下次亮屏时"
    }

    /** 亮屏了:看看这一轮该不该跑 */
    private fun onScreenOn(ctx: Context) {
        val p = plan(ctx) ?: return
        if (p.roundsLeft <= 0) { clear(ctx); return }
        val since = System.currentTimeMillis() - Config.prefs(ctx).getLong(K_LAST_AT, 0L)
        if (since < p.gapMin * 60_000L) {
            Log.i(TAG, "离上一轮才 ${since / 60_000} 分钟,这次亮屏不跑")
            return
        }
        Log.i(TAG, "亮屏,开跑这一轮,还剩 ${p.roundsLeft}")
        AgentService.startRound(ctx, p.goal)
    }

    /**
     * ACTION_SCREEN_ON 不能在清单里静态注册(Android 8 起被限制),只能运行时注册。
     * 挂在无障碍服务上:它只要被启用就一直活着,是这个 app 里生命周期最长的部件。
     */
    fun attach(ctx: Context): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_ON) onScreenOn(c.applicationContext)
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON))
        return r
    }
}

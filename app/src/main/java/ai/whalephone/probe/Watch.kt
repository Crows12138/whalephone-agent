package ai.whalephone.probe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长时任务。
 *
 * 「盯着这个东西降价,到价了叫我」这类需求和一次性任务的区别不在于步骤多,
 * 而在于它要跨小时甚至跨天存活 —— 期间用户会锁屏、切 App、手机会进 Doze。
 *
 * 关键决定是**每轮之间不占资源**:副屏销毁、循环线程退出,只留一个 AlarmManager
 * 闹钟和一条静默通知。一块虚拟显示器在 Doze 期间一直挂着,既费电又容易被系统回收,
 * 而这类任务大部分时间是在等。
 *
 * 用 `setExactAndAllowWhileIdle` 而不是 `postDelayed`:Doze 会把普通定时器攒到
 * 维护窗口一起放,间隔一长就完全不准。这个 API 是唯一能在 Doze 里按时醒的,
 * 代价是系统对它有频率限制(约每 9 分钟一次),所以间隔不设短于 15 分钟。
 *
 * 通知策略:结果和上一轮一样就静默更新那条常驻通知,不打扰;变了才发一条
 * 会响的。「一直没变化」本身不值得打断用户,而这恰恰是长时监控的常态。
 */
object Watch {

    private const val TAG = "WPWatch"
    private const val K_GOAL = "watch_goal"
    private const val K_LEFT = "watch_rounds_left"
    private const val K_INTERVAL = "watch_interval_min"
    private const val K_LAST = "watch_last_result"

    const val MIN_INTERVAL_MIN = 15

    data class Plan(val goal: String, val roundsLeft: Int, val intervalMin: Int)

    fun start(ctx: Context, goal: String, rounds: Int, intervalMin: Int) {
        Config.prefs(ctx).edit()
            .putString(K_GOAL, goal)
            .putInt(K_LEFT, rounds)
            .putInt(K_INTERVAL, intervalMin.coerceAtLeast(MIN_INTERVAL_MIN))
            .remove(K_LAST)
            .apply()
        Log.i(TAG, "长时任务开始:$rounds 轮,每 $intervalMin 分钟一次")
    }

    fun plan(ctx: Context): Plan? {
        val p = Config.prefs(ctx)
        val goal = p.getString(K_GOAL, null) ?: return null
        return Plan(goal, p.getInt(K_LEFT, 0), p.getInt(K_INTERVAL, MIN_INTERVAL_MIN))
    }

    fun clear(ctx: Context) {
        Config.prefs(ctx).edit().remove(K_GOAL).remove(K_LEFT).remove(K_LAST).apply()
        cancelAlarm(ctx)
    }

    /**
     * 记一轮的结果。返回 true 表示这一轮的结果和上一轮不同,值得响一声。
     * 第一轮总是算「有变化」—— 用户刚下的任务,第一份结果他要看。
     */
    fun record(ctx: Context, result: String): Boolean {
        val p = Config.prefs(ctx)
        val last = p.getString(K_LAST, null)
        p.edit().putString(K_LAST, result).putInt(K_LEFT, (p.getInt(K_LEFT, 1) - 1).coerceAtLeast(0)).apply()
        return last == null || last != result
    }

    fun roundsLeft(ctx: Context) = Config.prefs(ctx).getInt(K_LEFT, 0)

    fun scheduleNext(ctx: Context): String? {
        val plan = plan(ctx) ?: return null
        if (plan.roundsLeft <= 0) { clear(ctx); return null }
        val at = System.currentTimeMillis() + plan.intervalMin * 60_000L
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // 没有精确闹钟权限就退到不精确的,间隔会被系统拉长但任务不会断
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.e(TAG, "闹钟没设上", it) }
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, Alarm::class.java).setAction(ACT_ROUND),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelAlarm(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx))

    const val ACT_ROUND = "ai.whalephone.probe.ROUND"

    class Alarm : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val plan = plan(ctx) ?: return
            Log.i(TAG, "闹钟响了,还剩 ${plan.roundsLeft} 轮")
            AgentService.startRound(ctx, plan.goal)
        }
    }
}

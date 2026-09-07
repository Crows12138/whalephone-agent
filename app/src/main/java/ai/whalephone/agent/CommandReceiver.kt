package ai.whalephone.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 清单里静态注册的任务入口。
 *
 * 为什么不能只用无障碍服务里那个:权限现在跟着任务走(见 A11yGate),不跑任务时
 * 无障碍是关的,那个服务不存在,注册在它里面的接收器自然也不在 —— 于是
 * 「发个广播让 agent 开工」这条路在最需要它的时候恰好断掉。入口不能挂在
 * 它自己要打开的那个东西上。
 *
 * 静态接收器在 Android 8 之后收不到隐式广播,所以发的时候要带包名:
 *   adb shell am broadcast -p ai.whalephone.agent -a ai.whalephone.agent.RUN --es goal "..."
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, i: Intent?) {
        val c = ctx ?: return
        if (i?.action != AgentService.ACT_RUN_EXTERNAL) return
        val goal = i.getStringExtra("goal").orEmpty()
        if (goal.isBlank()) return
        Log.i("WPCmd", "收到任务: $goal")
        if (!AgentService.start(c, goal)) Log.w("WPCmd", "这条任务没能开跑,原因见上一行")
    }
}

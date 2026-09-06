package ai.whalephone.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机后把机主上次留着的悬浮窗恢复回来。
 *
 * 「不打开应用也能用」这句话要成立,重启一次就不见了是不行的 —— 而手机是会重启的,
 * 这个项目里 Shizuku 每次重启都要重开,所以重启本来就是常态路径的一部分。
 *
 * 只恢复悬浮窗,不恢复任务:agent 干活要 Shizuku,而 Shizuku 的服务重启后必须由机主
 * 重新拉起。悬浮球这时候仍然有用 —— 他点开就能看到「Shizuku 未就绪」并被引到那一步。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED &&
            i.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val want = Config.get(ctx, OverlayService.KEY_BALL) == "1" ||
            Config.get(ctx, OverlayService.KEY_SCREEN) == "1"
        if (!want) return
        runCatching {
            ctx.startForegroundService(
                Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACT_RESTORE))
        }.onFailure { Log.w("WPBoot", "开机恢复悬浮窗失败", it) }
    }
}

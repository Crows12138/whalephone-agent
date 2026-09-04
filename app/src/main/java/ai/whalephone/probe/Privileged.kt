package ai.whalephone.probe

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * app 侧访问特权桥的入口。
 *
 * 为什么是 Shizuku 而不是电脑接 ADB:题目要的是「跑在真机上」的 agent。
 * Shizuku 让用户在手机自己的「无线调试」里配一次,之后手机断开电脑照常工作;
 * 而 PC+ADB 方案一拔线 agent 就死。两者拿到的是同一个 shell UID,能力完全等价,
 * 差别只在这条 shell 通道是谁维持的。开发期我仍然用 ADB 起桥,方便调试。
 */
object Privileged {

    private const val TAG = "WPPriv"
    private var bridge: IShellBridge? = null

    val ready: Boolean get() = bridge?.asBinder()?.pingBinder() == true

    /** Shizuku 是否在运行 */
    fun shizukuAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 有没有拿到 Shizuku 授权 */
    fun shizukuGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(code: Int = 1001) = runCatching { Shizuku.requestPermission(code) }

    /** 阻塞地把桥拉起来。成功返回 true。 */
    fun connect(ctx: Context, timeoutMs: Long = 15_000): Boolean {
        if (ready) return true
        if (!shizukuAlive()) { Log.w(TAG, "Shizuku 没运行"); return false }
        if (!shizukuGranted()) { Log.w(TAG, "Shizuku 未授权"); return false }

        val latch = CountDownLatch(1)
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellBridge::class.java.name))
            .daemon(false)
            .processNameSuffix("bridge")
            .debuggable(false)
            .version(1)

        Shizuku.bindUserService(args, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bridge = binder?.let { IShellBridge.Stub.asInterface(it) }
                Log.i(TAG, "桥已连上 ready=$ready")
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                bridge = null; Log.w(TAG, "桥断开")
            }
        })
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ready
    }

    fun exec(cmd: String): String =
        bridge?.exec(cmd) ?: "NO_BRIDGE"

    fun createAgentDisplay(w: Int, h: Int, dpi: Int, surface: Surface): Int =
        bridge?.createDisplay(w, h, dpi, surface, ShellBridge.AGENT_DISPLAY_FLAGS) ?: -1

    /**
     * 逐位降级地造屏。OWN_FOCUS / ALWAYS_UNLOCKED 是 Android 14 才有的,
     * 老机器或者厂商魔改上可能直接抛异常 —— 与其整个失败,不如按重要性依次去掉,
     * 并把最终实际生效的标志位报出来,方便在答辩里说清「这台机器上拿到了哪几条保证」。
     */
    fun createAgentDisplayBestEffort(w: Int, h: Int, dpi: Int, surface: Surface): Pair<Int, Int> {
        val ladder = listOf(
            ShellBridge.AGENT_DISPLAY_FLAGS,
            ShellBridge.AGENT_DISPLAY_FLAGS and (ShellBridge.ALWAYS_UNLOCKED or ShellBridge.OWN_DISPLAY_GROUP).inv(),
            ShellBridge.TRUSTED or ShellBridge.OWN_CONTENT_ONLY or
                ShellBridge.SHOULD_SHOW_SYSTEM_DECORATIONS or ShellBridge.OWN_FOCUS,
            ShellBridge.TRUSTED or ShellBridge.OWN_CONTENT_ONLY or
                ShellBridge.SHOULD_SHOW_SYSTEM_DECORATIONS,
        )
        for (f in ladder) {
            val id = bridge?.createDisplay(w, h, dpi, surface, f) ?: -1
            if (id >= 0) return id to f
        }
        return -1 to 0
    }

    fun release(displayId: Int) { runCatching { bridge?.releaseDisplay(displayId) } }
}

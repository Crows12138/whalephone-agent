package ai.whalephone.probe

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Surface
import java.io.BufferedReader

/**
 * 特权桥的实现。这个类的实例跑在 Shizuku 拉起的 shell UID 进程里,
 * 不是 app 进程 —— 所以这里能做 app 做不到的三件事:
 *
 *   1. 造受信虚拟显示器(ADD_TRUSTED_DISPLAY,signature|privileged)
 *   2. 带显示器维度地注入按键(`input -d <id>`,INJECT_EVENTS 是 signature 级)
 *   3. 把任意第三方 App 启到那块屏上(`am start --display <id>`)
 *
 * 不受信的虚拟显示器只能启自己 UID 的 Activity,淘宝微信都启不上去 —— 这是
 * 「纯 app 方案」在这条路上走不通的硬边界,也是必须借 shell UID 的原因。
 */
class ShellBridge : IShellBridge.Stub {

    private val displays = HashMap<Int, VirtualDisplay>()

    constructor() : super()

    override fun destroy() {
        synchronized(displays) {
            displays.values.forEach { runCatching { it.release() } }
            displays.clear()
        }
        Log.i(TAG, "bridge destroyed")
    }

    override fun exec(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use(BufferedReader::readText)
        p.waitFor()
        out
    }.getOrElse { "EXEC_FAIL: ${it.message}" }

    override fun createDisplay(w: Int, h: Int, dpi: Int, surface: Surface?, flags: Int): Int {
        return runCatching {
            val dm = displayManager()
            val vd = dm.createVirtualDisplay("whalephone-agent", w, h, dpi, surface, flags)
            val id = vd.display.displayId
            synchronized(displays) { displays[id] = vd }
            Log.i(TAG, "虚拟屏已创建 id=$id ${w}x$h@$dpi flags=0x${flags.toString(16)}")
            id
        }.getOrElse {
            Log.e(TAG, "createDisplay 失败 flags=0x${flags.toString(16)}", it)
            -1
        }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    /**
     * DisplayManagerService 会拿客户端报上来的包名反查 uid 做校验。系统 Context 的
     * 包名是 "android"(uid 1000),而我们这个进程是 shell(uid 2000),对不上就直接
     * SecurityException: packageName must match the owner uid。实测踩过。
     *
     * 所以要套一层把包名改成 shell 自己的包,并且 DisplayManager 必须用这个 Context
     * 现造 —— getSystemService 拿到的是用底层 ContextImpl 建好的缓存实例,
     * 包装层改的包名它看不见。
     */
    private class ShellContext(base: android.content.Context) :
        android.content.ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): android.content.Context = this
    }

    private fun displayManager(): DisplayManager {
        val c = ShellContext(ctx())
        val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(c) as DisplayManager
    }

    /**
     * shell 进程里没有现成的 Context。app_process 起来的进程走
     * ActivityThread.systemMain() 拿系统 Context —— scrcpy / Shizuku 自己都是这么干的。
     */
    private fun ctx(): android.content.Context {
        cached?.let { return it }
        // systemMain() 内部要建 Handler,当前线程必须先有 Looper
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        val c = at.getMethod("getSystemContext").invoke(thread) as android.content.Context
        cached = c
        return c
    }

    companion object {
        private const val TAG = "WPBridge"
        private var cached: android.content.Context? = null

        // 以下常量在 DisplayManager 里是 @hide 的,值来自 AOSP,直接内联
        const val PUBLIC                     = 1 shl 0
        const val OWN_CONTENT_ONLY           = 1 shl 3
        const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val TRUSTED                    = 1 shl 10
        const val OWN_DISPLAY_GROUP          = 1 shl 11
        const val ALWAYS_UNLOCKED            = 1 shl 12
        const val OWN_FOCUS                  = 1 shl 14

        /**
         * agent 屏的标志位组合,每一位都对应一个实测出来的冲突:
         *
         *  TRUSTED                  必需,否则第三方 App 启不上来
         *  OWN_CONTENT_ONLY         不镜像主屏,用户屏上什么都不会多出来
         *  SHOULD_SHOW_SYSTEM_DECORATIONS  这块屏有自己的状态栏/导航,才能有自己的 IME 策略
         *  OWN_FOCUS                ← 焦点冲突的结构性解法:这块屏自己维护焦点,
         *                             agent 点什么都不会把全局焦点指针从用户那块屏拽走
         *  OWN_DISPLAY_GROUP        ALWAYS_UNLOCKED 的前置条件
         *  ALWAYS_UNLOCKED          用户锁屏后 agent 继续干活,而不是只能看见 keyguard
         */
        const val AGENT_DISPLAY_FLAGS =
            TRUSTED or OWN_CONTENT_ONLY or SHOULD_SHOW_SYSTEM_DECORATIONS or
            OWN_FOCUS or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED
    }
}

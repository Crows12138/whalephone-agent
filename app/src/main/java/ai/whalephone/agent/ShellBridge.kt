package ai.whalephone.agent

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

    constructor() : super() {
        // 构造发生在 user service 进程的主线程,那里有 Looper。先把 Context 拿到缓存里,
        // 后面 binder 线程上的调用就不用再碰 ActivityThread 了。
        runCatching { ctx() }.onFailure { Log.w(TAG, "预热 Context 失败,留给首次调用重试", it) }
        reapOrphans()
    }

    /**
     * 把同名的旧桥进程收掉,只留自己。
     *
     * 桥不是 app 的进程,是 Shizuku 用 shell 身份起的独立进程 —— **app 被 force-stop
     * 杀不掉它**;而 app 死过一次之后再来绑,Shizuku 不会复用那个孤儿,会再起一个。
     * 实测:force-stop 之后跑一个任务,桥从 1 个变 2 个,每轮 +1。
     *
     * 攒到两三个之后的现象是**任务静默不启动**:广播发出去,日志一行都没有,
     * 脚本照样跑完给出一份干净的读数。今天在测试里连撞三次才定位到。
     * 这个项目最不能出的就是这种错,所以宁可在这里多做一步。
     *
     * 试过更斯文的做法:绑之前调 `Shizuku.unbindUserService(args, null, remove=true)`。
     * 无效 —— 没绑的状态下 Shizuku 不认,孤儿照样在。所以改成自己动手。
     * 杀的全是本 app 自己的进程,而且这一步跑在新桥启动时,那时旧的一定没人在用。
     */
    private fun reapOrphans() = runCatching {
        val me = android.os.Process.myPid()
        val out = ProcessBuilder("ps", "-A", "-o", "PID,NAME").redirectErrorStream(true)
            .start().inputStream.bufferedReader().use(BufferedReader::readText)
        var n = 0
        for (line in out.lineSequence()) {
            if (!line.trimEnd().endsWith(":bridge")) continue
            val pid = line.trim().substringBefore(' ').toIntOrNull() ?: continue
            if (pid == me) continue
            android.os.Process.killProcess(pid); n++
        }
        if (n > 0) Log.i(TAG, "收掉了 $n 个残留的桥进程(force-stop 杀不到它们)")
    }.onFailure { Log.w(TAG, "清理残留桥进程失败,不影响本次工作", it) }

    /** WindowManager.LayoutParams.DISPLAY_IME_POLICY_HIDE —— 这块屏不显示输入法 */
    private val DISPLAY_IME_POLICY_HIDE = 2
    private val POLICY_UNREADABLE = -99

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

    /**
     * 不经过 sh 的执行路径。ProcessBuilder 收 argv 数组时是直接 execve,
     * 参数里的 ; && ` $() 全部只是普通字符 —— 注入这一类问题在这里不存在。
     *
     * 无障碍树里的文字是攻击者可控的(商品标题、网页内容、通知),它们会进模型的
     * 上下文,模型的输出又会变成命令参数。这条链上任何一处做字符串拼接,
     * 都等于把 uid 2000 的 shell 交出去。
     */
    override fun execArgs(argv: MutableList<String>?): String = runCatching {
        val a = argv?.toList().orEmpty()
        if (a.isEmpty()) return "EXEC_FAIL: 空命令"
        val p = ProcessBuilder(a).redirectErrorStream(true).start()
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
            hideImeOn(id)
            id
        }.getOrElse {
            Log.e(TAG, "createDisplay 失败 flags=0x${flags.toString(16)}", it)
            -1
        }
    }

    /**
     * 告诉窗口管理器:这块副屏永远不要输入法。
     *
     * 不设的话,副屏上的 App 一要输入法,**机主主屏上的键盘就会弹出来**。
     * 这不是我们调了输入法 —— 整机只有一个 IME,实测 `mDisplayIdToShowIme` 恒为 0,
     * 所以任何一块屏上的输入框请求输入法,系统都只能弹到机主那块屏上。真机上就是
     * agent 点一下淘宝搜索栏,搜索页给输入框自动对焦,机主眼前的键盘就自己冒出来
     * 再落下去。机主看到的是「它在动我的输入法」。
     *
     * agent 自己从不需要输入法:填字走 ACTION_SET_TEXT,按键走 `input -d <屏>`。
     * 所以这块屏的正确策略就是 HIDE —— 副屏上的输入框该对焦对焦,只是不再有人
     * 替它把键盘弹到别人脸上。
     *
     * 权限上能不能设通,文档查不到,只能试;设不通就照旧,不影响别的。
     */
    private fun hideImeOn(displayId: Int) {
        val got = setImePolicy(displayId, DISPLAY_IME_POLICY_HIDE)
        if (got == POLICY_UNREADABLE)
            Log.w(TAG, "设不了副屏输入法策略,副屏上的输入框可能会把机主的键盘顶出来")
    }

    override fun setImePolicy(displayId: Int, policy: Int): Int = runCatching {
        val wm = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)!!
        fun read() = runCatching {
            wm.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
                .invoke(wm, displayId) as Int
        }.getOrDefault(POLICY_UNREADABLE)
        val before = read()
        wm.javaClass.getMethod(
            "setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(wm, displayId, policy)
        val after = read()
        Log.i(TAG, "副屏 $displayId 输入法策略 $before -> $after(0=本屏弹 1=弹到主屏 2=不弹)")
        after
    }.getOrElse { Log.w(TAG, "设不了副屏 $displayId 的输入法策略", it); POLICY_UNREADABLE }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displays) { displays.remove(displayId) }?.release()
    }

    /**
     * 这块屏还在吗。两个条件都要:我们手上还握着它,而且系统里也还认它。
     * 只看前者会在系统单方面回收之后仍然报活;只看后者会漏掉别人造的屏,
     * 而那种屏不归我们复用。
     */
    override fun displayAlive(displayId: Int): Boolean = runCatching {
        val held = synchronized(displays) { displays[displayId] } ?: return false
        held.display?.isValid == true && displayManager().getDisplay(displayId) != null
    }.getOrDefault(false)

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
        // systemMain() 内部要 new Handler(),而无参 Handler 绑的是**当前线程**的 Looper。
        // AIDL 的调用落在 binder 线程上,那种线程从来没有 Looper —— 只判断主 Looper
        // 存不存在是不够的,Shizuku 的 user service 进程主线程本来就有 Looper,
        // 守卫会直接跳过,然后在 binder 线程上炸掉。
        if (android.os.Looper.myLooper() == null) {
            if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper()
            else android.os.Looper.prepare()
        }
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

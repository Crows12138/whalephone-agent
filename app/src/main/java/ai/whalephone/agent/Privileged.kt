package ai.whalephone.agent

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
            // AIDL 一改就要升版本号,否则 Shizuku 会复用旧的 user service 进程 ——
            // 那个进程里没有新方法,调用会直接抛。
            .version(5)

        // 残留的旧桥进程由新桥自己收掉,见 ShellBridge.reapOrphans ——
        // 这里试过 Shizuku.unbindUserService(args, null, remove=true),没绑的状态下无效。
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

    /** 命令里带模型给的字符串时用这个,不走 sh,见 IShellBridge.execArgs */
    fun execArgs(vararg argv: String): String =
        bridge?.execArgs(argv.toMutableList()) ?: "NO_BRIDGE"

    /**
     * 任务收尾时,如果机主那块屏没有获焦窗口,把焦点推回去一次。
     *
     * 这个机制被否过一次,而且否得对:上一版在任务**进行中**每 250 毫秒往主屏灌一次
     * 空按键。那本身就是个输入事件 —— 主屏没有获焦窗口时它送不进去,派发超时 5 秒
     * 就是一次 ANR,弹在机主脸上。那一版是自己制造了要解决的那个问题。
     *
     * 这一版不同在三处,缺一不可:
     *   - **只在 agent 接下来必定空闲的时刻做。** 上一版的致命处不在「还焦点」本身,
     *     在于它挑了 `am start` 冷启动期反复灌 —— 那正是焦点被来回抢的时候,
     *     注入落不了地。现在只在两个时刻调用:任务收尾,以及每一轮去问模型之前
     *     (那几秒 agent 什么都不做,没有竞争)。
     *   - 只做一次,不循环。循环是上一版 ANR 的直接来源。
     *   - 只在主屏确实没有获焦窗口时做。没坏就不修。
     *
     * 为什么值得冒这个险:实测任务全程 89% 的时间主屏都没有获焦窗口
     * (`scripts/tests/measure-focus-gap.sh`,19 秒的任务里 17 秒是),
     * 也就是说机主在这段时间里碰一下自己的屏幕就可能 ANR。风险窗口是整个任务,
     * 不是一个边角情况。
     *
     * 为什么非补不可:副屏按设计在任务结束后保留(跨任务复用,少一次抖动),而副屏上
     * 有可获焦窗口,它会一直占着全局焦点。于是主屏 `mCurrentFocus=null` 长期存在,
     * 机主下一次按键正落在「Application does not have a focused window」上。
     * 真机上实测到过这个状态:任务跑完、机主的 App 还在屏上,但主屏没有获焦窗口。
     */
    fun handBackFocusIfLost(): String {
        val f = ownerFocus() ?: return "读不到主屏焦点,不动"
        if (f.isNotEmpty()) return "主屏本来就有焦点,不动($f)"
        execArgs("input", "-d", "0", "keyevent", "0")
        Thread.sleep(400)
        return "主屏原本没有获焦窗口,推回一次 -> ${ownerFocus()?.ifEmpty { "还是没有" } ?: "读不到"}"
    }

    /**
     * 主屏当前的获焦窗口。`""` = 确实没有,`null` = 读不到。
     * 这两者必须分开:读不到的时候动手就是在瞎猜,见 A11yGate 里同样的教训。
     */
    private fun ownerFocus(): String? {
        val out = exec(
            "dumpsys window displays | awk '/Display: mDisplayId=0 /,/mCurrentFocus=/'" +
                " | grep -m1 mCurrentFocus"
        ).trim()
        if (out.startsWith("NO_BRIDGE") || out.startsWith("EXEC_FAIL")) return null
        if (!out.contains("mCurrentFocus=")) return null
        val v = out.substringAfter("mCurrentFocus=").trim()
        return if (v == "null") "" else v
    }

    /** 这块副屏还在吗。问属主 —— app 自己看不见私有虚拟屏,见 AIDL 里的说明。 */
    fun displayAlive(displayId: Int): Boolean =
        runCatching { bridge?.displayAlive(displayId) == true }.getOrDefault(false)

    /** 改副屏的输入法策略(0=本屏弹 1=弹到主屏 2=不弹)。返回改完的实际值,失败 -99。 */
    fun setImePolicy(displayId: Int, policy: Int): Int =
        runCatching { bridge?.setImePolicy(displayId, policy) ?: -99 }.getOrDefault(-99)

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
            if (id < 0) continue
            // 造出来了不等于能用:无障碍看不见这块屏的话,agent 是瞎的。
            //
            // 原生 AOSP 的 AccessibilityManagerService 会直接排除**私有**虚拟屏
            // (isValidDisplay:TYPE_VIRTUAL 且带 FLAG_PRIVATE 就不跟踪),
            // 而不带 PUBLIC 造出来的屏一定是私有的。三星放宽了这条,所以在机主那台
            // 手机上一直没暴露 —— 换台原生机器整套东西就是瞎的,而且不会报错,
            // 只会「什么都读不到」。在模拟器上撞到才发现。
            //
            // 所以造完当场问一句无障碍看不看得见,看不见就补上 PUBLIC 重造。
            // 判据是能力本身,不是我对某个 ROM 的假设。
            if (a11ySees(id)) return id to f
            Log.w(TAG, "无障碍看不见副屏 $id(flags=0x${f.toString(16)}),补 PUBLIC 重造")
            bridge?.releaseDisplay(id)
            val pub = f or ShellBridge.PUBLIC
            val id2 = bridge?.createDisplay(w, h, dpi, surface, pub) ?: -1
            if (id2 >= 0 && a11ySees(id2)) return id2 to pub
            if (id2 >= 0) bridge?.releaseDisplay(id2)
        }
        return -1 to 0
    }

    /** 无障碍能不能读到这块屏。造屏之后必须问一次 —— 读不到的话 agent 没有眼睛。 */
    private fun a11ySees(displayId: Int): Boolean {
        val svc = EyesAndHands.instance ?: return true   // 服务还没连上,这里判断不了,不拦
        // 屏刚造出来,窗口管理器和无障碍那边都要一会儿才跟上。
        // 等得宽一点是有代价考虑的:等太短会把「还没注册上」误判成「看不见」,
        // 于是白白补 PUBLIC 重造一块 —— 在本来就看得见私有屏的 ROM(三星)上,
        // 那是平白把屏改成公开的,可能牵动 DeX 之类的行为。宁可多等两秒。
        // 「这块屏不在列表里」有两种原因,必须分开:
        //   a. 这台 ROM 的无障碍看不见私有虚拟屏(原生 AOSP 就是这样)—— 真的要补 PUBLIC
        //   b. 无障碍服务自己刚连上,窗口列表还是空的 —— 再等等就有了
        // 不分开的后果真机上撞到了:特权桥重启、无障碍跟着重连,那一瞬列表是空的,
        // 于是把三星误判成「看不见」,平白把屏改成公开的。
        var sawAnything = false
        repeat(24) {
            val wins = svc.windowsOnAllDisplays
            if (wins.size() > 0) sawAnything = true
            if (wins.indexOfKey(displayId) >= 0) {
                Log.i(TAG, "无障碍看得见副屏 $displayId,保持私有屏")
                return true
            }
            Thread.sleep(250)
        }
        if (!sawAnything) {
            Log.w(TAG, "无障碍一块屏都报不出来 —— 是它还没就绪,不是看不见副屏,不改 flags")
            return true
        }
        return false
    }

    fun release(displayId: Int) { runCatching { bridge?.releaseDisplay(displayId) } }

    /**
     * 把全局焦点还给用户那块屏。
     *
     * 实测:造副屏、以及往副屏启 App,这两下会把 FocusedDisplayId 拽到副屏,
     * 并且**收起用户正在用的输入法**(mInputShown 从 true 变 false)。这是整套
     * 方案里唯一一处真正打扰到用户的地方。
     *
     * KEYCODE_UNKNOWN 是个空按键,界面上不产生任何效果,但它带着显示器维度进了
     * InputDispatcher,足以把焦点指针推回 0 号屏,输入法跟着回来。实测焦点和
     * mInputShown 都能恢复。
     *
     * (agent 后续的无障碍动作也会移动焦点指针,但那个**不需要**处理:输入投递
     * 是按屏走的,用户手指按在物理屏上产生的事件天然带 0 号屏的归属,照样落进
     * 他自己的输入框,而且会顺手把指针拽回来。实测 agent 连点 5 下之后用户接着
     * 打字,一个字都没丢。)
     */
}

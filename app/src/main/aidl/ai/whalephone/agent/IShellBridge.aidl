package ai.whalephone.agent;

import android.view.Surface;

/**
 * 运行在 shell UID 里的特权桥。
 * 由 Shizuku 的 user service 拉起 —— 进程是 app_process 起的,UID 2000,
 * 因此持有 ADD_TRUSTED_DISPLAY / INJECT_EVENTS 这类 app 拿不到的权限。
 */
interface IShellBridge {
    /** Shizuku 约定的固定 transaction id,不能改 */
    void destroy() = 16777114;

    /** 执行 shell 命令,返回 stdout+stderr。命令行会被 sh 解析,只许传固定字面量。 */
    String exec(String cmd) = 1;

    /**
     * 按 argv 直接 exec,不经过 sh。凡是命令里含有模型给的字符串,一律走这条 ——
     * 没有 shell 解析,就没有注入面。
     */
    String execArgs(in List<String> argv) = 4;

    /** 造一块受信虚拟显示器,返回 displayId;失败返回 -1 */
    int createDisplay(int w, int h, int dpi, in Surface surface, int flags) = 2;

    void releaseDisplay(int displayId) = 3;

    /**
     * 这块屏还在吗。
     *
     * 必须由属主来答:副屏带 FLAG_PRIVATE,只有创建它的进程(shell)看得见。
     * app 侧拿自己的 DisplayManager 去 getDisplay(id) 永远返回 null ——
     * 那不是「屏没了」,是「你没资格看见它」。两者读数一样,后果差很远:
     * 前者该重造,后者重造就是白白多抢一次焦点、多收一次机主的键盘。
     */
    boolean displayAlive(int displayId) = 5;
}

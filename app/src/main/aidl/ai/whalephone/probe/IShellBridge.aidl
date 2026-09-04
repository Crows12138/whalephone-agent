package ai.whalephone.probe;

import android.view.Surface;

/**
 * 运行在 shell UID 里的特权桥。
 * 由 Shizuku 的 user service 拉起 —— 进程是 app_process 起的,UID 2000,
 * 因此持有 ADD_TRUSTED_DISPLAY / INJECT_EVENTS 这类 app 拿不到的权限。
 */
interface IShellBridge {
    /** Shizuku 约定的固定 transaction id,不能改 */
    void destroy() = 16777114;

    /** 执行 shell 命令,返回 stdout+stderr */
    String exec(String cmd) = 1;

    /** 造一块受信虚拟显示器,返回 displayId;失败返回 -1 */
    int createDisplay(int w, int h, int dpi, in Surface surface, int flags) = 2;

    void releaseDisplay(int displayId) = 3;
}

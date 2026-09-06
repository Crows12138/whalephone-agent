package ai.whalephone;

/**
 * 副屏上的 App 一要输入法,机主主屏上的键盘就会弹出来 —— 因为整机只有一个 IME,
 * 实测 mDisplayIdToShowIme 恒为 0。这个探针试的是能不能按屏关掉:
 * IWindowManager.setDisplayImePolicy(displayId, 2 = HIDE)。
 *
 * 关键问题是权限:这个调用在 WMS 里走 enforceTaskPermission(MANAGE_ACTIVITY_TASKS),
 * shell(uid 2000)有没有这条权限,文档上查不到,只能试。
 *
 * 用法: CLASSPATH=imepolicy.dex app_process /data/local/tmp \
 *         ai.whalephone.ImePolicy <displayId> [policy]
 *       policy: 0=LOCAL 1=FALLBACK(默认,弹到主屏) 2=HIDE
 */
public class ImePolicy {
    public static void main(String[] args) {
        int id = Integer.parseInt(args[0]);
        int policy = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        Object wm;
        try {
            wm = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
        } catch (Throwable t) { System.out.println("拿不到 WMS: " + root(t)); return; }
        System.out.println("设置前 = " + get(wm, id));
        try {
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                    .invoke(wm, id, policy);
            System.out.println("setDisplayImePolicy(" + id + ", " + policy + ") 调用没抛异常");
        } catch (Throwable t) { System.out.println("设置失败: " + root(t)); return; }
        System.out.println("设置后 = " + get(wm, id));
    }

    static String get(Object wm, int id) {
        try {
            return String.valueOf(
                wm.getClass().getMethod("getDisplayImePolicy", int.class).invoke(wm, id));
        } catch (Throwable t) { return "读不到(" + root(t) + ")"; }
    }

    static Throwable root(Throwable t) { return t.getCause() != null ? t.getCause() : t; }
}

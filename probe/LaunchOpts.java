package ai.whalephone;

import android.content.ComponentName;
import android.content.Intent;
import java.lang.reflect.Method;

/**
 * 带 ActivityOptions 往副屏启 App —— 用来找一种不收起机主键盘的启动方式。
 *
 * 背景:`am start --display N` 是全流程里唯一会收起机主软键盘的操作(逐个操作归因表)。
 * 根子是新的可获焦窗口出现,窗口管理器重算全局焦点,主屏丢掉自己的获焦窗口,
 * IMMS 跟着收键盘。`am` 命令行没有暴露能改变这个行为的选项,但 ActivityOptions 有几个
 * 隐藏方法(这台机器上探到了 setAvoidMoveToFront / setTransientLaunch)。
 * 名字对得上不等于语义对得上,所以做成可切换的几组,一组一组量。
 *
 * 用法: CLASSPATH=launchopts.dex app_process /data/local/tmp \
 *         ai.whalephone.LaunchOpts <displayId> <包名> <模式>
 *   模式 0 = 只 setLaunchDisplayId(对照组,等价于 am start --display)
 *        1 = 加 setAvoidMoveToFront
 *        2 = 加 setTransientLaunch
 *        3 = 两个都加
 */
public class LaunchOpts {

    public static void main(String[] args) throws Exception {
        int display = Integer.parseInt(args[0]);
        String pkg = args[1];
        int mode = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();

        Class<?> aoc = Class.forName("android.app.ActivityOptions");
        Object opts = aoc.getMethod("makeBasic").invoke(null);
        aoc.getMethod("setLaunchDisplayId", int.class).invoke(opts, display);
        if ((mode & 1) != 0) { aoc.getMethod("setAvoidMoveToFront").invoke(opts); System.out.println("已加 setAvoidMoveToFront"); }
        if ((mode & 2) != 0) { aoc.getMethod("setTransientLaunch").invoke(opts); System.out.println("已加 setTransientLaunch"); }
        android.os.Bundle bundle = (android.os.Bundle) aoc.getMethod("toBundle").invoke(opts);

        ComponentName cn = resolve(pkg);
        if (cn == null) { System.out.println("解析不出 " + pkg + " 的启动组件"); return; }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

        Object atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null);
        Method start = null;
        for (Method m : atm.getClass().getMethods())
            if (m.getName().equals("startActivityAsUser")) {
                System.out.println("  候选 startActivityAsUser " + m.getParameterTypes().length + " 参");
                if (start == null || m.getParameterTypes().length > start.getParameterTypes().length) start = m;
            }
        if (start == null) { System.out.println("没有 startActivityAsUser"); return; }

        Class<?>[] ps = start.getParameterTypes();
        Object[] a = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            Class<?> t = ps[i];
            if (t == String.class) a[i] = null;
            else if (t == int.class) a[i] = 0;
            else a[i] = null;
        }
        // 按类型把该填的填上。不同 API 级参数个数不同,按类型认比按位置认稳。
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == Intent.class) a[i] = intent;
            else if (ps[i] == android.os.Bundle.class) a[i] = bundle;
        }
        // callingPackage 是第一个 String;userId 是最后一个 int
        for (int i = 0; i < ps.length; i++) if (ps[i] == String.class) { a[i] = "com.android.shell"; break; }
        for (int i = ps.length - 1; i >= 0; i--) if (ps[i] == int.class) { a[i] = 0; break; }

        Object r = start.invoke(atm, a);
        System.out.println("startActivityAsUser -> " + r + "  (0 = 成功)");
    }

    static ComponentName resolve(String pkg) throws Exception {
        Object pm = Class.forName("android.app.ActivityThread")
                .getMethod("getPackageManager").invoke(null);
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        for (Method m : pm.getClass().getMethods()) {
            if (!m.getName().equals("resolveIntent")) continue;
            Class<?>[] ps = m.getParameterTypes();
            Object[] a = new Object[ps.length];
            for (int k = 0; k < ps.length; k++) {
                if (ps[k] == Intent.class) a[k] = i;
                else if (ps[k] == String.class) a[k] = null;
                else if (ps[k] == int.class) a[k] = 0;
                else if (ps[k] == long.class) a[k] = 0L;
                else a[k] = null;
            }
            Object ri = m.invoke(pm, a);
            if (ri == null) continue;
            Object ai = ri.getClass().getField("activityInfo").get(ri);
            String p = (String) ai.getClass().getField("packageName").get(ai);
            String n = (String) ai.getClass().getField("name").get(ai);
            return new ComponentName(p, n);
        }
        return null;
    }
}

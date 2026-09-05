package ai.whalephone;

/**
 * 探 ActivityOptions 在这台机器上真实暴露了什么。
 *
 * 为什么要探:往副屏启 App 是全流程里唯一会收起机主键盘的操作。根子是新窗口出现
 * 触发全局焦点重算。ActivityOptions 里有几个名字听起来能绕开这次重算的隐藏方法,
 * 但「名字对得上」不等于「语义对得上」,而且各家 ROM 未必都有。先看这台机器给了什么。
 *
 * 用法: CLASSPATH=actopts.dex app_process /data/local/tmp ai.whalephone.ActOpts
 */
public class ActOpts {
    public static void main(String[] args) {
        System.out.println("== ActivityOptions 里和焦点/显示器有关的方法 ==");
        try {
            Class<?> c = Class.forName("android.app.ActivityOptions");
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("display") || n.contains("front") || n.contains("transient")
                        || n.contains("focus") || n.contains("taskalwaysontop")
                        || n.contains("windowingmode") || n.contains("taskdisplayarea")) {
                    StringBuilder sb = new StringBuilder("  ");
                    sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(ps[i].getSimpleName());
                    }
                    System.out.println(sb.append(')'));
                }
            }
        } catch (Throwable t) { System.out.println("  " + t); }

        System.out.println();
        System.out.println("== 这块 ROM 的 per-display focus 开关 ==");
        try {
            Class<?> r = Class.forName("com.android.internal.R$bool");
            java.lang.reflect.Field f = r.getDeclaredField("config_perDisplayFocusEnabled");
            f.setAccessible(true);
            int id = f.getInt(null);
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object th = at.getMethod("systemMain").invoke(null);
            android.content.Context ctx = (android.content.Context) at.getMethod("getSystemContext").invoke(th);
            System.out.println("  config_perDisplayFocusEnabled = " + ctx.getResources().getBoolean(id));
        } catch (Throwable t) { System.out.println("  读不到: " + t); }
    }
}

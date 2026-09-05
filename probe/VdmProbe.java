package ai.whalephone;

import android.content.Context;
import android.content.ContextWrapper;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 探路:能不能用 VirtualDeviceManager 造一块**在自己的 display group 里**的副屏。
 *
 * 为什么要这条路:机主的输入法被收起,根子是副屏和主屏同在 displayGroupId 0,
 * 窗口焦点整组共享 —— 副屏上一出现新窗口,主屏的 IME 就没了。
 * 直接给 createVirtualDisplay 传 DEVICE_DISPLAY_GROUP(1<<15) 会被系统静默丢弃。
 *
 * 而 Android 14 起,独立的 display group 是由**虚拟设备**提供的:一个 VirtualDevice
 * 自带独立的显示器组、独立焦点和独立输入。shell(uid 2000)实测持有
 * CREATE_VIRTUAL_DEVICE(granted=true, GRANTED_BY_ROLE),所以权限这关是过的。
 *
 * 卡点在于这套 API 通常要求先有一个 CompanionDeviceManager 关联(associationId)。
 * 这个探针只做一件事:把这台机器上真实暴露出来的方法签名打出来,
 * 看有没有不需要 association 的入口 —— 猜没有用,得看这台 ROM 到底给了什么。
 *
 * 用法: CLASSPATH=vdmprobe.dex app_process /data/local/tmp ai.whalephone.VdmProbe
 */
public class VdmProbe {

    public static void main(String[] args) {
        try {
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();
        } catch (Throwable ignored) {}

        show("android.companion.virtual.VirtualDeviceManager");
        show("android.companion.virtual.VirtualDeviceParams");
        show("android.companion.virtual.VirtualDeviceParams$Builder");
        show("android.companion.virtual.VirtualDeviceManager$VirtualDevice");

        System.out.println();
        System.out.println("== IVirtualDeviceManager 的 AIDL 签名 ==");
        try {
            Class<?> c = Class.forName("android.companion.virtual.IVirtualDeviceManager");
            for (Method m : c.getDeclaredMethods())
                if (m.getName().contains("createVirtualDevice")) System.out.println("  " + sig(m));
        } catch (Throwable t) { System.out.println("  " + t); }

        System.out.println();
        System.out.println("== 能不能从 context 拿到 VirtualDeviceManager ==");
        try {
            Context sys = systemContext();
            Object vdm = sys.getSystemService("virtualdevice");
            System.out.println("  getSystemService(\"virtualdevice\") -> " + vdm);
        } catch (Throwable t) {
            System.out.println("  失败: " + t);
        }

        System.out.println();
        System.out.println("== 现有的 CompanionDeviceManager 关联 ==");
        try {
            Class<?> smc = Class.forName("android.os.ServiceManager");
            Object b = smc.getMethod("getService", String.class).invoke(null, "companiondevice");
            System.out.println("  companiondevice binder = " + b);
        } catch (Throwable t) {
            System.out.println("  失败: " + t);
        }
    }

    /** 把一个类真实暴露的公开方法签名打出来 —— 文档和这台 ROM 未必一致 */
    static void show(String name) {
        System.out.println();
        System.out.println("== " + name + " ==");
        try {
            Class<?> c = Class.forName(name);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("create") || n.startsWith("get") || n.startsWith("set")
                        || n.startsWith("build") || n.startsWith("add")) {
                    System.out.println("  " + sig(m));
                }
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                System.out.println("  <init>" + params(k.getParameterTypes()));
            }
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().startsWith("POLICY") || f.getName().startsWith("DEVICE_POLICY")
                        || f.getName().startsWith("LOCK") || f.getName().startsWith("NAVIGATION")) {
                    System.out.println("  常量 " + f.getName());
                }
            }
        } catch (Throwable t) {
            System.out.println("  这台机器上没有: " + t);
        }
    }

    static String sig(Method m) {
        return m.getReturnType().getSimpleName() + " " + m.getName() + params(m.getParameterTypes());
    }

    static String params(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    /** shell 进程里没有真正的 Context,借 ActivityThread 的系统 Context,并伪装成 com.android.shell */
    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
        };
    }
}

package ai.whalephone;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.view.Display;
import android.view.Surface;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * 走 VirtualDeviceManager 造副屏,目标是让机主的输入法**完全不受影响**。
 *
 * 为什么换这条路:直接 DisplayManager.createVirtualDisplay 造出来的屏,无论传什么 flag,
 * 实测都落在 displayGroupId 0 —— 和主屏同组,窗口焦点整组共享,副屏上一出现新窗口
 * (am start)机主的软键盘就被收起。DEVICE_DISPLAY_GROUP(1<<15)传进去会被静默丢弃。
 *
 * Android 14 起,独立的显示器组由**虚拟设备**提供。这套 API 还顺带给了几样正好对症的:
 *   setDisplayImePolicy(displayId, ...)  这块屏的输入法策略,可本地化或直接禁掉
 *   setLockState(ALWAYS_UNLOCKED)        自己的锁屏状态
 *   createVirtualTouchscreen(...)        绑在这块屏上的输入设备,注入不经过全局 InputDispatcher
 *
 * VirtualDeviceParams 是 @SystemApi,公开 android.jar 里没有,所以这里全用反射。
 *
 * 用法: CLASSPATH=vdmdisplay.dex app_process /data/local/tmp \
 *         ai.whalephone.VdmDisplay <associationId> <保持秒数> [imePolicy]
 *       imePolicy: 0=LOCAL(输入法开在副屏自己身上) 1=FALLBACK(回落主屏) 2=HIDE(副屏不要输入法)
 *
 * associationId 传负数 = 走**对照组**:不建虚拟设备,直接 DisplayManager 造一块同样 flags
 * 的屏。两组跑在同一个进程、同一套 hold 逻辑里,唯一的差别就是「有没有虚拟设备」,
 * 这样量出来的差值才能归因到虚拟设备本身。
 */
public class VdmDisplay {

    static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;

    public static void main(String[] args) throws Exception {
        int assoc = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int hold = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        int imePolicy = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        // 0 的话 setDisplayImePolicy 会报 "untrusted virtual display",而且拿不到独立显示器组
        int flags = args.length > 3 ? Integer.decode(args[3]) : 0x5e08;
        // Android 15 起虚拟设备可以自带输入法。机主用讯飞,就把三星 HoneyBoard 指给副屏,
        // 看 IMMS 是不是真按设备分会话 —— 如果是,机主那边的键盘就没有任何理由被收。
        String imeComp = args.length > 4 ? args[4] : "-";

        if (android.os.Looper.myLooper() == null) android.os.Looper.prepareMainLooper();
        Context ctx = systemContext();

        if (assoc < 0) { plain(ctx, hold, flags); return; }

        // 不能用 ctx.getSystemService("virtualdevice"):ContextWrapper 会把调用转给
        // 底层的 ContextImpl,于是 VirtualDeviceManager 拿到的是系统 Context,
        // binder 调用带出去的 AttributionSource 包名是 "android" ——
        // VDM 按调用方包名查关联,自然报 "No association with ID N"。
        // 自己用 (IVirtualDeviceManager, Context) 构造它,把伪装过的 Context 塞进去。
        Class<?> smc = Class.forName("android.os.ServiceManager");
        Object binder = smc.getMethod("getService", String.class).invoke(null, "virtualdevice");
        Class<?> stub = Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub");
        Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
        Class<?> iface = Class.forName("android.companion.virtual.IVirtualDeviceManager");
        Class<?> vdmCls = Class.forName("android.companion.virtual.VirtualDeviceManager");
        java.lang.reflect.Constructor<?> vdmCtor = vdmCls.getDeclaredConstructor(iface, Context.class);
        vdmCtor.setAccessible(true);
        Object vdm = vdmCtor.newInstance(svc, ctx);
        System.out.println("vdm = " + vdm + "  (自建,Context 包名=" + ctx.getPackageName() + ")");

        Class<?> bCls = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
        Object b = bCls.getConstructor().newInstance();
        b = bCls.getMethod("setName", String.class).invoke(b, "whalephone-agent-device");
        try {
            b = bCls.getMethod("setLockState", int.class).invoke(b, LOCK_STATE_ALWAYS_UNLOCKED);
        } catch (Throwable t) { System.out.println("setLockState 不可用: " + t); }
        if (!"-".equals(imeComp)) {
            try {
                b = bCls.getMethod("setInputMethodComponent", android.content.ComponentName.class)
                        .invoke(b, android.content.ComponentName.unflattenFromString(imeComp));
                System.out.println("setInputMethodComponent(" + imeComp + ") 已设置");
            } catch (Throwable t) {
                System.out.println("setInputMethodComponent 失败: " + (t.getCause() != null ? t.getCause() : t));
            }
        }
        Object params = bCls.getMethod("build").invoke(b);

        Class<?> pCls = Class.forName("android.companion.virtual.VirtualDeviceParams");

        // 直接调 AIDL,自己显式传 AttributionSource ——
        // 走 VirtualDeviceManager 包装类时它自己去取 context 的身份,取到的是 "android",
        // VDM 按包名查关联就查不到。这里把身份完全握在手上。
        Object idev = null;
        try {
            Class<?> asCls = android.content.AttributionSource.class;
            Class<?> alCls = Class.forName("android.companion.virtual.IVirtualDeviceActivityListener");
            Class<?> slCls = Class.forName("android.companion.virtual.IVirtualDeviceSoundEffectListener");
            Method mk2 = null;
            for (Method m : svc.getClass().getMethods())
                if (m.getName().equals("createVirtualDevice")) {
                    System.out.println("  候选: " + m.getParameterTypes().length + " 参 " + m);
                    if (m.getParameterTypes().length == 6) mk2 = m;
                }
            if (mk2 == null) { System.out.println("  没找到 6 参的 createVirtualDevice"); throw new IllegalStateException("no method"); }
            android.content.AttributionSource as =
                    new android.content.AttributionSource.Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell").build();
            // 两个 listener 不能传 null(AIDL 序列化时 NPE)。用动态代理造空实现:
            // 它们只在虚拟设备运行期间被回调,创建这一步只需要一个能 asBinder() 的对象。
            Object al = emptyListener(alCls);
            Object sl = emptyListener(slCls);
            idev = mk2.invoke(svc, new android.os.Binder(), as, assoc, params, al, sl);
            System.out.println("直调 AIDL 成功: " + idev);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("直调 AIDL 失败: " + c);
        }

        if (idev == null) { System.out.println("虚拟设备没建成"); return; }

        // 把裸 binder 包回好用的 VirtualDevice —— 它有 (Context, IVirtualDevice) 这个构造器
        Class<?> vdCls = Class.forName("android.companion.virtual.VirtualDeviceManager$VirtualDevice");
        Class<?> idevCls = Class.forName("android.companion.virtual.IVirtualDevice");
        java.lang.reflect.Constructor<?> k = vdCls.getDeclaredConstructor(Context.class, idevCls);
        k.setAccessible(true);
        Object device = k.newInstance(ctx, idev);
        System.out.println("deviceId = " + device.getClass().getMethod("getDeviceId").invoke(device));

        ImageReader reader = ImageReader.newInstance(1080, 2340, PixelFormat.RGBA_8888, 2);
        Method mk = null;
        for (Method m : device.getClass().getMethods())
            if (m.getName().equals("createVirtualDisplay") && m.getParameterTypes().length == 7) { mk = m; break; }
        Executor exec = Runnable::run;
        VirtualDisplay vd = (VirtualDisplay) mk.invoke(device,
                1080, 2340, 420, (Surface) reader.getSurface(), flags, exec, null);
        System.out.println("flags = 0x" + Integer.toHexString(flags));
        if (vd == null) { System.out.println("造屏失败"); return; }
        int id = vd.getDisplay().getDisplayId();
        System.out.println("HOLD displayId=" + id);

        // 这块屏的输入法策略。HIDE = 副屏根本不要输入法,
        // 机主那块屏上的 IME 就没有任何理由被动。
        try {
            device.getClass().getMethod("setDisplayImePolicy", int.class, int.class)
                    .invoke(device, id, imePolicy);
            System.out.println("setDisplayImePolicy(" + id + ", " + imePolicy + ") 成功");
        } catch (Throwable t) {
            System.out.println("setDisplayImePolicy 失败: " + (t.getCause() != null ? t.getCause() : t));
        }

        Thread.sleep(hold * 1000L);
        vd.release();
        reader.close();
        try { device.getClass().getMethod("close").invoke(device); } catch (Throwable ignored) {}
    }

    /** 对照组:老路子。DisplayManager 直接造屏,没有虚拟设备,其余一切相同。 */
    static void plain(Context ctx, int hold, int flags) throws Exception {
        java.lang.reflect.Constructor<?> c =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) c.newInstance(ctx);
        ImageReader reader = ImageReader.newInstance(1080, 2340, PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = dm.createVirtualDisplay(
                "whalephone-plain", 1080, 2340, 420, reader.getSurface(), flags);
        System.out.println("对照组 flags = 0x" + Integer.toHexString(flags));
        System.out.println("HOLD displayId=" + vd.getDisplay().getDisplayId());
        Thread.sleep(hold * 1000L);
        vd.release();
        reader.close();
    }

    /** 给一个 AIDL 接口造一个什么都不做的实现,只保证 asBinder() 返回同一个 Binder */
    static Object emptyListener(Class<?> iface) {
        final android.os.Binder token = new android.os.Binder();
        return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                (proxy, method, a) -> method.getName().equals("asBinder") ? token : null);
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context base = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new ContextWrapper(base) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            // 这一条才是关键:binder 调用带出去的身份取自这里,不是 getPackageName()
            @Override public android.content.AttributionSource getAttributionSource() {
                return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                        .setPackageName("com.android.shell")
                        .build();
            }
        };
    }
}

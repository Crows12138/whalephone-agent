package ai.whalephone;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.content.Context;
import android.view.Surface;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.media.Image;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 独立实验:shell UID 到底能拿到虚拟显示器的哪几个标志位。
 *
 * 用 app_process 跑,不经过 app、不经过 Shizuku —— 因为要量的是「shell 这个身份
 * 有没有这个权限」,任何中间层都只会引入额外的失败原因。scrcpy 也是这么跑的。
 *
 * 用法: CLASSPATH=probe.dex app_process /data/local/tmp ai.whalephone.FlagProbe <保持秒数>
 */
public class FlagProbe {

    static final int PUBLIC = 1;
    static final int OWN_CONTENT_ONLY = 1 << 3;
    static final int SHOW_SYSTEM_DECORATIONS = 1 << 9;
    static final int TRUSTED = 1 << 10;
    static final int OWN_DISPLAY_GROUP = 1 << 11;
    static final int ALWAYS_UNLOCKED = 1 << 12;
    static final int OWN_FOCUS = 1 << 14;

    static String name(int f) {
        switch (f) {
            case PUBLIC: return "PUBLIC";
            case OWN_CONTENT_ONLY: return "OWN_CONTENT_ONLY";
            case SHOW_SYSTEM_DECORATIONS: return "SHOW_SYSTEM_DECORATIONS";
            case TRUSTED: return "TRUSTED";
            case OWN_DISPLAY_GROUP: return "OWN_DISPLAY_GROUP";
            case ALWAYS_UNLOCKED: return "ALWAYS_UNLOCKED";
            case OWN_FOCUS: return "OWN_FOCUS";
            default: return "0x" + Integer.toHexString(f);
        }
    }

    /**
     * shell 身份下的 Context。系统 Context 的包名是 "android"(uid 1000),
     * 而我们是 uid 2000,DisplayManagerService 会拿包名反查 uid 做校验并拒掉。
     * 套一层把包名改成 shell 自己的包 —— scrcpy 走的也是这条路。
     */
    static class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
    }

    static DisplayManager displayManager(Context c) throws Exception {
        Constructor<?> ctor = DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return (DisplayManager) ctor.newInstance(c);
    }

    static Context ctx() throws Exception {
        if (android.os.Looper.getMainLooper() == null) android.os.Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method main = at.getMethod("systemMain");
        Object thread = main.invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    public static void main(String[] args) throws Exception {
        int hold = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int forced = args.length > 1 ? Integer.decode(args[1]) : -1;
        Context c = new FakeContext(ctx());
        DisplayManager dm = displayManager(c);
        System.out.println("uid=" + android.os.Process.myUid());

        int w = 1080, h = 2340, dpi = 420;

        // 逐位加:先确认基线能成,再一位一位试,失败的那位就是这台机器不给的
        int[] ladder = { OWN_CONTENT_ONLY, TRUSTED, SHOW_SYSTEM_DECORATIONS,
                         OWN_FOCUS, OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED };
        int accepted = 0;
        for (int bit : (forced >= 0 ? new int[0] : ladder)) {
            int test = accepted | bit;
            ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            try {
                VirtualDisplay vd = dm.createVirtualDisplay("flagprobe", w, h, dpi, r.getSurface(), test);
                if (vd != null) {
                    System.out.println("  + " + name(bit) + "  可以  (displayId=" + vd.getDisplay().getDisplayId() + ")");
                    accepted = test;
                    vd.release();
                } else {
                    System.out.println("  - " + name(bit) + "  返回 null");
                }
            } catch (Throwable t) {
                System.out.println("  - " + name(bit) + "  被拒: " + t);
            }
            r.close();
            Thread.sleep(300);
        }

        if (forced >= 0) accepted = forced;
        System.out.println("使用标志位 = 0x" + Integer.toHexString(accepted));
        for (int bit : ladder) if ((accepted & bit) != 0) System.out.println("    " + name(bit));

        if (hold > 0) {
            ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            VirtualDisplay vd = dm.createVirtualDisplay("whalephone-agent", w, h, dpi, r.getSurface(), accepted);
            System.out.println("HOLD displayId=" + vd.getDisplay().getDisplayId() + " 保持 " + hold + " 秒");
            System.out.flush();
            // 持屏期间不断把最新一帧写成 PNG。这样「看副屏」就是读一个文件,
            // 不用 scrcpy 录屏再抽帧 —— 那条路只是为了在电脑上肉眼看,
            // 且 screencap 根本抓不到虚拟屏(它只认 SurfaceFlinger 的物理 ID)。
            for (int i = 0; i < hold * 2; i++) {
                Thread.sleep(500);
                grab(r, "/data/local/tmp/vd.png");
            }
            vd.release();
            r.close();
            System.out.println("released");
        }
        System.exit(0);
    }

    static void grab(ImageReader r, String path) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            Image.Plane p = img.getPlanes()[0];
            int rowStride = p.getRowStride(), pixelStride = p.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
            int padded = rowStride / pixelStride;
            Bitmap bmp = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(p.getBuffer());
            Bitmap out = (padded == w) ? bmp : Bitmap.createBitmap(bmp, 0, 0, w, h);
            FileOutputStream fos = new FileOutputStream(path + ".tmp");
            out.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.close();
            new java.io.File(path + ".tmp").renameTo(new java.io.File(path));
        } catch (Throwable t) {
            System.out.println("grab 失败: " + t);
        } finally {
            img.close();
        }
    }
}

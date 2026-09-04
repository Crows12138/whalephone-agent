package ai.whalephone.probe

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * agent 专用的那块屏,以及它的画面出口。
 *
 * 虚拟显示器必须有一个 Surface 承接画面。之前的探针阶段是拿 scrcpy 录屏当水槽,
 * 再用 ffmpeg 抽帧 —— 那是为了在电脑上肉眼看,不是 agent 需要的。这里换成
 * ImageReader:画面直接落在 app 自己的内存里,截图变成一次 acquireLatestImage,
 * 不依赖 scrcpy、不写视频文件、也绕开了 `screencap -d` 抓不到虚拟屏的限制
 * (screencap 只认 SurfaceFlinger 的物理显示器 ID,实测对虚拟屏返回 80 字节空图)。
 *
 * 注意:agent 主要靠无障碍树感知,截图是给 WebView / Canvas / 游戏这类
 * 树里读不出东西的场景兜底,以及给演示视频提供「agent 那块屏在干什么」的画面。
 */
class AgentDisplay private constructor(
    val displayId: Int,
    val flags: Int,
    private val reader: ImageReader,
) {
    val width get() = reader.width
    val height get() = reader.height

    /** 这块屏实际拿到了哪几条保证 —— 降级过的话这里会少 */
    fun guarantees(): String = buildList {
        if (flags and ShellBridge.TRUSTED != 0) add("受信(可启第三方App)")
        if (flags and ShellBridge.OWN_FOCUS != 0) add("独立焦点(不抢用户焦点)")
        if (flags and ShellBridge.ALWAYS_UNLOCKED != 0) add("锁屏仍可用")
        if (flags and ShellBridge.OWN_CONTENT_ONLY != 0) add("不镜像主屏")
        if (flags and ShellBridge.SHOULD_SHOW_SYSTEM_DECORATIONS != 0) add("有独立系统装饰")
    }.joinToString(" / ")

    fun capture(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return null
        return try {
            val plane = img.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val padding = rowStride - pixelStride * width
            val bmp = Bitmap.createBitmap(width + padding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (padding == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, width, height)
        } catch (t: Throwable) {
            Log.e(TAG, "capture 失败", t); null
        } finally {
            img.close()
        }
    }

    fun captureTo(path: String): Boolean {
        val bmp = capture() ?: return false
        return runCatching {
            FileOutputStream(File(path)).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            true
        }.getOrDefault(false)
    }

    fun release() {
        Privileged.release(displayId)
        runCatching { reader.close() }
    }

    companion object {
        private const val TAG = "WPDisplay"

        /**
         * 尺寸取和主屏一致 —— 很多 App 会按屏幕比例做布局,尺寸差太多容易触发
         * 平板布局或者干脆不适配。dpi 同理。
         */
        fun create(w: Int, h: Int, dpi: Int): AgentDisplay? {
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val (id, flags) = Privileged.createAgentDisplayBestEffort(w, h, dpi, reader.surface)
            if (id < 0) { reader.close(); Log.e(TAG, "造屏失败"); return null }
            val d = AgentDisplay(id, flags, reader)
            Log.i(TAG, "agent 屏 id=$id ${w}x$h@$dpi  保证: ${d.guarantees()}")
            return d
        }
    }
}

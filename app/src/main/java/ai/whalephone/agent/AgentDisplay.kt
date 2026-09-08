package ai.whalephone.agent

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
 * 不依赖 scrcpy,也不写视频文件。
 *
 * `screencap -d` 也能截虚拟屏,但要传 SurfaceFlinger 的显示器 ID —— 那个 ID 每次
 * 造屏都变,得先 dumpsys 解析一次。ImageReader 是自己持有 Surface,不走 shell、
 * 不解析任何东西,取景窗要连续出帧,这条更直接。
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
        // 原生 Android 16 上实测有效:机主的屏和副屏能同时各自持有焦点窗口,
        // 机主的输入法不受影响。三星那台机器上没观察到同样的效果,原因未查。
        if (flags and ShellBridge.OWN_FOCUS != 0) add("独立焦点")
        if (flags and ShellBridge.STEAL_TOP_FOCUS_DISABLED != 0) add("不抢顶层焦点")
        if (flags and ShellBridge.ALWAYS_UNLOCKED != 0) add("锁屏仍可用")
        if (flags and ShellBridge.OWN_CONTENT_ONLY != 0) add("不镜像主屏")
        if (flags and ShellBridge.SHOULD_SHOW_SYSTEM_DECORATIONS != 0) add("有独立系统装饰")
    }.joinToString(" / ")

    /**
     * 取景窗那条路复用的位图。
     *
     * 1080x2340 的 ARGB_8888 是 10 MB —— 取景窗一秒抓五帧,每帧新分配一张的话
     * 是 50 MB/s 的垃圾,GC 会把机主那块屏一起卡住(这个 app 的全部意义就是不卡他)。
     * 所以连续取帧走 [captureLive],复用同一张;一次性截图仍然走 [capture],
     * 那条要把位图交出去,不能被下一帧覆盖。
     */
    private var live: Bitmap? = null

    /** 连续取帧用。返回的位图**下一次调用就会被覆盖**,只能当场画掉,不能存。 */
    @Synchronized
    fun captureLive(): Bitmap? {
        val img = reader.acquireLatestImage() ?: return live
        return try {
            val plane = img.planes[0]
            val stride = plane.rowStride / plane.pixelStride
            val b = live?.takeIf { it.width == stride && it.height == height }
                ?: Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888).also { live = it }
            b.copyPixelsFromBuffer(plane.buffer)
            b
        } catch (t: Throwable) {
            Log.e(TAG, "captureLive 失败", t); null
        } finally {
            img.close()
        }
    }

    /** 位图右边可能多出 rowStride 的补白,画的时候要按这个宽度裁 */
    val liveVisibleWidth get() = width

    /**
     * 这一帧的 JPEG,给视觉模型看。
     *
     * 用 [captureLive] 而不是 [capture]:后者在「这一瞬间没有新帧」时返回 null,
     * 而界面静止的时候恰恰不会有新帧 —— 那正是最需要看图的时刻(树读不出东西,
     * 页面也不动)。captureLive 在没有新帧时把上一帧还回来。
     *
     * 缩到长边 [maxDim]:视觉模型按像素收费也按像素限流,原图 1080x2340 既贵又常常
     * 超过厂商的单图上限。模型输出的是归一化坐标,所以缩放不影响点击精度。
     */
    @Synchronized
    fun frameJpeg(
        maxDim: Int = 1120,
        quality: Int = 80,
        /** 要在图上标出来的元素:序号 -> 它在这块屏上的位置(屏幕像素) */
        marks: List<Pair<Int, android.graphics.Rect>> = emptyList(),
    ): ByteArray? {
        val src = captureLive() ?: return null
        return runCatching {
            // captureLive 给的位图右边带 rowStride 补白,先裁掉
            val cut = if (src.width == width) src
            else android.graphics.Bitmap.createBitmap(src, 0, 0, width, height)
            val scale = maxDim.toFloat() / maxOf(cut.width, cut.height)
            val out = if (scale >= 1f) cut else android.graphics.Bitmap.createScaledBitmap(
                cut, (cut.width * scale).toInt(), (cut.height * scale).toInt(), true)
            val marked = if (marks.isEmpty()) out else drawMarks(out, marks, minOf(scale, 1f))
            java.io.ByteArrayOutputStream().also {
                marked.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it)
            }.toByteArray()
        }.onFailure { Log.w(TAG, "这一帧压不成 JPEG", it) }.getOrNull()
    }

    /**
     * 把元素序号画到截图上(Set-of-Mark)。
     *
     * 解决的是一个具体的断裂:淘宝的商品规格弹层里那些按钮**在无障碍树里有节点、
     * 有精确坐标,却既没有 text 也没有 contentDescription** —— 元素列表里是一排
     * 「[12] View "—" 可点」,模型看得见图上的「确定」,也看得见列表里的序号,
     * 但两边对不上。对不上它就放弃列表去猜像素坐标,实测猜偏率约一半
     * (点到 y=57 的屏幕顶端,而确认按钮在 y=958)。
     *
     * 把序号画到图上,这座桥就通了:模型看图认出「确定」,读出它旁边的号,
     * 输出 click [号],坐标由系统从节点里取 —— 一次都不用猜。这是这个领域的
     * 标准做法(Set-of-Mark;Mobile-Agent / SeeAct / UFO2 都走这条)。
     *
     * 画在**缩放之后**的图上:标号大小要按最终送给模型的那张图来定,先画后缩会把
     * 数字缩糊,而糊掉的标号比没有标号更糟 —— 模型会读错成另一个元素的号。
     */
    private fun drawMarks(
        bmp: Bitmap,
        marks: List<Pair<Int, android.graphics.Rect>>,
        scale: Float,
    ): Bitmap = runCatching {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return bmp
        val c = android.graphics.Canvas(out)
        val box = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
            color = 0xFFFF3B30.toInt()
            isAntiAlias = true
        }
        val chip = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.FILL
            color = 0xFFFF3B30.toInt()
            isAntiAlias = true
        }
        val num = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 22f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for ((i, r) in marks) {
            val l = r.left * scale
            val t = r.top * scale
            val rr = r.right * scale
            val b = r.bottom * scale
            if (rr - l < 4f || b - t < 4f) continue          // 太小的画上去只是噪点
            c.drawRect(l, t, rr, b, box)
            val label = i.toString()
            val w = num.measureText(label) + 10f
            // 标号贴在元素左上角内侧,贴外侧会被相邻元素盖住,也会跑出画面
            val cx = l.coerceIn(0f, out.width - w)
            val cy = t.coerceIn(0f, out.height - 26f)
            c.drawRect(cx, cy, cx + w, cy + 26f, chip)
            c.drawText(label, cx + 5f, cy + 19f, num)
        }
        out
    }.onFailure { Log.w(TAG, "标号画不上去,退回原图", it) }.getOrDefault(bmp)

    @Synchronized
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

    /** 先写临时文件再改名。直接写目标文件的话,外面拉取时会撞上写了一半的 PNG。 */
    @Synchronized
    fun captureTo(path: String): Boolean {
        val bmp = capture() ?: return false
        return runCatching {
            val tmp = File("$path.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            tmp.renameTo(File(path))
        }.getOrDefault(false)
    }

    @Synchronized
    fun release() {
        Privileged.release(displayId)
        runCatching { reader.close() }
        live?.recycle(); live = null
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
            // 造屏这一下也会把顶层焦点屏切到副屏上(带系统装饰的受信屏上会自己冒出
            // 桌面窗口:三星是 DeX 桌面)。避让在调用方 AgentService 里做 ——
            // 放那儿是因为副屏跨任务复用,只有真要新造的那一次才需要等。
            Log.i(TAG, "agent 屏 id=$id ${w}x$h@$dpi  保证: ${d.guarantees()}")
            return d
        }
    }
}

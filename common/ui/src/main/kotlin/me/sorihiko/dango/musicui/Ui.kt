package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** 网易云红（和菜单里 Music 分类同色） */
internal val RED = Color.fromHex("#FF375F")

/** 一块可点击区域和点下去要做的事 */
internal typealias Hit = Pair<LayoutBounds, () -> Unit>

internal fun fmtTime(ms: Long): String {
    val s = max(0L, ms / 1000)
    return "${s / 60}:${"%02d".format(s % 60)}"
}

/** 输入框光标：每半秒闪一次 */
internal fun caretVisible(): Boolean = (System.currentTimeMillis() / 500) % 2 == 0L

/**
 * 播放器界面共用的绘制工具和每帧状态（鼠标位置、缩放、点击区域、切页动画）。
 * 各个页面 / 部件都通过它来画东西、登记点击区域。
 */
internal class Ui {

    val ctx: GuiCanvas get() = ScreenState.context!!

    /** 界面缩放：按 1280×800 的逻辑分辨率画，再整体缩放到窗口 */
    var scale = 1f
    var lw = 0f
    var lh = 0f
    var mx = 0
    var my = 0
    /** 上一帧到这一帧的毫秒数（动画用） */
    var frameDt = 16f
    /** 当前页面开始入场动画的时间 */
    var pageAt = 0L

    /** 这一帧登记的点击区域，按登记顺序优先 */
    val hits = mutableListOf<Hit>()

    /** 中英混排：英文用菜单字体，中日韩 / 符号用系统字体（玻璃层画完后统一画） */
    val mt = MixedText()

    // ---------------------------------------------------------------- 文字

    /** y 是文字顶部 */
    fun text(s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    /** 图标符号（⌂ ≡ ♫ ♥ ↻ ⇄ ▶ ◀ …），按中心点放 */
    fun icon(s: String, cx: Float, cy: Float, size: Float, color: Color) =
        mt.icon(ctx, s, cx, cy, size, color)

    fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String =
        mt.trim(s, maxW, size, font)

    fun wrap2(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): List<String> =
        mt.wrap2(s, maxW, size, font)

    // ---------------------------------------------------------------- 裁剪

    private fun scissor(b: LayoutBounds) {
        ctx.scissor(
            (b.x * scale).toInt(),
            (DangoClient.platform.windowHeight - b.bottom * scale).toInt(),
            max(0, (b.width * scale).toInt()),
            max(0, (b.height * scale).toInt()),
        )
    }

    fun beginClip(b: LayoutBounds) {
        mt.clip = b
        scissor(b)
    }

    fun endClip() {
        mt.clip = null
        ctx.noScissor()
    }

    // ---------------------------------------------------------------- 图形 / 控件

    /**
     * 浮在内容上面的东西（底部播放栏）占的地方：画内容时，鼠标在这里不算悬停在下面的内容上。
     * 画播放栏本身时设回 null
     */
    var occluder: LayoutBounds? = null
    /** 内容区底部被播放栏盖住的高度：能滚动的页面多留这么多，最后一项才能滚到播放栏上面 */
    var bottomInset = 0f

    fun hover(b: LayoutBounds) = b.contains(mx, my) && occluder?.contains(mx, my) != true

    fun card(b: LayoutBounds, color: Color, radius: Float = 10f) =
        ctx.drawShape(b, ShapeStyle(color, radius))

    fun drawCover(b: LayoutBounds, tex: Texture?, radius: Float = 8f) {
        if (tex != null) ctx.drawImage(b, tex) else card(b, Theme.TRACK, radius)
    }

    /** 胶囊按钮：可选左侧图标 */
    fun pill(b: LayoutBounds, fill: Color, label: String, glyph: String? = null, action: () -> Unit) {
        ctx.drawShape(b, ShapeStyle(if (hover(b)) fill.copy(a = fill.a * 0.85f) else fill, b.height / 2f))
        val tw = width(label, 12f, Theme.FONT_SEMIBOLD) + (if (glyph != null) 18f else 0f)
        var tx = b.x + (b.width - tw) / 2f
        if (glyph != null) {
            icon(glyph, tx + 6f, b.y + b.height / 2f, 12f, Theme.TEXT_PRIMARY)
            tx += 18f
        }
        text(label, tx, b.y + b.height / 2f - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        hits += b to action
    }

    /**
     * 进度条：[fraction] 为 0..1 时按比例填充；为 null（不知道总量）时一段红色来回滑动，表示还在加载。
     */
    fun progressBar(b: LayoutBounds, fraction: Float?) {
        ctx.drawShape(b, ShapeStyle(Theme.TRACK, b.height / 2f))
        if (fraction != null) {
            val w = b.width * fraction.coerceIn(0f, 1f)
            if (w > 0.5f) ctx.drawShape(LayoutBounds(b.x, b.y, max(b.height, w), b.height), ShapeStyle(RED, b.height / 2f))
            return
        }
        val t = (System.currentTimeMillis() % 1400L) / 1400f
        val e = if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f
        val seg = b.width * 0.35f
        val start = -seg + (b.width + seg) * e
        val l = max(0f, start)
        val r = min(b.width, start + seg)
        if (r - l > 0.5f) ctx.drawShape(LayoutBounds(b.x + l, b.y, r - l, b.height), ShapeStyle(RED, b.height / 2f))
    }

    /** 每个开关自己的弹簧（按 [toggleSwitch] 的 key 记） */
    private val switchAnim = HashMap<Any, Spring>()

    /**
     * iOS 风格开关。给了 [key]（比如这个开关对应的设置值）就带动画：圆钮用弹簧滑过去，稍微冲过一点再回来，
     * 滑动时沿运动方向拉长一点（液体感），底色在灰和红之间渐变。
     */
    fun toggleSwitch(b: LayoutBounds, on: Boolean, clip: LayoutBounds, key: Any? = null, action: () -> Unit) {
        val target = if (on) 1f else 0f
        val anim = key?.let { switchAnim.getOrPut(it) { Spring(380f, 24f, target) } }
        val p = anim?.tick(target) ?: target
        val pc = p.coerceIn(0f, 1f)
        val off = Color(1f, 1f, 1f, if (hover(b)) 0.24f else 0.16f)
        val fill = Color(off.r + (RED.r - off.r) * pc, off.g + (RED.g - off.g) * pc, off.b + (RED.b - off.b) * pc, off.a + (RED.a - off.a) * pc)
        ctx.drawShape(b, ShapeStyle(fill, b.height / 2f))
        val k = b.height - 4f
        val stretch = min(6f, abs(anim?.v ?: 0f) * 0.9f)
        val travel = b.width - 4f - k
        val kx = b.x + 2f + travel * p.coerceIn(-0.08f, 1.08f) - (if ((anim?.v ?: 0f) > 0f) stretch else 0f)
        ctx.drawShape(LayoutBounds(kx, b.y + 2f, k + stretch, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        hits += b.expand(4f, 4f).intersect(clip) to action
    }

    // ---------------------------------------------------------------- 动画（照主菜单）
    //   右侧内容：标题从右边 12px 滑入淡入（320ms），条目从下面 18px 升起淡入（380ms，每个晚 35ms）

    fun easeOut(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    /** 指数缓动：每帧往 target 靠近，time 越大越慢 */
    fun approach(pos: Float, target: Float, timeMs: Float): Float = pos + (target - pos) * (1f - exp(-frameDt / timeMs))

    /** 把滚动位置缓动到 target；跳得太远（定位、拖滚动条、Home/End）直接到位，免得中间几百行封面全去下载 */
    fun easeScroll(pos: Float, target: Int, snapRows: Int): Float {
        if (abs(pos - target) > snapRows) return target.toFloat()
        val next = approach(pos, target.toFloat(), 70f)
        return if (abs(next - target) < 0.002f) target.toFloat() else next
    }

    fun pageProgress(delayMs: Float, durMs: Float): Float =
        easeOut((System.currentTimeMillis() - pageAt - delayMs) / durMs)

    inline fun shifted(dx: Float, dy: Float, alpha: Float, block: () -> Unit) {
        if (dx == 0f && dy == 0f && alpha >= 1f) {
            block()
            return
        }
        ctx.pushTranslate(dx, dy, 0f)
        ctx.pushOpacity(alpha)
        mt.shiftX += dx
        mt.shiftY += dy
        try {
            block()
        } finally {
            mt.shiftX -= dx
            mt.shiftY -= dy
            ctx.popOpacity()
            ctx.popTransform()
        }
    }

    /** 页面标题区：从右边滑入 */
    inline fun headerAnim(block: () -> Unit) {
        val p = pageProgress(0f, 320f)
        shifted((1f - p) * 12f, 0f, p, block)
    }

    /** 第 i 个条目：从下面升起，逐个错开 */
    inline fun itemAnim(i: Int, block: () -> Unit) {
        val p = pageProgress(min(i, 14) * 35f, 380f)
        shifted(0f, (1f - p) * 18f, p, block)
    }
}

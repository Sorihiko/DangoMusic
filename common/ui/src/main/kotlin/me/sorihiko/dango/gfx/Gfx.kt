package me.sorihiko.dango.gfx

import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.RenderContext

import java.awt.image.BufferedImage

/**
 * 共用界面代码画东西时用的接口。每个 Minecraft 版本的平台层实现一份，启动时交给 [me.sorihiko.dango.DangoClient]。
 *
 * 约定：
 *   - "窗口像素"：游戏窗口的实际像素；"逻辑坐标"：界面按 1280×800 排版后的坐标（乘上缩放才是窗口像素）。
 *   - 中日韩文字和图标符号用 [cjkWidth] / [drawCjkRuns]（平滑字体，平台层自己光栅化）；
 *     拉丁文字用菜单字体（Inter），由 [RenderContext.drawText] 画。
 */
interface Gfx {

    val textures: Textures

    /** 新建一块液态玻璃画布（播放器窗口、Song card、灵动岛各用一块） */
    fun newCanvas(): GuiCanvas

    /** 菜单字体（Inter 等，按文件名）在 [size] 字号下的宽度 */
    fun uiTextWidth(text: String, size: Float, font: String): Float

    /** 平滑字体（中日韩 / 符号）的宽度；[style] 见 [CjkStyle] */
    fun cjkWidth(text: String, size: Float, style: Int): Float

    /** 预先生成字形（图标第一次出现时就准备好，避免闪一下） */
    fun cjkPreload(c: Char, style: Int)

    /**
     * 画一批平滑字体文字段（在玻璃层画完之后统一画）。
     * [scale]：逻辑坐标 → 窗口像素；裁剪框也是逻辑坐标。
     */
    fun drawCjkRuns(runs: List<CjkRun>, scale: Float)

    /** 保存当前渲染状态，执行 [block]，然后原样恢复（HUD / 世界里画东西前后用） */
    fun guarded(block: () -> Unit)

    // ---- 歌词用 ----

    val text: TextCanvas
    val world: WorldView
    val glow: GlowPass
    val hud: HudCanvas
}

/** 平滑字体的样式 */
object CjkStyle {
    const val PLAIN = 0
    const val BOLD = 1
    const val DISPLAY = 2
    /** 歌词（玩家选的字体，没有的字退回普通字体） */
    const val LYRIC = 3
}

/** 一段要画的平滑字体文字。centered = (x, y) 是可见部分的中心；否则 y 是基线 */
class CjkRun(
    val s: String, val x: Float, val y: Float, val size: Float,
    val r: Float, val g: Float, val b: Float, val a: Float,
    val style: Int, val clip: LayoutBounds?, val centered: Boolean,
)

// ---------------------------------------------------------------- 贴图

/** 平台层的贴图句柄（不透明） */
interface Texture

interface Textures {
    /** 把图片传成贴图（必须在渲染线程调用） */
    fun upload(name: String, image: BufferedImage): Texture
    fun delete(texture: Texture)
}

// ---------------------------------------------------------------- 液态玻璃画布

interface GuiCanvas : RenderContext {
    /**
     * 开始画一帧：保存渲染状态、设好混合。
     * [captureBackdrop] = 先截一张当前画面给液态玻璃做背景（不截时玻璃退化成半透明面板）。
     * 鼠标位置是逻辑坐标（悬停效果用）。
     */
    fun begin(captureBackdrop: Boolean, mouseX: Int, mouseY: Int)

    /** 结束一帧：关掉裁剪，恢复渲染状态 */
    fun end()

    /** 立刻生效的矩形裁剪，参数和 glScissor 一样（窗口像素，原点在左下角） */
    fun scissor(x: Int, y: Int, width: Int, height: Int)

    fun noScissor()
}

// ---------------------------------------------------------------- 歌词文字

/**
 * 画歌词文字用的"笔"：当前变换（平移 / 旋转 / 缩放）、颜色，以及两种字体：
 *   smooth = 平滑字体（[CjkStyle]），pixel = 游戏自带的像素字体。
 */
interface TextCanvas {
    fun push()
    fun pop()
    fun translate(x: Float, y: Float)
    fun rotate(degrees: Float)
    fun scale(x: Float, y: Float)

    /** 平滑字体的颜色 */
    fun color(r: Float, g: Float, b: Float, a: Float)

    /** 歌词用的字体文件（null = 默认）；只在渲染线程调用 */
    fun lyricFont(file: java.io.File?) {}

    /** 平滑字体：y 是基线 */
    fun smoothText(text: String, x: Float, baseline: Float, size: Float, style: Int)
    fun smoothWidth(text: String, size: Float, style: Int): Float

    /** 开始 / 结束一批平滑字体（设置和还原平滑字体需要的渲染状态） */
    fun beginSmooth()
    fun endSmooth()

    /** 像素字体：y 是字的顶部，argb 颜色 */
    fun pixelText(text: String, x: Float, y: Float, argb: Int, shadow: Boolean)
    fun pixelWidth(text: String): Float
    fun pixelCharWidth(c: Char): Float

    /** 屏幕模式：窗口像素坐标（原点左上角）的正交投影，画文闪用 */
    fun beginScreen()
    fun endScreen()
}

/** 在世界里画歌词 */
interface WorldView {
    /** 镜头位置（世界坐标） */
    val cameraX: Double
    val cameraY: Double
    val cameraZ: Double

    /** 开始画世界里的文字：关深度测试、设好混合和贴图状态 */
    fun begin()

    /**
     * 移到镜头相对坐标 (rx, ry, rz)，先绕 Y 轴转 -yaw、再绕 X 轴转 pitch（角度），
     * 然后缩放成文字坐标（x 向右、y 向下）。之后用 [TextCanvas] 画，最后 [pop]。
     */
    fun push(rx: Double, ry: Double, rz: Double, yaw: Float, pitch: Float, scale: Float)
    fun pop()

    /** 发光层画完之后，把文字需要的渲染状态重新设好 */
    fun resync()
}

/** Deep Glow：把文字先画到离屏画布上，再模糊、合成出光晕 */
interface GlowPass {
    val available: Boolean
    /** 开始往发光画布上画；返回 false 表示这一帧做不了 */
    fun begin(): Boolean
    fun end(r: Float, g: Float, b: Float, exposure: Float, spread: Float)
    /** 出错了：之后不再用发光 */
    fun disable(reason: Throwable)
}

/** 左上角"正在播放"这种原版风格的 HUD（原版 GUI 缩放的坐标） */
interface HudCanvas {
    fun begin()
    fun end()
    fun rect(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int)
    fun texture(texture: Texture, x: Int, y: Int, width: Int, height: Int, alpha: Float)
    fun text(text: String, x: Float, y: Float, argb: Int, shadow: Boolean)
}

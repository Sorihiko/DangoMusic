package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.font.MixedText
import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.update.UpdateChecker
import me.sorihiko.dango.utils.Color

import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 灵动岛（iOS 风格的胶囊）：
 *  - 平时：用户名 · FPS · 延迟 · 时间；内置播放器在放歌时显示 封面 + 歌名 + 跳动的音浪
 *  - 切歌 / 暂停 / 继续：展开成大卡片（封面、歌名、歌手、进度条）
 *  - 开关模块、攻击玩家：短暂显示对应信息
 *  - 有新版本时在游戏里提醒一次（每个版本只提醒一次）
 * 尺寸用弹簧动画过渡，内容淡入淡出；背景可选液态玻璃或纯黑。
 */
@ModuleMeta(Category.MUSIC, "DynamicIsland")
class DynamicIslandModule : Module() {

    @ModuleValue("Liquid glass", "Liquid glass background; off is a solid black pill")
    private val glass = BooleanValue(true)

    @ModuleValue("Show now playing", "Show what the built-in player is playing")
    private val showMusic = BooleanValue(true)

    @ModuleValue("Song notify", "Expand to show the song on track change or pause")
    private val songNotify = BooleanValue(true)

    @ModuleValue("Scale", "Island size")
    private val scale = FloatValue(0.6f, 1.6f, 1f, 2)

    @ModuleValue("Notify time", "How long song notifications stay (seconds)")
    private val notifyTime = FloatValue(1f, 8f, 3.5f, 1)

    private enum class Kind { IDLE, SONG, UPDATE }

    private var context: GuiCanvas? = null

    // 当前显示的内容 / 临时内容
    private var shown = Kind.IDLE
    private var temp: Kind? = null
    private var tempUntil = 0L
    private var contentAlpha = 0f

    // 弹簧动画（单位：逻辑像素）
    private var w = 0f
    private var h = 0f
    private var vw = 0f
    private var vh = 0f
    private var lastNanos = 0L
    private var enabledAt = 0L

    private var lastTrackId = ""
    private var lastState: MusicPlayer.State? = null
    /** 这次启动是否已经处理过新版本提醒 */
    private var updateHandled = false

    private val clock = SimpleDateFormat("HH:mm")

    private val mt = MixedText()

    override fun onEnable() {
        enabledAt = System.currentTimeMillis()
        w = 0f; h = 0f; vw = 0f; vh = 0f
        shown = Kind.IDLE
        temp = null
        contentAlpha = 0f
        lastNanos = 0L
        lastTrackId = MusicPlayer.shownTrack?.key ?: ""
        lastState = MusicPlayer.shownState
    }

    private fun show(kind: Kind, ms: Long) {
        temp = kind
        tempUntil = System.currentTimeMillis() + ms
    }

    private fun watchMusic() {
        val cur = MusicPlayer.shownTrack
        val st = MusicPlayer.shownState
        if (cur == null) {
            lastTrackId = ""
        } else if (cur.key != lastTrackId) {
            lastTrackId = cur.key
            if (songNotify.get()) show(Kind.SONG, (notifyTime.value * 1000).toLong())
        } else if (lastState != null && st != lastState && songNotify.get() &&
            (st == MusicPlayer.State.PAUSED || (st == MusicPlayer.State.PLAYING && lastState == MusicPlayer.State.PAUSED))
        ) {
            if (temp != Kind.SONG || tempUntil - System.currentTimeMillis() < 1500L) show(Kind.SONG, 1500L)
        }
        lastState = st
    }

    /** 有新版本、设置里开着检查更新、这个版本还没提醒过：等没有别的通知时展开提醒一次 */
    private fun watchUpdate() {
        if (updateHandled || temp != null || UpdateChecker.available == null || !DangoClient.platform.inGame) return
        updateHandled = true
        val on = try {
            DangoMusic.instance.moduleManager.get(NeteaseMusicModule::class.java).checkUpdates.get()
        } catch (_: Exception) {
            false
        }
        if (!on || !UpdateChecker.shouldNotify()) return
        show(Kind.UPDATE, 7000L)
        UpdateChecker.markNotified()
    }

    private fun desired(): Kind {
        val t = temp
        if (t != null && System.currentTimeMillis() < tempUntil) {
            val ok = when (t) {
                Kind.SONG -> MusicPlayer.shownTrack != null
                Kind.UPDATE, Kind.IDLE -> true
            }
            if (ok) return t
        }
        temp = null
        return Kind.IDLE
    }

    private fun musicIdle() = showMusic.get() && MusicPlayer.shownTrack != null && MusicPlayer.shownActive

    // ---------------------------------------------------------------- 尺寸（逻辑像素）

    private fun sizeOf(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.SONG -> {
            val t = MusicPlayer.shownTrack
            val tw = if (t == null) 0f else max(
                width(t.name, 14f, Theme.FONT_SEMIBOLD).coerceAtMost(260f),
                width(t.artists.joinToString(" / "), 11f).coerceAtMost(260f),
            )
            (76f + max(tw, 150f) + 44f) to 76f
        }
        Kind.UPDATE -> {
            val tw = max(width(updateTitle(), 13f, Theme.FONT_SEMIBOLD), width(I18n.text("Copy the download link in the player's settings"), 11f))
            (16f + 8f + 10f + tw.coerceAtMost(320f) + 18f) to 52f
        }
        Kind.IDLE -> if (musicIdle()) {
            val t = MusicPlayer.shownTrack!!
            (9f + 22f + 9f + width(t.name, 12f, Theme.FONT_SEMIBOLD).coerceAtMost(170f) + 12f + 14f + 12f) to 32f
        } else {
            (14f + 6f + 8f + idleWidth() + 16f) to 32f
        }
    }

    private fun idleParts(): List<Pair<String, Color>> {
        val parts = mutableListOf<Pair<String, Color>>()
        val platform = DangoClient.platform
        parts += platform.playerName to Theme.TEXT_PRIMARY
        parts += "${platform.fps} fps" to Theme.TEXT_SECONDARY
        val ping = platform.pingMs
        if (ping > 0) parts += "$ping ms" to Theme.TEXT_SECONDARY
        parts += clock.format(Date()) to Theme.TEXT_SECONDARY
        return parts
    }

    private fun idleWidth(): Float {
        val parts = idleParts()
        var x = 0f
        parts.forEachIndexed { i, (s, _) ->
            if (i > 0) x += width("  ·  ", 12f)
            x += width(s, 12f, if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR)
        }
        return x
    }

    // ---------------------------------------------------------------- 渲染

    @EventHandler
    fun render(e: HudRenderEvent) {
        if (!enabled) return
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now

        watchMusic()
        watchUpdate()
        val want = desired()

        // 内容切换：先淡出旧内容，再换成新内容淡入
        if (shown != want) {
            contentAlpha -= dt / 0.09f
            if (contentAlpha <= 0f) {
                contentAlpha = 0f
                shown = want
            }
        } else {
            contentAlpha = min(1f, contentAlpha + dt / 0.16f)
        }

        // 弹簧：略带回弹，接近 iOS 灵动岛的手感
        val (tw, th) = sizeOf(want)
        val stiff = 230f
        val damp = 24f
        vw += ((tw - w) * stiff - vw * damp) * dt
        vh += ((th - h) * stiff - vh * damp) * dt
        w += vw * dt
        h += vh * dt
        if (w < 1f || h < 1f) return

        val platform = DangoClient.platform
        val ctx = context ?: DangoClient.gfx.newCanvas().also { context = it }
        ctx.begin(glass.get(), 0, 0)
        try {
            val uiScale = min(platform.windowWidth / 1280f, platform.windowHeight / 800f).coerceAtLeast(0.5f)
            val k = uiScale * scale.value
            val lw = platform.windowWidth / k
            val sf = platform.guiScale
            val top = 18f * sf / k                       // 和原来一样，留出 Boss 血条的位置
            val pill = LayoutBounds((lw - w) / 2f, top, w, h)
            val r = min(h / 2f, 22f)

            ctx.pushScale(k, k, 1f)
            if (glass.get()) {
                ctx.drawGlass(pill, Theme.GLASS_WINDOW.copy(radius = r, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.14f), 8f, Vec2(0f, -2f))))
            } else {
                ctx.drawShape(
                    pill,
                    ShapeStyle(
                        Color(0.02f, 0.02f, 0.03f, 0.9f), r, 1f, Color(1f, 1f, 1f, 0.07f),
                        ShadowStyle(Color(0f, 0f, 0f, 0.18f), 8f, Vec2(0f, -2f)),
                    )
                )
            }

            // 内容按目标尺寸排版，在当前胶囊里居中，超出部分裁掉
            val (cw, ch) = sizeOf(shown)
            val cb = LayoutBounds(pill.x + (w - cw) / 2f, pill.y + (h - ch) / 2f, cw, ch)
            val scX = (pill.x * k).toInt()
            val scY = (platform.windowHeight - (pill.y + h) * k).toInt()
            ctx.scissor(scX, scY, (w * k).toInt() + 1, (h * k).toInt() + 1)

            mt.clear()
            mt.clip = pill
            ctx.pushOpacity(contentAlpha)
            when (shown) {
                Kind.IDLE -> if (musicIdle()) drawMusicIdle(ctx, cb) else drawIdle(ctx, cb)
                Kind.SONG -> drawSong(ctx, cb)
                Kind.UPDATE -> drawUpdate(ctx, cb)
            }
            ctx.popOpacity()
            ctx.popTransform()
            mt.flush(k)
            ctx.noScissor()
        } finally {
            ctx.end()
        }
    }

    private fun drawIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val cy = b.y + b.height / 2f
        ctx.drawShape(LayoutBounds(b.x + 14f, cy - 3f, 6f, 6f), ShapeStyle(Theme.GREEN, 3f))
        var x = b.x + 28f
        val ty = cy - 12f * 0.39f
        idleParts().forEachIndexed { i, (s, c) ->
            if (i > 0) {
                text(ctx, "  ·  ", x, ty, 12f, Theme.TEXT_TERTIARY)
                x += width("  ·  ", 12f)
            }
            val font = if (i == 0) Theme.FONT_SEMIBOLD else Theme.FONT_REGULAR
            text(ctx, s, x, ty, 12f, c, font)
            x += width(s, 12f, font)
        }
    }

    private fun drawMusicIdle(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.shownTrack ?: return
        // 胶囊两头是半圆：封面往里挪一点、圆角跟着胶囊的弧度走，看起来才居中
        val cover = LayoutBounds(b.x + 9f, b.y + 5f, 22f, 22f)
        drawCover(ctx, cover, t.let { CoverCache.trackRounded(it, 0.36f) ?: CoverCache.track(it) })
        val playing = MusicPlayer.shownState == MusicPlayer.State.PLAYING
        val tx = cover.right + 9f
        val title = trim(t.name, 170f, 12f, Theme.FONT_SEMIBOLD)
        text(ctx, title, tx, b.y + 16f - 12f * 0.39f, 12f, if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        bars(ctx, b.right - 12f - 14f, b.y + 22f, playing, 11f)
    }

    private fun updateTitle() = I18n.text("Dango Music update: ") + (UpdateChecker.available?.version ?: "")

    private fun drawUpdate(ctx: GuiCanvas, b: LayoutBounds) {
        ctx.drawShape(LayoutBounds(b.x + 16f, b.y + 22f, 8f, 8f), ShapeStyle(Theme.ACCENT, 4f))
        val tx = b.x + 34f
        val tw = b.right - 18f - tx
        text(ctx, trim(updateTitle(), tw, 13f, Theme.FONT_SEMIBOLD), tx, b.y + 12f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(I18n.text("Copy the download link in the player's settings"), tw, 11f), tx, b.y + 30f, 11f, Theme.TEXT_SECONDARY)
    }

    private fun drawSong(ctx: GuiCanvas, b: LayoutBounds) {
        val t = MusicPlayer.shownTrack ?: return
        val cover = LayoutBounds(b.x + 10f, b.y + 10f, 56f, 56f)
        drawCover(ctx, cover, CoverCache.trackRounded(t) ?: CoverCache.track(t))
        val playing = MusicPlayer.shownState == MusicPlayer.State.PLAYING
        val tx = cover.right + 10f
        val tw = b.right - 14f - tx

        val label = when (MusicPlayer.shownState) {
            MusicPlayer.State.PAUSED -> I18n.text("PAUSED")
            MusicPlayer.State.LOADING -> I18n.text("LOADING")
            else -> I18n.text("NOW PLAYING")
        }
        text(ctx, label, tx, b.y + 11f, 9f, if (playing) Theme.ACCENT else Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        bars(ctx, b.right - 14f - 14f, b.y + 21f, playing, 10f)
        text(ctx, trim(t.name, tw - 4f, 14f, Theme.FONT_SEMIBOLD), tx, b.y + 24f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        text(ctx, trim(t.artists.joinToString(" / "), tw, 11f), tx, b.y + 42f, 11f, Theme.TEXT_SECONDARY)

        val bar = LayoutBounds(tx, b.y + 60f, tw, 3f)
        ctx.drawShape(bar, ShapeStyle(Theme.TRACK, 1.5f))
        val dur = MusicPlayer.shownDurationMs
        if (dur > 0) {
            val f = (MusicPlayer.shownPositionMs.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ctx.drawShape(LayoutBounds(bar.x, bar.y, max(3f, bar.width * f), bar.height), ShapeStyle(Theme.TEXT_PRIMARY, 1.5f))
        }
    }

    private fun drawCover(ctx: GuiCanvas, b: LayoutBounds, tex: Texture?) {
        CoverCache.upload()
        if (tex != null) ctx.drawImage(b, tex)
        else ctx.drawShape(b, ShapeStyle(Theme.TRACK, b.width * 0.2f))
    }

    /** 跳动的音浪：3 根小柱子，底部对齐 yBottom */
    private fun bars(ctx: GuiCanvas, x: Float, yBottom: Float, playing: Boolean, maxH: Float) {
        val time = System.nanoTime() / 1e9
        for (i in 0 until 4) {
            val hh = if (playing)
                (3f + (maxH - 3f) * (0.5f + 0.5f * sin(time * (5.5 + i * 1.9) + i * 1.3).toFloat()))
            else 3f
            ctx.drawShape(
                LayoutBounds(x + i * 3.6f, yBottom - hh, 2.2f, hh),
                ShapeStyle(if (playing) Theme.TEXT_PRIMARY else Theme.TEXT_TERTIARY, 1.1f),
            )
        }
    }

    // ---------------------------------------------------------------- 文字（拉丁用菜单字体，中文用 MC 字体），y 是文字顶部

    private fun text(ctx: GuiCanvas, s: String, x: Float, y: Float, size: Float, color: Color, font: String = Theme.FONT_REGULAR) =
        mt.text(ctx, s, x, y, size, color, font)

    private fun width(s: String, size: Float, font: String = Theme.FONT_REGULAR): Float = mt.width(s, size, font)

    private fun trim(s: String, maxW: Float, size: Float, font: String = Theme.FONT_REGULAR): String = mt.trim(s, maxW, size, font)
}

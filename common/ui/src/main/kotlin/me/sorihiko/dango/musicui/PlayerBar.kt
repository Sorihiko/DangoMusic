package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.playback.PlaybackController
import me.sorihiko.dango.playback.PlaybackMode
import me.sorihiko.dango.playback.PlaybackSessions
import me.sorihiko.dango.playback.PlaybackSnapshot
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.min

/**
 * 底部播放栏 = 控制面板：左边当前歌曲，中间控制按钮 + 进度条，右边模式相关操作。
 * 内置模式：单曲循环 / 随机 + 音量（本地引擎参数）；
 * Salt 模式：歌和引擎在 Salt Player，这里只显示连接状态并转发控制。
 */
internal class PlayerBar(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private var progRect = LayoutBounds()
    private var volRect = LayoutBounds()

    /** 一个按钮的动画：悬停底圈渐显、点击先缩再弹回、开关类按钮的选中颜色渐变 */
    private class Anim {
        val hover = Spring(300f, 26f)
        val press = Spring(420f, 13f)
        val on = Spring(240f, 24f)
    }
    private val prevA = Anim()
    private val playA = Anim()
    private val nextA = Anim()
    private val queueA = Anim()
    private val shuffleA = Anim()
    private val repeatA = Anim()
    /** 播放 ↔ 暂停图标的过渡 */
    private val playMorph = Spring(320f, 26f)
    /** 进度条上的圆钮：鼠标移上去时渐显 */
    private val progKnob = Spring(300f, 26f)

    fun draw(bar: LayoutBounds) {
        val ctx = ui.ctx
        ui.card(bar, Theme.CARD, 16f)
        val salt = PlaybackSessions.mode == PlaybackMode.SALT
        val snap: PlaybackSnapshot = PlaybackSessions.active().snapshot()
        val t = snap.track

        // 左：封面 + 歌名 / 歌手（统一读当前播放会话）
        val cs = 48f
        val coverBox = LayoutBounds(bar.x + 13f, bar.y + (bar.height - cs) / 2f, cs, cs)
        val coverImg = if (salt) t?.let { CoverCache.get(it.key, "", it.coverBytes) }
            else MusicPlayer.current?.let { CoverCache.track(it) }
        ui.drawCover(coverBox, coverImg, 8f)
        val infoX = coverBox.right + 12f
        val infoW = 220f
        val title = t?.title ?: I18n.text("Not playing")
        ui.text(ui.trim(title, infoW, 14f, Theme.FONT_SEMIBOLD), infoX, coverBox.y + 9f, 14f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = when {
            salt && PlaybackSessions.salt.connectionNote.isNotEmpty() ->
                I18n.text(module.saltStatusText())
            t == null -> "-"
            !salt && MusicPlayer.message.isNotEmpty() -> I18n.message(MusicPlayer.message)
            else -> t.artistText
        }
        ui.text(ui.trim(sub, infoW, 11f), infoX, coverBox.y + 28f, 11f, Theme.TEXT_SECONDARY)

        // 中：上一首 / 播放 / 下一首（+ 内置的队列）。控制经统一路由
        val cx = bar.x + bar.width / 2f
        val cy = bar.y + 25f
        val playB = LayoutBounds(cx - 17f, cy - 17f, 34f, 34f)
        val prevB = LayoutBounds(cx - 58f - 14f, cy - 14f, 28f, 28f)
        val nextB = LayoutBounds(cx + 58f - 14f, cy - 14f, 28f, 28f)
        val queueB = LayoutBounds(cx + 112f - 14f, cy - 14f, 28f, 28f)
        val kPrev = button(prevB, prevA)
        val kPlay = button(playB, playA)
        val kNext = button(nextB, nextA)
        // 播放 ↔ 暂停：两个图标交叉淡入淡出，暂停的两道竖条从中间长出来，三角形收小淡出
        val p = playMorph.tick(if (snap.playing) 1f else 0f).coerceIn(0f, 1f)
        if (p > 0.01f) {
            ctx.pushOpacity(p)
            val bh = 16f * kPlay * (0.55f + 0.45f * p)
            ui.card(LayoutBounds(cx - 6f * kPlay, cy - bh / 2f, 4f * kPlay, bh), Theme.TEXT_PRIMARY, 1.5f)
            ui.card(LayoutBounds(cx + 2f * kPlay, cy - bh / 2f, 4f * kPlay, bh), Theme.TEXT_PRIMARY, 1.5f)
            ctx.popOpacity()
        }
        if (p < 0.99f) {
            ctx.pushOpacity(1f - p)
            ui.icon("▶", cx + 1.5f * kPlay, cy, 18f * kPlay * (1f - 0.35f * p), Theme.TEXT_PRIMARY)
            ctx.popOpacity()
        }
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条（围绕按钮中心缩放）
        val pc = prevB.x + 14f
        ui.card(LayoutBounds(pc - 6f * kPrev, cy - 5.5f * kPrev, 2.2f * kPrev, 11f * kPrev), Theme.TEXT_PRIMARY, 1f)
        ui.icon("◀", pc + 2.5f * kPrev, cy, 12f * kPrev, Theme.TEXT_PRIMARY)
        val nc = nextB.x + 14f
        ui.icon("▶", nc - 2.5f * kNext, cy, 12f * kNext, Theme.TEXT_PRIMARY)
        ui.card(LayoutBounds(nc + 3.8f * kNext, cy - 5.5f * kNext, 2.2f * kNext, 11f * kNext), Theme.TEXT_PRIMARY, 1f)
        ui.hits += prevB to { prevA.press.v -= 7f; PlaybackController.previous() }
        ui.hits += playB to { playA.press.v -= 7f; PlaybackController.togglePause() }
        ui.hits += nextB to { nextA.press.v -= 7f; PlaybackController.next() }
        if (!salt) {
            val kQueue = button(queueB, queueA)
            val q = queueA.on.tick(if (ScreenState.page == Page.QUEUE) 1f else 0f)
            ui.icon("≡", queueB.x + 14f, cy, 14f * kQueue, mixColor(Theme.TEXT_PRIMARY, RED, q))
            ui.hits += queueB to {
                queueA.press.v -= 7f
                if (ScreenState.page == Page.QUEUE) screen.goBackOrHome() else screen.go(Page.QUEUE)
            }
        }

        // 进度条：时间 | 条 | 时间
        val dur = t?.durationMs ?: 0L
        val pos = snap.positionMs
        val pw = min(440f, bar.width * 0.4f)
        progRect = LayoutBounds(cx - pw / 2f, bar.y + 54f, pw, 4f)
        val l = fmtTime(pos)
        val rgt = fmtTime(dur)
        ui.text(l, progRect.x - 10f - ui.width(l, 10f), progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        ui.text(rgt, progRect.right + 10f, progRect.y - 3f, 10f, Theme.TEXT_SECONDARY)
        ui.card(progRect, Theme.TRACK, 2f)
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(progRect.x, progRect.y, max(4f, progRect.width * f), progRect.height), RED, 2f)
            val kn = progKnob.tick(if (progRect.expand(0f, 6f).contains(ui.mx, ui.my)) 1f else 0f).coerceIn(0f, 1.2f)
            if (kn > 0.01f) {
                val kx = progRect.x + progRect.width * f
                val ks = 10f * kn
                ctx.drawShape(LayoutBounds(kx - ks / 2f, progRect.y + 2f - ks / 2f, ks, ks), ShapeStyle(Theme.TEXT_PRIMARY, ks / 2f))
            }
        }

        // 右：内置 = 单曲循环 / 随机 + 音量；Salt = 连接状态（曲库和引擎在 Salt Player）
        val vy = bar.y + bar.height / 2f
        if (salt) {
            val st = I18n.text(module.saltStatusText())
            ui.text(ui.trim(st, 220f, 11f, Theme.FONT_SEMIBOLD), bar.right - 24f - 220f, vy - 5f, 11f,
                Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            val hint = I18n.text("Songs & playback live in Salt Player")
            ui.text(ui.trim(hint, 220f, 9.5f), bar.right - 24f - 220f, vy + 10f, 9.5f, Theme.TEXT_TERTIARY)
            volRect = LayoutBounds()
            return
        }
        volRect = LayoutBounds(bar.right - 30f - 110f, vy - 2f, 110f, 4f)
        speaker(volRect.x - 22f, vy, false)
        speaker(volRect.right + 9f, vy, true)
        ui.card(volRect, Theme.TRACK, 2f)
        ui.card(LayoutBounds(volRect.x, volRect.y, max(4f, volRect.width * MusicPlayer.volume), volRect.height), RED, 2f)
        val knobX = volRect.x + volRect.width * MusicPlayer.volume
        ctx.drawShape(LayoutBounds(knobX - 5f, volRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))

        val shB = LayoutBounds(volRect.x - 72f, vy - 14f, 32f, 28f)
        toggleButton(shB, vy, MusicPlayer.shuffle, shuffleA, "⇄") { module.toggleShuffle() }
        val repeatB = LayoutBounds(shB.x - 40f, vy - 14f, 32f, 28f)
        toggleButton(repeatB, vy, MusicPlayer.repeatOne, repeatA, "↻") { module.toggleRepeatOne() }
    }

    /** 圆形按钮的底：悬停时渐显、跟着点击回弹；返回当前的缩放，图标按它缩放 */
    private fun button(b: LayoutBounds, a: Anim): Float {
        val h = a.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + a.press.tick(0f) * 0.14f + h * 0.04f
        if (h > 0.01f) {
            val d = b.scaled(k)
            ui.ctx.drawShape(d, ShapeStyle(Theme.CARD_HOVER.copy(a = Theme.CARD_HOVER.a * h), d.height / 2f))
        }
        return k
    }

    /** 随机 / 单曲循环：选中的红底渐入渐出，点击先缩再弹回 */
    private fun toggleButton(b: LayoutBounds, vy: Float, on: Boolean, a: Anim, glyph: String, action: () -> Unit) {
        val o = a.on.tick(if (on) 1f else 0f).coerceIn(0f, 1f)
        val h = a.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + a.press.tick(0f) * 0.14f
        val base = mixColor(Color(1f, 1f, 1f, 0.07f), Theme.CARD_HOVER, h)
        ui.card(b.scaled(k), mixColor(base, RED.copy(a = 0.85f), o), 9f * k)
        ui.icon(glyph, b.x + 16f, vy, 14f * k, Theme.TEXT_PRIMARY)
        ui.hits += b to {
            a.press.v -= 7f
            action()
        }
    }

    /** 小喇叭图标：方块 + 喇叭口，loud = 右边再加两道声波 */
    private fun speaker(x: Float, cy: Float, loud: Boolean) {
        ui.card(LayoutBounds(x, cy - 2.5f, 3.5f, 5f), Theme.TEXT_SECONDARY, 1f)
        ui.card(LayoutBounds(x + 3f, cy - 5f, 3.5f, 10f), Theme.TEXT_SECONDARY, 1.2f)
        if (loud) {
            ui.card(LayoutBounds(x + 8.5f, cy - 2.5f, 1.4f, 5f), Theme.TEXT_SECONDARY, 0.7f)
            ui.card(LayoutBounds(x + 11.5f, cy - 4.5f, 1.4f, 9f), Theme.TEXT_SECONDARY, 0.7f)
        }
    }

    /** 点在进度条 / 音量条上就处理掉，返回 true */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (progRect.expand(0f, 6f).contains(mx, my)) {
            val snap = PlaybackSessions.active().snapshot()
            val dur = snap.track?.durationMs ?: 0L
            if (dur > 0) PlaybackController.seekTo(((mx - progRect.x) / progRect.width * dur).toLong())
            return true
        }
        if (volRect.width > 0f && volRect.expand(6f, 8f).contains(mx, my)) {
            setVolumeAt(mx)
            return true
        }
        return false
    }

    /** 按住拖动音量 */
    fun mouseDrag(mx: Int, my: Int) {
        if (volRect.width > 0f && volRect.expand(12f, 12f).contains(mx, my)) setVolumeAt(mx)
    }

    private fun setVolumeAt(x: Int) {
        val v = ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
        module.setVolume(v)
        MusicPlayer.volume = v
    }
}

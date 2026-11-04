package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import kotlin.math.max

/** 底部播放栏：左边当前歌曲，中间控制按钮 + 进度条，右边单曲循环 / 随机 + 音量 */
internal class PlayerBar(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private var lastSubtitle = ""
    private var subtitleAt = 0L
    private var lastSkipNotice = 0L
    private var lastBlockedNotice = 0L
    private var failedMembers = emptySet<String>()
    private var progRect = LayoutBounds()
    private var volRect = LayoutBounds()
    /** 正在拖进度条：拖的时候只移动显示的位置，松开才真正跳过去（拖动过程中不反复重新解码） */
    private var seeking = false
    private var seekFraction = 0f

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
    private val likeA = Anim()
    /** 播放 ↔ 暂停图标的过渡 */
    private val playMorph = Spring(320f, 26f)
    /** 进度条上的圆钮：鼠标移上去时渐显 */
    private val progKnob = Spring(300f, 26f)

    fun draw(bar: LayoutBounds) {
        val ctx = ui.ctx
        drawPanel(bar)
        val t = MusicPlayer.current
        // 上面一排放封面、按钮和音量（中线在 [ry]），下面是横贯整条栏的进度条
        val ry = bar.y + 34f

        // 左：封面 + 歌名 / 歌手
        val cs = 48f
        // 左边封面和右边音量都往里收，和下面进度条的两头对齐
        val coverBox = LayoutBounds(bar.x + PROGRESS_INSET, ry - cs / 2f, cs, cs)
        ui.drawCover(coverBox, t?.let { CoverCache.trackRounded(it, 0.27f) }, 13f)
        ctx.drawShape(coverBox, ShapeStyle(Color.TRANSPARENT, 13f, 1f, Color(1f, 1f, 1f, 0.10f)))
        val infoX = coverBox.right + 12f
        // 窗口窄时歌名区跟着缩短，别压到中间的按钮上（红心也要留位置）
        val likeShown = t != null && t.source == MusicSource.NETEASE && NeteaseApi.loggedIn
        val leftLimit = bar.x + bar.width / 2f - 96f
        val infoW = (leftLimit - infoX - (if (likeShown) 44f else 8f)).coerceIn(40f, 220f)
        val title = t?.name ?: I18n.text("Not playing")
        ui.text(ui.trim(title, infoW, 13f, Theme.FONT_SEMIBOLD), infoX, coverBox.y + 9f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val sub = when {
            t == null -> "-"
            MusicPlayer.message.isNotEmpty() -> I18n.message(MusicPlayer.message)
            else -> t.artists.joinToString(" / ")
        }
        // 一起听：房主没开"允许成员控制"时点了切歌 / 暂停 / 拖进度
        val blockedAt = MusicPlayer.blockedNotice
        if (lastBlockedNotice != blockedAt) {
            lastBlockedNotice = blockedAt
            if (blockedAt != 0L) Library.showTemporaryStatus(I18n.text("The host has turned off member control. You can only follow the host's music."), 3_000L)
        }
        val skipAt = MusicPlayer.skipNotice
        if (lastSkipNotice != skipAt) {
            lastSkipNotice = skipAt
            val remaining = 5_000_000_000L - (System.nanoTime() - skipAt)
            // 重开界面不重放过期事件，也不延长仍在显示的提示。
            if (skipAt != 0L && remaining > 0L) Library.showTemporaryStatus(
                I18n.text("This song could not be played. Skipped to the next song."), remaining / 1_000_000L)
        }
        val failures = me.sorihiko.dango.party.ListenParty.members.filter {
            it.failedTrack.isNotEmpty() && it.failedTrack == t?.key
        }.map { it.uid + ":" + it.failedTrack }.toSet()
        if ((failures - failedMembers).isNotEmpty()) {
            Library.showTemporaryStatus(I18n.text("Someone in the room cannot play this song. Check the room member list; account access, region or network may be the cause."), 8_000L)
        }
        failedMembers = failures
        if (sub != lastSubtitle) { lastSubtitle = sub; subtitleAt = System.nanoTime() }
        val overflow = ui.width(sub, 11f) - infoW
        if (MusicPlayer.message.isNotEmpty() && overflow > 0f) {
            val seconds = (System.nanoTime() - subtitleAt) / 1e9
            val travel = overflow / 28.0
            val phase = seconds % (travel * 2 + 3)
            val offset = when {
                phase < 1.5 -> 0f
                phase < 1.5 + travel -> ((phase - 1.5) * 28).toFloat()
                phase < 3 + travel -> overflow
                else -> (overflow - (phase - 3 - travel) * 28).toFloat()
            }
            ui.beginClip(LayoutBounds(infoX, coverBox.y + 23f, infoW, 20f))
            ui.text(sub, infoX - offset, coverBox.y + 27f, 11f, Theme.TEXT_SECONDARY)
            ui.endClip()
        } else if (t != null && MusicPlayer.message.isEmpty()) {
            // 歌手名可以点，打开歌手页
            drawArtistLinks(screen, t, infoX, coverBox.y + 27f, infoW, 11f, Theme.TEXT_SECONDARY)
        } else ui.text(ui.trim(sub, infoW, 11f), infoX, coverBox.y + 27f, 11f, Theme.TEXT_SECONDARY)

        // 网易云：红心（登录了才有）
        if (t != null && likeShown) {
            Library.ensureLiked()
            val likeB = LayoutBounds(infoX + infoW + 8f, coverBox.y + cs / 2f - 14f, 28f, 28f)
            val k = button(likeB, likeA)
            val on = likeA.on.tick(if (NeteaseApi.isLiked(t.id)) 1f else 0f).coerceIn(0f, 1f)
            ui.icon("♥", likeB.x + 14f, likeB.y + 14f, 15f * k, mixColor(Theme.TEXT_SECONDARY, RED, on))
            ui.hits += likeB to { likeA.press.v -= 7f; Library.toggleLike(t) }
        }

        // 中：上一首 / 播放 / 下一首 / 队列。点击区域固定，动画只改画出来的样子
        val cx = bar.x + bar.width / 2f
        val cy = ry
        val playB = LayoutBounds(cx - 24f, cy - 24f, 48f, 48f)
        val prevB = LayoutBounds(cx - 62f - 16f, cy - 16f, 32f, 32f)
        val nextB = LayoutBounds(cx + 62f - 16f, cy - 16f, 32f, 32f)
        val queueB = LayoutBounds(cx + 112f - 16f, cy - 16f, 32f, 32f)
        val kPrev = button(prevB, prevA)
        val kPlay = drawPlayDisc(playB)
        val kNext = button(nextB, nextA)
        val kQueue = button(queueB, queueA)
        // 播放 ↔ 暂停：两个图标交叉淡入淡出，暂停的两道竖条从中间长出来，三角形收小淡出
        val p = playMorph.tick(if (MusicPlayer.state == MusicPlayer.State.PLAYING) 1f else 0f).coerceIn(0f, 1f)
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
        val q = queueA.on.tick(if (ScreenState.page == Page.QUEUE) 1f else 0f)
        ui.icon("≡", queueB.x + 14f, cy, 14f * kQueue, mixColor(Theme.TEXT_PRIMARY, RED, q))
        ui.hits += prevB to { prevA.press.v -= 7f; MusicPlayer.prev() }
        ui.hits += playB to { playA.press.v -= 7f; MusicPlayer.togglePause() }
        ui.hits += nextB to { nextA.press.v -= 7f; MusicPlayer.next() }
        ui.hits += queueB to {
            queueA.press.v -= 7f
            if (ScreenState.page == Page.QUEUE) screen.goBackOrHome() else screen.go(Page.QUEUE)
        }

        // 进度条：在栏的底部，两端上方标时间（当前时间亮一点）；鼠标移上去变粗、出现圆钮
        val dur = MusicPlayer.durationMs
        val pos = MusicPlayer.positionMs
        // 两头各留一段，不贴着播放栏的圆角
        progRect = LayoutBounds(bar.x + PROGRESS_INSET, bar.bottom - 11f, bar.width - PROGRESS_INSET * 2, 3f)
        val kn = progKnob.tick(if (seeking || progRect.expand(0f, 6f).contains(ui.mx, ui.my)) 1f else 0f).coerceIn(0f, 1.2f)
        val l = fmtTime(if (seeking && dur > 0) (seekFraction * dur).toLong() else pos)
        val rgt = fmtTime(dur)
        ui.text(l, progRect.x, progRect.y - 14f, 9.5f, Theme.TEXT_PRIMARY.copy(a = 0.9f), Theme.FONT_SEMIBOLD)
        ui.text(rgt, progRect.right - ui.width(rgt, 9.5f, Theme.FONT_SEMIBOLD), progRect.y - 14f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        val thick = 3f + 2f * kn.coerceAtMost(1f)
        val lane = LayoutBounds(progRect.x, progRect.y + 1.5f - thick / 2f, progRect.width, thick)
        ui.card(lane, Color(1f, 1f, 1f, 0.08f), thick / 2f)
        if (dur > 0) {
            val f = if (seeking) seekFraction else (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(lane.x, lane.y, max(thick, lane.width * f), lane.height), RED, thick / 2f)
            if (kn > 0.01f) {
                val kx = lane.x + lane.width * f
                val ks = 12f * kn
                ctx.drawShape(LayoutBounds(kx - ks / 2f, progRect.y + 1.5f - ks / 2f, ks, ks), ShapeStyle(RED, ks / 2f))
            }
        }

        // 右：单曲循环 / 随机 + 音量
        val vy = ry
        // 窗口窄时先缩短音量条，再藏掉单曲循环 / 随机（右边从队列按钮之后到栏的右边）
        // 右边喇叭图标的右边缘对齐进度条的右端
        val rightEdge = bar.right - PROGRESS_INSET - 14f
        val rightSpace = rightEdge - (cx + 112f + 28f)
        val showToggles = rightSpace >= 230f
        val volW = (rightSpace - 46f - (if (showToggles) 84f else 0f)).coerceIn(48f, 110f)
        volRect = LayoutBounds(rightEdge - volW, vy - 2f, volW, 4f)
        speaker(volRect.x - 22f, vy, false)
        speaker(volRect.right + 9f, vy, true)
        ui.card(volRect, Theme.TRACK, 2f)
        ui.card(LayoutBounds(volRect.x, volRect.y, max(4f, volRect.width * MusicPlayer.volume), volRect.height), RED, 2f)
        val knobX = volRect.x + volRect.width * MusicPlayer.volume
        ctx.drawShape(LayoutBounds(knobX - 5f, volRect.y - 3f, 10f, 10f), ShapeStyle(Theme.TEXT_PRIMARY, 5f))

        if (showToggles) {
            val shB = LayoutBounds(volRect.x - 72f, vy - 14f, 32f, 28f)
            toggleButton(shB, vy, MusicPlayer.shuffle, shuffleA, "⇄") { module.toggleShuffle() }
            val repeatB = LayoutBounds(shB.x - 40f, vy - 14f, 32f, 28f)
            toggleButton(repeatB, vy, MusicPlayer.repeatOne, repeatA, "↻") { module.toggleRepeatOne() }
        }
    }

    /**
     * 播放栏的底：和侧栏同一种液态玻璃，圆角更大；下面压着一团强调色的光晕
     */
    private fun drawPanel(bar: LayoutBounds) {
        val glowW = bar.width * 0.6f
        ui.ctx.drawShape(LayoutBounds(bar.x + (bar.width - glowW) / 2f, bar.bottom - 40f, glowW, 36f),
            ShapeStyle(Color.TRANSPARENT, 18f, shadow = ShadowStyle(RED.copy(a = 0.12f), 30f, Vec2(0f, -6f))))
        ui.ctx.drawGlass(bar, BAR_GLASS)
    }

    /**
     * 播放键：实心的强调色圆；正在播放时外面一圈一圈地往外扩散。
     * 返回当前的缩放（悬停放大、点击回弹），里面的图标按它缩放
     */
    private fun drawPlayDisc(b: LayoutBounds): Float {
        val ctx = ui.ctx
        val hover = playA.hover.tick(if (ui.hover(b)) 1f else 0f).coerceIn(0f, 1f)
        val k = 1f + playA.press.tick(0f) * 0.14f + hover * 0.06f
        if (MusicPlayer.state == MusicPlayer.State.PLAYING) {
            val phase = (System.nanoTime() / 1e9 % 2.8 / 2.8).toFloat()
            if (phase < 0.7f) {
                val ring = b.scaled(k * (1f + 0.4f * phase / 0.7f))
                ctx.drawShape(ring, ShapeStyle(Color.TRANSPARENT, ring.height / 2f, 1.5f, RED.copy(a = 0.7f * (1f - phase / 0.7f))))
            }
        }
        val disc = b.scaled(k)
        ctx.drawShape(disc, ShapeStyle(RED, disc.height / 2f))
        return k
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

    /** 点在进度条 / 音量条上就处理掉，返回 true；按在进度条上开始拖动 */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (progRect.expand(0f, 6f).contains(mx, my)) {
            if (MusicPlayer.durationMs > 0) {
                seeking = true
                seekFraction = fractionAt(mx)
            }
            return true
        }
        if (volRect.expand(6f, 8f).contains(mx, my)) {
            setVolumeAt(mx)
            return true
        }
        return false
    }

    /** 按住拖动：拖进度条时跟着鼠标走（拖出条外也算），否则拖音量 */
    fun mouseDrag(mx: Int, my: Int) {
        if (seeking) {
            seekFraction = fractionAt(mx)
            return
        }
        if (volRect.expand(12f, 12f).contains(mx, my)) setVolumeAt(mx)
    }

    /** 松开鼠标：拖进度条的话现在才跳过去 */
    fun mouseUp() {
        if (!seeking) return
        seeking = false
        val dur = MusicPlayer.durationMs
        if (dur > 0) MusicPlayer.seek((seekFraction * dur).toLong())
    }

    private fun fractionAt(x: Int): Float =
        if (progRect.width <= 0f) 0f else ((x - progRect.x) / progRect.width).coerceIn(0f, 1f)

    private fun setVolumeAt(x: Int) {
        // 音量条还没画出来（宽度是 0）时算出来是 NaN
        if (volRect.width <= 0f) return
        val v = ((x - volRect.x) / volRect.width).coerceIn(0f, 1f)
        module.setVolume(v)
        MusicPlayer.volume = v
    }
}

/** 播放栏的玻璃：用侧栏那一套，圆角放大、阴影比侧栏淡一点 */
private val BAR_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 28f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.10f), 14f, Vec2(0f, -3f)))
/** 进度条离播放栏左右两边的距离 */
private const val PROGRESS_INSET = 72f

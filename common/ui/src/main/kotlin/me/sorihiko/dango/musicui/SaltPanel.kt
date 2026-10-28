package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.playback.PlaybackController
import me.sorihiko.dango.playback.PlaybackSessions
import me.sorihiko.dango.playback.PlaybackSnapshot
import me.sorihiko.dango.playback.salt.SaltSessionSource
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/** Salt Player 的曲目信息、播放进度与控制面板。 */
internal class SaltPanel(private val ui: Ui, private val module: NeteaseMusicModule) {

    private var progRect = LayoutBounds()
    private val btnAnim = HashMap<String, Spring>()

    fun draw(area: LayoutBounds) {
        val snap: PlaybackSnapshot = PlaybackSessions.active().snapshot()
        val t = snap.track

        // 标题行：面板标题 + 连接状态
        ui.text(I18n.text("Salt Player control panel"), area.x, area.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val status = if (module.saltSwitchNote.isNotEmpty()) I18n.text(module.saltSwitchNote) else I18n.text(module.saltStatusText())
        val statusColor = when {
            module.saltSwitchNote.isNotEmpty() -> Color(1f, 0.72f, 0.30f, 1f)
            module.saltConnection() == SaltSessionSource.Connection.READY -> Color(0.42f, 0.86f, 0.52f, 1f)
            else -> Theme.TEXT_SECONDARY
        }
        val dotX = area.x + ui.width(I18n.text("Salt Player control panel"), 22f, Theme.FONT_DISPLAY) + 12f
        ui.ctx.drawShape(LayoutBounds(dotX, area.y + 8f, 8f, 8f), ShapeStyle(statusColor, 4f))
        ui.text(ui.trim(status, 260f, 11f, Theme.FONT_SEMIBOLD), dotX + 14f, area.y + 5f, 11f, statusColor, Theme.FONT_SEMIBOLD)

        // 主卡片：曲目信息 + 进度 + 控制
        val card = LayoutBounds(area.x, area.y + 40f, area.width * 0.72f, 190f)
        ui.card(card, Theme.CARD, 16f)
        val x = card.x + 22f
        val coverBox = LayoutBounds(x, card.y + 18f, 64f, 64f)
        ui.drawCover(coverBox, t?.let { CoverCache.get(it.key, "", it.coverBytes) })
        val infoX = coverBox.right + 14f
        val infoWidth = (card.right - 22f - infoX).coerceAtLeast(0f)
        val title = t?.title ?: I18n.text("Not playing")
        ui.text(ui.trim(title, infoWidth, 24f, Theme.FONT_SEMIBOLD), infoX, card.y + 22f, 24f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val artist = t?.artistText ?: "—"
        ui.text(ui.trim(artist, infoWidth, 13f), infoX, card.y + 56f, 13f, Theme.TEXT_SECONDARY)

        // 进度条（可拖 = seek）
        val dur = t?.durationMs ?: 0L
        val pos = snap.positionMs
        progRect = LayoutBounds(x, card.y + 104f, card.width - 44f, 5f)
        ui.card(progRect, Theme.TRACK, 2.5f)
        if (dur > 0) {
            val f = (pos.toDouble() / dur).coerceIn(0.0, 1.0).toFloat()
            ui.card(LayoutBounds(progRect.x, progRect.y, progRect.width * f, progRect.height), RED, 2.5f)
            ui.ctx.drawShape(
                LayoutBounds(progRect.x + progRect.width * f - 6f, progRect.y - 3.5f, 12f, 12f),
                ShapeStyle(Theme.TEXT_PRIMARY, 6f),
            )
        }
        ui.text(fmtTime(pos), x, card.y + 118f, 10f, Theme.TEXT_TERTIARY)
        val dt = fmtTime(dur)
        ui.text(dt, card.right - 22f - ui.width(dt, 10f), card.y + 118f, 10f, Theme.TEXT_TERTIARY)

        // 大号控制按钮：⏮ ▶/⏸ ⏭
        val cy = card.y + 158f
        val cx = card.x + card.width / 2f
        bigButton("prev", cx - 84f, cy, "◀", barSide = -1) { PlaybackController.previous() }
        bigButton("next", cx + 84f, cy, "▶", barSide = 1) { PlaybackController.next() }
        playButton(cx, cy, snap.playing)

        // 右侧说明卡：歌和引擎都在 Salt Player
        val side = LayoutBounds(card.right + 16f, card.y, area.width - card.width - 16f, card.height)
        ui.card(side, Theme.CARD, 16f)
        var sy = side.y + 18f
        ui.text(I18n.text("How it works"), side.x + 18f, sy, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        sy += 26f
        for (line in listOf(
            "Songs & playback live in Salt Player",
            "This panel is just a remote control",
            "3D lyrics follow in the world automatically",
        )) {
            ui.text(ui.trim("· " + I18n.text(line), side.width - 36f, 11f), side.x + 18f, sy, 11f, Theme.TEXT_SECONDARY)
            sy += 22f
        }
    }

    /** 点在面板进度条上 → seek */
    fun mouseDown(mx: Int, my: Int): Boolean {
        if (progRect.expand(0f, 8f).contains(mx, my)) {
            val snap = PlaybackSessions.active().snapshot()
            val dur = snap.track?.durationMs ?: 0L
            if (dur > 0) PlaybackController.seekTo(((mx - progRect.x) / progRect.width * dur).toLong())
            return true
        }
        return false
    }

    private fun bigButton(id: String, cx: Float, cy: Float, glyph: String, barSide: Int = 0, action: () -> Unit) {
        val a = btnAnim.getOrPut(id) { Spring(420f, 13f) }
        val b = LayoutBounds(cx - 22f, cy - 22f, 44f, 44f)
        val h = if (ui.hover(b)) 1f else 0f
        val k = 1f + a.tick(0f) * 0.12f + h * 0.05f
        val d = b.scaled(k)
        ui.card(d, Theme.CARD_HOVER.copy(a = 0.35f + 0.45f * h), d.height / 2f)
        // ⏮ = 竖条 + ◀，⏭ = ▶ + 竖条
        if (barSide == -1) {
            ui.card(LayoutBounds(cx - 8f * k, cy - 7f * k, 2.5f * k, 14f * k), Theme.TEXT_PRIMARY, 1f)
            ui.icon(glyph, cx + 2f * k, cy, 15f * k, Theme.TEXT_PRIMARY)
        } else if (barSide == 1) {
            ui.icon(glyph, cx - 2f * k, cy, 15f * k, Theme.TEXT_PRIMARY)
            ui.card(LayoutBounds(cx + 5.5f * k, cy - 7f * k, 2.5f * k, 14f * k), Theme.TEXT_PRIMARY, 1f)
        } else {
            ui.icon(glyph, cx, cy, 16f * k, Theme.TEXT_PRIMARY)
        }
        ui.hits += b to { a.v -= 7f; action() }
    }

    /** 播放 ↔ 暂停：三角形 / 两道竖条（字体里没有现成的暂停字形，用色块画） */
    private fun playButton(cx: Float, cy: Float, playing: Boolean) {
        val a = btnAnim.getOrPut("play") { Spring(420f, 13f) }
        val b = LayoutBounds(cx - 26f, cy - 26f, 52f, 52f)
        val h = if (ui.hover(b)) 1f else 0f
        val k = 1f + a.tick(0f) * 0.12f + h * 0.05f
        val d = b.scaled(k)
        ui.card(d, RED.copy(a = 0.85f), d.height / 2f)
        if (playing) {
            ui.card(LayoutBounds(cx - 7f * k, cy - 9f * k, 4f * k, 18f * k), Theme.TEXT_PRIMARY, 1.5f)
            ui.card(LayoutBounds(cx + 3f * k, cy - 9f * k, 4f * k, 18f * k), Theme.TEXT_PRIMARY, 1.5f)
        } else {
            ui.icon("▶", cx + 1.5f * k, cy, 20f * k, Theme.TEXT_PRIMARY)
        }
        ui.hits += b to { a.v -= 7f; PlaybackController.togglePause() }
    }
}

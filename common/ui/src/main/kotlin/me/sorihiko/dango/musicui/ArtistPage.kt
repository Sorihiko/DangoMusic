package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.music.CoverCache
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.music.MusicSource
import me.sorihiko.dango.music.MusicSources
import me.sorihiko.dango.music.SourceArtist
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShadowStyle
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.ui.Vec2
import me.sorihiko.dango.utils.Color

import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 要打开的歌手：[id] 是音源自己的歌手 ID，为空时按 [name] 去这个音源找 */
internal data class ArtistRef(val source: MusicSource, val id: String, val name: String)

/**
 * 歌手页（网易云、QQ 音乐、酷狗），布局参考 SoundCloud-Desktop 的歌手页：
 *   玻璃大卡片：发光的圆形头像、标签、大号名字、两行简介、播放按钮，右边几块数据
 *   主题色从头像里取，只用在头像后面的光晕和"歌手"标签上（卡片本身是中性的玻璃）
 *   下面一条浮着的标签栏（热门歌曲 / 专辑 / 简介，带数量），再下面一块圆角面板放内容
 */
internal class ArtistPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    /** 本帧画了简介的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()

    private var tab = 0
    private var shownKey = ""

    // 标签的红色选中块：按"第几个标签"用弹簧滑过去（和设置页的标签一样）
    private val tabPill = Spring(300f, 23f)
    private var pillSnap = true

    // 主题色：从头像算出来，换歌手时慢慢变过去
    private var accent = NEUTRAL_ACCENT
    private var accentKey = ""
    private var accentTarget = NEUTRAL_ACCENT

    // 简介：平滑滚动（逻辑像素）；换行结果按文字和宽度缓存
    private var aboutTarget = 0f
    private var aboutPos = 0f
    private var aboutMax = 0f
    private var wrapKey = ""
    private var wrapped: List<String> = emptyList()

    fun tick() {
        aboutPos = ui.approach(aboutPos, aboutTarget, 70f)
        val k = (1f - kotlin.math.exp(-ui.frameDt / 160f)).coerceIn(0f, 1f)
        accent = mixColor(accent, accentTarget, k)
    }

    fun scroll(wheel: Int) {
        aboutTarget = (aboutTarget + (if (wheel > 0) -60f else 60f)).coerceIn(0f, aboutMax)
    }

    fun draw(page: LayoutBounds) {
        area = LayoutBounds()
        val ref = ScreenState.artist ?: return
        val key = "${ref.source.key}|${ref.id}|${ref.name}"
        if (key != shownKey) {
            shownKey = key
            selectTab(0, animate = false)
            pillSnap = true
        }
        // 歌曲里没带歌手 ID：先按名字找（null = 还在找，"" = 没找到）
        val src = ref.source
        val id = ref.id.ifEmpty { Library.artistIdFor(src, ref.name) }
        if (!id.isNullOrEmpty()) Library.loadArtist(src, id)
        val info = if (!id.isNullOrEmpty()) Library.artist(src, id) else null
        val failed = id == "" || (!id.isNullOrEmpty() && info == null && Library.artistFailed(src, id))
        updateAccent(info)

        // 窗口矮的时候卡片收小一点（不显示简介），给下面的列表留地方
        val compact = page.height < 600f
        val heroH = if (compact) 150f else 204f
        val hero = LayoutBounds(page.x, page.y, page.width, heroH)
        ui.headerAnim { drawHero(hero, compact, ref, id, info, failed) }

        val albums = info?.let { Library.artistAlbums(it.source, it.id) }
        val dockY = hero.bottom + 14f
        ui.headerAnim { drawDock(page, dockY, info, albums) }

        val panel = LayoutBounds(page.x, dockY + 44f, page.width, page.bottom - (dockY + 44f))
        if (info == null || panel.height < 40f) return
        ui.itemAnim(0) {
            ui.ctx.drawShape(panel, ShapeStyle(Color(1f, 1f, 1f, 0.028f), 22f, 1f, Color(1f, 1f, 1f, 0.06f)))
        }
        val inner = LayoutBounds(panel.x + 12f, panel.y + 14f, panel.width - 24f, panel.height - 14f)

        // 换标签：旧内容往一边滑出去、变淡，新内容从另一边滑进来（和歌单的列表 / 封面墙切换一样）
        val t = tabFade.tick(1f)
        val from = fromTab
        if (from == null || t >= 0.999f) {
            fromTab = null
            drawTab(tab, info, albums, inner)
            return
        }
        // 往右边的标签切：内容往左走；往左切：往右走
        val dir = if (tab > from) 1f else -1f
        val out = (1f - t * 1.6f).coerceIn(0f, 1f)
        if (out > 0.01f) {
            // 旧内容只是画出来：点击区域、滚轮区域都不保留
            val keep = ui.hits.size
            ui.shifted(-dir * 40f * t.coerceIn(0f, 1f), 0f, out) { drawTab(from, info, albums, inner) }
            while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
            screen.tracks.area = LayoutBounds()
            screen.grid.area = LayoutBounds()
            area = LayoutBounds()
        }
        ui.shifted(dir * 40f * (1f - t), 0f, t.coerceIn(0f, 1f)) { drawTab(tab, info, albums, inner) }
    }

    private fun drawTab(which: Int, info: SourceArtist, albums: List<NeteasePlaylist>?, inner: LayoutBounds) {
        when (which) {
            0 -> if (info.hotSongs.isEmpty()) note(inner, I18n.text("No results")) else screen.tracks.drawList(inner, info.hotSongs)
            1 -> when {
                albums == null -> note(inner, I18n.text("Loading..."))
                albums.isEmpty() -> note(inner, I18n.text("No albums"))
                else -> screen.grid.drawItems(LayoutBounds(inner.x + 6f, inner.y + 6f, inner.width - 12f, inner.height - 6f), albums)
            }
            else -> drawAbout(inner, info.brief)
        }
    }

    // 标签切换的过渡：0 → 1
    private val tabFade = Spring(210f, 24f, 1f)
    private var fromTab: Int? = null

    private fun selectTab(i: Int, animate: Boolean = true) {
        if (i == tab) return
        if (animate) {
            fromTab = tab
            tabFade.x = 0f
            tabFade.v = 0f
        } else {
            fromTab = null
        }
        tab = i
        screen.tracks.resetScroll()
        screen.grid.resetScroll()
        aboutTarget = 0f
        aboutPos = 0f
    }

    private fun note(area: LayoutBounds, text: String) {
        ui.itemAnim(0) { ui.text(text, area.x + 12f, area.y + 8f, 12f, Theme.TEXT_TERTIARY) }
    }

    // ---------------------------------------------------------------- 主题色

    private fun updateAccent(info: SourceArtist?) {
        val k = info?.let { "a:${it.source.key}:${it.id}" } ?: ""
        if (k == accentKey) return
        if (k.isEmpty()) return
        val img = CoverCache.image(k) ?: return   // 头像还没下好：下好了再算
        accentKey = k
        accentTarget = accentOf(img)
    }

    /** 头像的代表色：越鲜艳越亮的像素权重越大，再调成在深色玻璃上好看的亮度和饱和度 */
    private fun accentOf(img: BufferedImage): Color {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        val hsb = FloatArray(3)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val argb = img.getRGB(x, y)
                if ((argb ushr 24) >= 128) {
                    val pr = (argb shr 16) and 0xFF
                    val pg = (argb shr 8) and 0xFF
                    val pb = argb and 0xFF
                    java.awt.Color.RGBtoHSB(pr, pg, pb, hsb)
                    val w = (hsb[1] * hsb[1] * hsb[2]).toDouble() + 0.002
                    r += pr * w
                    g += pg * w
                    b += pb * w
                    total += w
                }
                x += 3
            }
            y += 3
        }
        if (total <= 0.0) return NEUTRAL_ACCENT
        java.awt.Color.RGBtoHSB((r / total).toInt(), (g / total).toInt(), (b / total).toInt(), hsb)
        // 几乎没颜色的头像（黑白照片）：用淡淡的白，不硬套一个颜色
        if (hsb[1] < 0.12f) return NEUTRAL_ACCENT
        val rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1].coerceIn(0.45f, 0.8f), 0.95f)
        return Color.fromRGB(rgb and 0xFFFFFF)
    }

    // ---------------------------------------------------------------- 上面的大卡片

    private fun drawHero(hero: LayoutBounds, compact: Boolean, ref: ArtistRef, id: String?, info: SourceArtist?, failed: Boolean) {
        val ctx = ui.ctx
        val a = accent
        // 卡片本身用中性的玻璃（不按头像染色，免得整块偏色）；主题色只用在头像的光和"歌手"标签上
        ctx.drawGlass(hero, HERO_GLASS)

        // 返回
        val bb = LayoutBounds(hero.x + 14f, hero.y + 14f, 28f, 28f)
        ctx.drawShape(bb, ShapeStyle(if (ui.hover(bb)) Theme.CARD_HOVER else Color(1f, 1f, 1f, 0.07f), 14f))
        ui.icon("◀", bb.x + 13.5f, bb.y + 14f, 10f, Theme.TEXT_PRIMARY)
        ui.hits += bb to { screen.goBack() }

        // 头像：后面一团主题色的光，外面一圈细边
        val size = hero.height - (if (compact) 36f else 48f)
        val av = LayoutBounds(hero.x + 56f, hero.y + (hero.height - size) / 2f, size, size)
        ctx.drawShape(av.expand(-size * 0.12f, -size * 0.12f), ShapeStyle(Color.TRANSPARENT, size / 2f,
            shadow = ShadowStyle(a.copy(a = 0.55f), size * 0.45f, Vec2(0f, 0f))))
        val tex = info?.picUrl?.takeIf { it.isNotEmpty() }?.let { CoverCache.rounded("a:${info.source.key}:${info.id}", it, 0.5f) }
        ui.drawCover(av, tex, size / 2f)
        ctx.drawShape(av, ShapeStyle(Color.TRANSPARENT, size / 2f, 1.5f, Color(1f, 1f, 1f, 0.16f)))

        // 右边的数据块（够宽才放）
        val statsW = 150f
        val showStats = info != null && hero.width >= 820f
        val tx = av.right + 30f
        val textRight = if (showStats) hero.right - statsW - 36f else hero.right - 24f
        val maxW = max(60f, textRight - tx)

        // 标签行
        var cy = hero.y + (if (compact) 18f else 26f)
        var chipX = tx
        chipX = chip(chipX, cy, "♪", I18n.text("Artist"), a.copy(a = 0.16f), a.copy(a = 0.35f), textRight)
        info?.alias?.take(2)?.forEach { chipX = chip(chipX, cy, null, it, Color(1f, 1f, 1f, 0.05f), Color(1f, 1f, 1f, 0.09f), textRight) }

        // 名字：大号粗体，带一点投影
        val nameSize = if (compact) 30f else 40f
        val name = ui.trim(info?.name?.takeIf { it.isNotEmpty() } ?: ref.name, maxW, nameSize, Theme.FONT_DISPLAY)
        cy += 30f
        ui.text(name, tx + 1f, cy + 3f, nameSize, Color(0f, 0f, 0f, 0.35f), Theme.FONT_DISPLAY)
        ui.text(name, tx, cy, nameSize, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        cy += nameSize + 10f

        when {
            failed -> {
                val msg = I18n.text("Could not load this artist. Please retry.")
                ui.text(ui.trim(msg, maxW, 11f, Theme.FONT_SEMIBOLD), tx, cy, 11f, RED, Theme.FONT_SEMIBOLD)
                ui.hits += LayoutBounds(tx, cy - 3f, ui.width(msg, 11f, Theme.FONT_SEMIBOLD), 18f) to { Library.retryArtist(ref.source, ref.name, id) }
            }
            info == null -> {
                ui.text(I18n.text(if (id == null) "Finding artist..." else "Loading..."), tx, cy, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                ui.progressBar(LayoutBounds(tx, cy + 20f, 180f, 3f), null)
            }
            else -> {
                // 最多两行简介（完整的在"简介"标签里），放不下就少一行，不压到下面的按钮
                if (!compact && info.brief.isNotBlank()) {
                    val room = ((hero.bottom - 22f - 32f - 10f - cy) / 17f).toInt().coerceIn(0, 2)
                    val lines = ui.wrap2(info.brief.replace('\n', ' '), min(maxW, 560f), 12f).take(room)
                    lines.forEachIndexed { i, line -> ui.text(line, tx, cy + i * 17f, 12f, Theme.TEXT_SECONDARY) }
                } else if (!showStats) {
                    ui.text(I18n.format("%d songs · %d albums", info.songCount, info.albumCount), tx, cy, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
                }
                drawButtons(hero, tx, info)
            }
        }

        if (showStats && info != null) {
            val stats = listOf(
                info.songCount to I18n.text("Songs"),
                info.albumCount to I18n.text("Albums"),
                info.hotSongs.size to I18n.text("Popular songs"),
            )
            val sx = hero.right - statsW - 20f
            val gap = 8f
            val th = (hero.height - 36f - gap * (stats.size - 1)) / stats.size
            stats.forEachIndexed { i, (value, label) ->
                val tb = LayoutBounds(sx, hero.y + 18f + i * (th + gap), statsW, th)
                // 浅色半透明的块，下面一点小小的黑色阴影把它托起来（阴影只在外面，不透进块里）
                ctx.drawShape(tb, ShapeStyle(Color(1f, 1f, 1f, 0.08f), 14f, shadow = ShadowStyle(Color(0f, 0f, 0f, 0.18f), 6f, Vec2(0f, -2f), outside = true)))
                val num = value.toString()
                val ny = tb.y + (th - 17f) / 2f - 1f
                ui.text(num, tb.x + 16f, ny, 17f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
                val lx = tb.x + 24f + ui.width(num, 17f, Theme.FONT_DISPLAY)
                // 英文标签全大写（和参考的样式一样），中文不受影响
                val caps = label.uppercase(java.util.Locale.ROOT)
                ui.text(ui.trim(caps, tb.right - lx - 10f, 9.5f, Theme.FONT_SEMIBOLD), lx, ny + 6.5f, 9.5f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            }
        }
    }

    /** 小圆角标签，返回下一个标签的 x；放不下就不画 */
    private fun chip(x: Float, y: Float, glyph: String?, label: String, fill: Color, border: Color, limit: Float): Float {
        val w = ui.width(label, 10f, Theme.FONT_SEMIBOLD) + 20f + (if (glyph != null) 14f else 0f)
        if (x + w > limit) return x
        val b = LayoutBounds(x, y, w, 20f)
        ui.ctx.drawShape(b, ShapeStyle(fill, 10f, 1f, border))
        var lx = b.x + 10f
        if (glyph != null) {
            ui.icon(glyph, lx + 4f, b.y + 10f, 10f, Theme.TEXT_PRIMARY)
            lx += 14f
        }
        ui.text(label, lx, b.y + 6f, 10f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        return b.right + 6f
    }

    /** 播放 / 随机播放热门歌曲 */
    private fun drawButtons(hero: LayoutBounds, tx: Float, info: SourceArtist) {
        val songs = info.hotSongs
        if (songs.isEmpty()) return
        val by = hero.bottom - 22f - 32f
        val play = I18n.text("Play")
        val pb = LayoutBounds(tx, by, ui.width(play, 12f, Theme.FONT_SEMIBOLD) + 52f, 32f)
        ui.ctx.drawShape(pb.expand(-6f, -4f), ShapeStyle(Color.TRANSPARENT, 16f, shadow = ShadowStyle(RED.copy(a = 0.35f), 16f, Vec2(0f, -3f))))
        ui.pill(pb, RED, play, "▶") { MusicPlayer.playQueue(songs, 0, null) }
        val shuffle = I18n.text("Shuffle")
        val sb = LayoutBounds(pb.right + 10f, by, ui.width(shuffle, 12f, Theme.FONT_SEMIBOLD) + 52f, 32f)
        ui.ctx.drawShape(sb, ShapeStyle(if (ui.hover(sb)) Color(1f, 1f, 1f, 0.12f) else Color(1f, 1f, 1f, 0.07f), 16f, 1f, Color(1f, 1f, 1f, 0.09f)))
        ui.icon("⇄", sb.x + 20f, sb.y + 16f, 12f, Theme.TEXT_PRIMARY)
        ui.text(shuffle, sb.x + 32f, sb.y + 16f - 12f * 0.39f - 0.5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.hits += sb to { MusicPlayer.playQueue(songs, songs.indices.random(), null) }
    }

    // ---------------------------------------------------------------- 标签栏

    /** 标签：和设置页一样的样式（不用玻璃，免得玻璃太多抢了内容）。红色选中块滑过去时被拉长一点 */
    private fun drawDock(page: LayoutBounds, y: Float, info: SourceArtist?, albums: List<NeteasePlaylist>?) {
        val tabs = listOf(
            I18n.text("Popular songs") to info?.hotSongs?.size,
            I18n.text("Albums") to (albums?.size ?: info?.albumCount),
            I18n.text("About") to null,
        )
        // 标签一样宽：按最宽的那个（文字 + 数量）
        val tabW = max(104f, tabs.maxOf { (label, count) ->
            ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f + (if (count != null) ui.width(count.toString(), 10f, Theme.FONT_SEMIBOLD) + 6f else 0f)
        })
        val bar = LayoutBounds(page.x + (page.width - tabW * tabs.size - 8f) / 2f, y, tabW * tabs.size + 8f, 32f)
        ui.card(bar, Theme.CARD, 11f)
        if (pillSnap) {
            tabPill.x = tab.toFloat()
            tabPill.v = 0f
            pillSnap = false
        }
        val p = tabPill.tick(tab.toFloat())
        tabs.indices.forEach { i ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            if (i != tab && ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 8f)
        }
        val stretch = min(18f, abs(tabPill.v) * 6f)
        val px = bar.x + 4f + p.coerceIn(-0.06f, tabs.size - 0.94f) * tabW - (if (tabPill.v > 0f) stretch else 0f)
        ui.ctx.drawShape(LayoutBounds(px, bar.y + 4f, tabW + stretch, 24f), ShapeStyle(RED, 8f))
        tabs.forEachIndexed { i, (label, count) ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            val on = (1f - abs(p - i)).coerceIn(0f, 1f)
            val color = mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, on)
            val lw = ui.width(label, 12f, Theme.FONT_SEMIBOLD)
            val ct = count?.toString()
            val cw = if (ct != null) ui.width(ct, 10f, Theme.FONT_SEMIBOLD) + 6f else 0f
            val lx = b.x + (b.width - lw - cw) / 2f
            ui.text(label, lx, b.y + 12f - 12f * 0.39f - 0.5f, 12f, color, Theme.FONT_SEMIBOLD)
            // 数量：小一号、淡一点，跟在名字后面
            if (ct != null) ui.text(ct, lx + lw + 6f, b.y + 12f - 10f * 0.39f - 0.5f, 10f, color.copy(a = color.a * 0.7f), Theme.FONT_SEMIBOLD)
            ui.hits += b to { selectTab(i) }
        }
    }

    // ---------------------------------------------------------------- 简介

    private fun drawAbout(content: LayoutBounds, text: String) {
        if (text.isBlank()) {
            note(content, I18n.text("No introduction"))
            return
        }
        area = content
        val size = 12.5f
        val lineH = 21f
        val width = min(content.width - 24f, 760f)
        val k = "${text.hashCode()}|${width.toInt()}"
        if (k != wrapKey) {
            wrapKey = k
            wrapped = wrap(text, width, size)
        }
        aboutMax = max(0f, wrapped.size * lineH + ui.bottomInset + 12f - content.height)
        aboutTarget = aboutTarget.coerceIn(0f, aboutMax)
        aboutPos = aboutPos.coerceIn(0f, aboutMax)
        ui.beginClip(content)
        val first = max(0, (aboutPos / lineH).toInt())
        val last = min(wrapped.size - 1, ((aboutPos + content.height) / lineH).toInt() + 1)
        ui.itemAnim(0) {
            for (i in first..last) {
                ui.text(wrapped[i], content.x + 12f, content.y + 6f + i * lineH - aboutPos, size, Theme.TEXT_SECONDARY)
            }
        }
        ui.endClip()
    }

    /** 按宽度换行（中文按字，英文尽量在空格处断开），保留原文的换行 */
    private fun wrap(text: String, maxW: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split('\n')) {
            if (para.isBlank()) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
                continue
            }
            var rest = para.trim()
            while (rest.isNotEmpty()) {
                if (ui.width(rest, size) <= maxW) {
                    out += rest
                    break
                }
                // 二分找这一行最多放几个字
                var lo = 1
                var hi = rest.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (ui.width(rest.substring(0, mid), size) <= maxW) lo = mid else hi = mid - 1
                }
                var cut = lo
                // 断在英文单词中间：退回到前一个空格
                if (cut < rest.length && rest[cut] != ' ' && rest[cut - 1] != ' ' && rest[cut].code < 0x2E80) {
                    val space = rest.lastIndexOf(' ', cut - 1)
                    if (space > cut / 2) cut = space + 1
                }
                out += rest.substring(0, cut).trimEnd()
                rest = rest.substring(cut).trimStart()
            }
        }
        return out
    }

    private companion object {
        /** 头像取不出颜色时的主题色：淡白 */
        val NEUTRAL_ACCENT = Color(0.92f, 0.92f, 0.96f, 1f)
        /** 上面大卡片的玻璃 */
        val HERO_GLASS = Theme.GLASS_SIDEBAR.copy(radius = 26f, frost = 0.7f,
            shadow = ShadowStyle(Color(0f, 0f, 0f, 0.16f), 22f, Vec2(0f, -5f)))
    }
}

/**
 * 歌手名（几个歌手用 " / " 隔开）：每个名字都能点，点了打开那个歌手的页面。
 * 只有当前选的音源的歌能点（歌手页的专辑要用当前音源打开）。[clip] 是所在列表的裁剪区域（点击区域不超出它）
 */
internal fun drawArtistLinks(
    screen: MusicScreen, t: NeteaseTrack, x: Float, y: Float, maxW: Float, size: Float, color: Color, clip: LayoutBounds? = null,
) {
    val ui = screen.ui
    val names = t.artists
    if (names.isEmpty()) return
    if (t.source != MusicSources.selected || !MusicSources.api(t.source).hasArtists) {
        ui.text(ui.trim(names.joinToString(" / "), maxW, size), x, y, size, color)
        return
    }
    val sep = " / "
    val sepW = ui.width(sep, size)
    var cx = x
    for ((i, name) in names.withIndex()) {
        val room = x + maxW - cx
        if (room <= 8f) break
        val shown = ui.trim(name, room, size)
        val w = ui.width(shown, size)
        val b = LayoutBounds(cx, y - 2f, w, size + 5f)
        val hov = ui.hover(b) && (clip == null || clip.contains(ui.mx, ui.my))
        ui.text(shown, cx, y, size, if (hov) Theme.TEXT_PRIMARY else color)
        val id = t.artistIds.getOrNull(i).orEmpty()
        ui.hits += (if (clip != null) b.intersect(clip) else b) to { screen.openArtist(ArtistRef(t.source, id, name)) }
        cx += w
        if (shown != name || i == names.size - 1) break
        if (x + maxW - cx < sepW + 8f) break
        ui.text(sep, cx, y, size, color)
        cx += sepW
    }
}

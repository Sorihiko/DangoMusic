package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.update.UpdateChecker
import me.sorihiko.dango.utils.Color

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页（侧栏齿轮）：两个标签 Lyrics / Music & HUD，每个标签是若干分组卡片，两列瀑布流排开。
 * 直接读写各模块的 @ModuleValue，改了立刻生效，ConfigManager 会自动存盘。
 */
internal class SettingsPage(private val screen: MusicScreen, private val module: NeteaseMusicModule) {
    private val ui = screen.ui

    private enum class Ctl { TOGGLE, SLIDER, SWATCH, KEY, LANGUAGE, TOKEN, STATUS }

    /** scale：显示值 = 实际值 × scale（百分比的选项是 100），手动输入时按显示值换算回去 */
    private class Row(
        val label: String, val desc: String, val value: Any, val ctl: Ctl,
        val dep: BooleanValue?, val fmt: (Any) -> String, val scale: Float = 1f,
    )

    /** owner：所属模块（模块关掉时整组变暗）；toggle：组标题右边显示模块总开关 */
    private class Group(val title: String, val sub: String, val owner: Module, val toggle: Boolean, val rows: List<Row>)

    /** 显示成百分比的选项 */
    private val percentKeys = setOf("volume", "glowRadius", "posX", "posY")

    private val swatchColors = listOf(
        Color(1f, 1f, 1f, 1f),            // 跟随字色
        Color(1f, 0.38f, 0.62f, 1f),      // 粉
        Color(0.32f, 0.66f, 1f, 1f),      // 蓝
        Color(1f, 0.74f, 0.30f, 1f),      // 金
    )

    private val tabs: List<List<Group>> by lazy { buildSettings() }

    /** 本帧画了设置列表的区域（滚轮用），没画就是空的 */
    var area = LayoutBounds()
    // 平滑滚动（逻辑像素）
    private var target = 0f
    private var pos = 0f
    private var maxScroll = 0f

    // 滑条拖动
    private var dragValue: Any? = null
    private var dragTrack = LayoutBounds()

    // 点数值手动输入
    private var editRow: Row? = null
    private var editText = ""
    private var editRect = LayoutBounds()

    // 正在录快捷键的那一项，以及本帧所有快捷键按钮的区域（点别处就取消录制）
    var bindingKey: IntValue? = null
    private val keyButtons = mutableListOf<LayoutBounds>()
    private val keyResetButtons = mutableListOf<Hit>()

    val editing: Boolean get() = editRow != null

    private var copiedAt = 0L

    // 标签切换：红色选中块的位置（会被拉长一点，像液体）、内容过渡的进度、从哪个标签切过来、旧标签的滚动位置
    private val tabPill = Spring(300f, 23f, ScreenState.settingsTab.toFloat())
    private val tabFade = Spring(210f, 24f, 1f)
    private var tabFrom = -1
    private var tabFromPos = 0f

    /** 每个设置项的亮度（变暗 / 变亮的渐变） */
    private val rowFade = HashMap<Row, Spring>()
    /** 发光颜色：每个色块自己的选中圈（选中时长出来、取消时缩回去） */
    private val swatchRing = HashMap<Pair<IntValue, Int>, Spring>()
    /** 发光颜色：每个色块的悬停放大 / 点击弹跳 */
    private val swatchPop = HashMap<Pair<IntValue, Int>, Pair<Spring, Spring>>()
    /** 语言：红色选中块的位置 */
    private val langPill = HashMap<IntValue, Spring>()

    // ---------------------------------------------------------------- 每帧

    fun tick() {
        pos = ui.approach(pos, target, 70f)
        // 滑条：按住拖动
        if (dragValue != null) {
            if (DangoClient.platform.isMouseDown(0)) applyDrag() else dragValue = null
        }
    }

    fun beginFrame() {
        keyButtons.clear()
        keyResetButtons.clear()
    }

    fun resetScroll() {
        target = 0f
        pos = 0f
    }

    fun scroll(wheel: Int) {
        target = (target + (if (wheel > 0) -60f else 60f) * (if (DangoClient.platform.ctrlDown) 4f else 1f)).coerceIn(0f, maxScroll)
    }

    fun stopDrag() {
        dragValue = null
    }

    // ---------------------------------------------------------------- 数值输入

    private fun numberText(r: Row): String = when (val v = r.value) {
        is IntValue -> (v.value * r.scale).roundToInt().toString()
        is FloatValue -> {
            val x = v.value * r.scale
            if (r.scale != 1f || v.decimals == 0) x.roundToInt().toString() else "%.${v.decimals}f".format(x)
        }
        else -> ""
    }

    private fun startEdit(r: Row) {
        if (editRow === r) return
        commitEdit()
        editRow = r
        editText = numberText(r)
        screen.blurTextFields()
        bindingKey = null
    }

    /** 输入的数字写回去（超出范围的自动夹到范围内，乱输的忽略） */
    fun commitEdit() {
        val r = editRow ?: return
        editRow = null
        val n = editText.trim().replace(',', '.').toFloatOrNull() ?: return
        when (val v = r.value) {
            is IntValue -> v.setValue((n / r.scale).roundToInt())
            is FloatValue -> v.setValue(n / r.scale)
        }
    }

    /** 点到别处时：收起快捷键录制和数值输入 */
    fun onClickElsewhere(hit: Hit?) {
        if (bindingKey != null && (hit == null || hit.first !in keyButtons)) bindingKey = null
        if (editRow != null && (hit == null || hit.first != editRect.intersect(area))) commitEdit()
    }

    // ---------------------------------------------------------------- 选项列表

    private fun fieldsOf(m: Module): LinkedHashMap<String, Pair<ModuleValue, Any>> {
        val out = LinkedHashMap<String, Pair<ModuleValue, Any>>()
        for (f in m.javaClass.declaredFields) {
            val ann = f.getAnnotation(ModuleValue::class.java) ?: continue
            f.isAccessible = true
            val v = f.get(m) ?: continue
            out[f.name] = ann to v
        }
        return out
    }

    private fun fmtValue(v: Any): String = when (v) {
        is IntValue -> v.value.toString()
        is FloatValue -> "%.${v.decimals}f".format(v.value)
        else -> ""
    }

    private fun percent(v: Any): String = "${((v as FloatValue).value * 100f).roundToInt()}%"

    private fun buildSettings(): List<List<Group>> {
        val mm = DangoMusic.instance.moduleManager

        /** 一个模块的设置；take(...) 按名字取出若干行，剩下的最后放进 "Other" */
        class Src(val m: Module, val deps: Map<String, String> = emptyMap(), val fmts: Map<String, (Any) -> String> = emptyMap()) {
            val all = fieldsOf(m)
            val left = LinkedHashMap(all)
            fun take(names: List<String>? = null): List<Row> = (names ?: left.keys.toList()).mapNotNull { k ->
                val (ann, v) = left.remove(k) ?: return@mapNotNull null
                val ctl = when {
                    k == "language" -> Ctl.LANGUAGE
                    v is BooleanValue -> Ctl.TOGGLE
                    k == "glowColor" -> Ctl.SWATCH
                    k == "openKey" || (k.startsWith("key") && v is IntValue) -> Ctl.KEY
                    v is IntValue || v is FloatValue -> Ctl.SLIDER
                    else -> return@mapNotNull null
                }
                val dep = deps[k]?.let { all[it]?.second as? BooleanValue }
                Row(ann.name.ifEmpty { k }, ann.desc, v, ctl, dep, fmts[k] ?: ::fmtValue, if (k in percentKeys) 100f else 1f)
            }
        }

        val ly = Src(
            mm.get(LyricsModule::class.java),
            deps = mapOf(
                "distance" to "worldMode", "facePlayer" to "worldMode", "keepAway" to "worldMode", "stayOnScreen" to "worldMode", "aboveGround" to "worldMode", "spread" to "worldMode",
                "glowColor" to "glow", "glowStrength" to "glow", "glowRadius" to "glow",
                "wiggleFps" to "wiggle", "wiggleAmount" to "wiggle",
                "flashSize" to "textFlash",
            ),
            fmts = mapOf(
                "spread" to { v: Any -> "${(v as FloatValue).value.roundToInt()}°" },
                "glowRadius" to ::percent,
            ),
        )
        val lyrics = mutableListOf(
            Group("Placement", "In the world", ly.m, false, ly.take(listOf("worldMode", "distance", "facePlayer", "keepAway", "stayOnScreen", "aboveGround", "spread", "scale", "smoothFont", "splitLines"))),
            Group("Glow", "Deep Glow", ly.m, false, ly.take(listOf("glow", "glowColor", "glowStrength", "glowRadius"))),
            Group("Motion", "Animation", ly.m, false, ly.take(listOf("wiggle", "wiggleFps", "wiggleAmount", "textFlash", "wordPop", "flashSize"))),
        )
        // 左上角的原版风格"正在播放"和 Song card 是同一类东西，放到 Music & HUD 里
        val nowPlaying = Group("Now playing", "Classic overlay", ly.m, false, ly.take(listOf("nowPlaying", "showCover")))
        ly.take().let { if (it.isNotEmpty()) lyrics += Group("Other", "More", ly.m, false, it) }

        val player = Src(module, fmts = mapOf("volume" to ::percent))
        player.left.remove("shuffle")   // 随机播放用播放栏上的按钮就够了，设置页不再显示（状态照样保存）
        player.left.remove("repeatOne") // 单曲循环同样通过播放栏控制
        player.left.remove("musicSource") // 音源通过侧栏切换。
        player.left.remove("saltMode")  // Salt 接入单独一组（含令牌与状态）
        player.left.remove("saltPort")
        val card = Src(mm.get(SongCardModule::class.java), fmts = mapOf("posX" to ::percent, "posY" to ::percent))
        val island = Src(mm.get(DynamicIslandModule::class.java), fmts = mapOf("notifyTime" to { v: Any -> "%.1f s".format((v as FloatValue).value) }))
        // 先取走，免得落进下面的 Hotkeys 组
        val updates = player.take(listOf("checkUpdates"))
        // Salt Player 接入：模式 / 端口 / 令牌 / 状态
        val saltRows = listOf(
            Row(
                "Enable Salt Player", "Add Salt Player to the source menu (needs the SaltBridge plugin)",
                module.saltMode, Ctl.TOGGLE, null, ::fmtValue,
            ),
            Row(
                "Bridge port", "Local port of the SaltBridge plugin · default 8737",
                module.saltPort, Ctl.SLIDER, null, ::fmtValue,
            ),
            Row("Access token", "Auto-discovered from SaltBridge · or paste manually", module, Ctl.TOKEN, null, ::fmtValue),
            Row("Connection", "SaltBridge link status", module, Ctl.STATUS, null, ::fmtValue),
        )
        val music = listOf(
            Group("Player", "Playback", player.m, false, player.take(listOf("volume", "language"))),
            Group("Salt Player", "Local player bridge", player.m, false, saltRows),
            Group("Hotkeys", "Click, then press a key · Backspace clears", player.m, false, player.take()),
            Group("Song card", "Overlay", card.m, true, card.take()),
            nowPlaying,
            Group("Dynamic Island", "Overlay", island.m, true, island.take()),
            Group("Updates", "GitHub releases", player.m, false, updates),
        )
        return listOf(lyrics, music)
    }

    // ---------------------------------------------------------------- 绘制

    fun draw(page: LayoutBounds) {
        ui.headerAnim {
            ui.text(I18n.text("Settings"), page.x, page.y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            ui.text(I18n.text("Changes apply instantly and are saved automatically"), page.x, page.y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
            drawTabs(page)
        }

        var top = page.y + 54f
        // 有新版本时在最上面常驻一条（设置里关掉"检查更新"就不显示）
        val update = UpdateChecker.available?.takeIf { module.checkUpdates.get() }
        if (update != null) {
            drawUpdate(LayoutBounds(page.x, top, page.width - 12f, 40f), update)
            top += 50f
        }
        val view = LayoutBounds(page.x, top, page.width, page.bottom - top)
        area = view
        val tab = ScreenState.settingsTab.coerceIn(0, tabs.size - 1)
        val contentH = layoutHeight(tabs[tab], view)
        maxScroll = max(0f, contentH - view.height)
        target = target.coerceIn(0f, maxScroll)
        pos = pos.coerceIn(0f, maxScroll)

        // 切标签：旧内容往一边滑走淡出，新内容从另一边滑进来淡入（弹簧，略带回弹）
        val t = tabFade.tick(1f)
        val from = tabFrom
        ui.beginClip(view)
        if (from >= 0 && from != tab && t < 0.999f) {
            val dir = if (tab > from) 1f else -1f
            val out = (1f - t * 1.6f).coerceIn(0f, 1f)
            if (out > 0.01f) {
                // 旧内容只是画出来，点击区域不保留
                val keep = ui.hits.size
                ui.shifted(-dir * 36f * t.coerceIn(0f, 1f), 0f, out) { drawTab(tabs[from], view, tabFromPos, animate = false) }
                while (ui.hits.size > keep) ui.hits.removeAt(ui.hits.size - 1)
            }
            ui.shifted(dir * 36f * (1f - t), 0f, t.coerceIn(0f, 1f)) { drawTab(tabs[tab], view, pos, animate = false) }
        } else {
            tabFrom = -1
            drawTab(tabs[tab], view, pos, animate = true)
        }
        ui.endClip()

        // 滚动条（只显示位置）
        if (maxScroll > 0f && contentH > 0f) {
            val th = max(30f, view.height * view.height / contentH)
            val ty = view.y + (view.height - th) * (pos / maxScroll)
            ui.ctx.drawShape(LayoutBounds(view.right - 4f, ty, 4f, th), ShapeStyle(Color(1f, 1f, 1f, 0.30f), 2f))
        }
    }

    // 两列瀑布流：每组放进当前较矮的一列
    private val gap = 14f
    private val rowH = 40f
    private val headH = 36f

    /** 每组的位置：列号、在列里的 y、高度 */
    private fun layout(groups: List<Group>, view: LayoutBounds): List<Triple<Int, Float, Float>> {
        val cols = if (view.width >= 560f) 2 else 1
        val colY = FloatArray(cols)
        return groups.map { g ->
            val h = headH + g.rows.size * rowH + 6f
            var c = 0
            for (i in 1 until cols) if (colY[i] < colY[c] - 0.5f) c = i
            val p = Triple(c, colY[c], h)
            colY[c] += h + gap
            p
        }
    }

    private fun layoutHeight(groups: List<Group>, view: LayoutBounds): Float =
        max(0f, (layout(groups, view).maxOfOrNull { it.second + it.third + gap } ?: 0f) - gap)

    /** 画一个标签的全部分组；[animate] 时每组按进页面的时间依次升起 */
    private fun drawTab(groups: List<Group>, view: LayoutBounds, scroll: Float, animate: Boolean) {
        val cols = if (view.width >= 560f) 2 else 1
        val colW = (view.width - 12f - gap * (cols - 1)) / cols
        val placed = layout(groups, view)
        groups.forEachIndexed { gi, g ->
            val (c, gy, gh) = placed[gi]
            val b = LayoutBounds(view.x + c * (colW + gap), view.y + gy - scroll, colW, gh)
            if (b.bottom < view.y || b.y > view.bottom) return@forEachIndexed
            if (animate) ui.itemAnim(gi) { drawGroup(b, g, view, rowH, headH) } else drawGroup(b, g, view, rowH, headH)
        }
    }

    /** 新版本提醒：版本号 + 复制下载链接（只提醒，不下载） */
    private fun drawUpdate(b: LayoutBounds, info: UpdateChecker.Info) {
        ui.card(b, RED.copy(a = 0.16f), 12f)
        val label = I18n.text("New version available: ") + info.version
        ui.text(label, b.x + 14f, b.y + 9f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val current = UpdateChecker.currentVersion
        if (current.isNotEmpty()) ui.text(I18n.text("You have ") + current, b.x + 14f, b.y + 25f, 10f, Theme.TEXT_SECONDARY)
        val copied = System.currentTimeMillis() - copiedAt < 1500
        val text = if (copied) I18n.text("Copied") else I18n.text("Copy download link")
        val w = ui.width(text, 12f, Theme.FONT_SEMIBOLD) + 30f
        ui.pill(LayoutBounds(b.right - w - 8f, b.y + 6f, w, 28f), Color(1f, 1f, 1f, 0.14f), text) {
            DangoClient.platform.clipboard = info.download
            copiedAt = System.currentTimeMillis()
        }
    }

    /** 标签：Lyrics / Music & HUD。红色选中块用弹簧滑过去，滑动时被拉长一点；文字颜色跟着渐变 */
    private fun drawTabs(page: LayoutBounds) {
        val names = listOf(I18n.text("Lyrics"), I18n.text("Music & HUD"))
        val tabW = 104f
        val bar = LayoutBounds(page.right - tabW * names.size - 8f, page.y + 2f, tabW * names.size + 8f, 32f)
        ui.card(bar, Theme.CARD, 11f)
        val p = tabPill.tick(ScreenState.settingsTab.toFloat())
        names.forEachIndexed { i, _ ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            if (i != ScreenState.settingsTab && ui.hover(b)) ui.card(b, Theme.CARD_HOVER, 8f)
        }
        val stretch = kotlin.math.min(18f, kotlin.math.abs(tabPill.v) * 6f)
        val px = bar.x + 4f + p.coerceIn(-0.06f, names.size - 0.94f) * tabW - (if (tabPill.v > 0f) stretch else 0f)
        ui.ctx.drawShape(LayoutBounds(px, bar.y + 4f, tabW + stretch, 24f), ShapeStyle(RED, 8f))
        names.forEachIndexed { i, n ->
            val b = LayoutBounds(bar.x + 4f + i * tabW, bar.y + 4f, tabW, 24f)
            val on = (1f - kotlin.math.abs(p - i)).coerceIn(0f, 1f)
            val w = ui.width(n, 12f, Theme.FONT_SEMIBOLD)
            ui.text(n, b.x + (b.width - w) / 2f, b.y + 12f - 12f * 0.39f - 0.5f, 12f,
                mixColor(Theme.TEXT_SECONDARY, Theme.TEXT_PRIMARY, on), Theme.FONT_SEMIBOLD)
            ui.hits += b to {
                if (ScreenState.settingsTab != i) {
                    commitEdit()
                    tabFrom = ScreenState.settingsTab
                    tabFromPos = pos
                    tabFade.x = 0f
                    tabFade.v = 0f
                    ScreenState.settingsTab = i
                    resetScroll()
                    bindingKey = null
                }
            }
        }
    }

    private fun drawGroup(b: LayoutBounds, g: Group, clip: LayoutBounds, rowH: Float, headH: Float) {
        val ctx = ui.ctx
        ctx.drawShape(b, ShapeStyle(Color(1f, 1f, 1f, 0.05f), 14f))
        val x = b.x + 14f
        val iw = b.width - 28f
        val title = I18n.text(g.title)
        ui.text(title, x, b.y + 13f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        ui.text(I18n.text(g.sub), x + ui.width(title, 13f, Theme.FONT_SEMIBOLD) + 8f, b.y + 14.5f, 10f, Theme.TEXT_TERTIARY)
        if (g.toggle) {
            val m = g.owner
            ui.toggleSwitch(LayoutBounds(b.right - 14f - 34f, b.y + 9f, 34f, 20f), m.enabled, clip, key = m) { m.enabled = !m.enabled }
        }
        var y = b.y + headH
        val moduleOff = !g.owner.enabled
        for (r in g.rows) {
            ctx.drawShape(LayoutBounds(x, y, iw, 1f), ShapeStyle(Color(1f, 1f, 1f, 0.06f)))
            // 依赖的开关 / 整个模块关掉时变暗：渐变过去，不是一下子变
            val dim = moduleOff || (r.dep != null && !r.dep.get())
            val target = if (dim) 0.4f else 1f
            val alpha = rowFade.getOrPut(r) { Spring(170f, 26f, target) }.tick(target).coerceIn(0.4f, 1f)
            val faded = alpha < 0.999f
            if (faded) ctx.pushOpacity(alpha)
            drawRow(LayoutBounds(x, y, iw, rowH), r, clip)
            if (faded) ctx.popOpacity()
            y += rowH
        }
    }

    private fun drawRow(b: LayoutBounds, r: Row, clip: LayoutBounds) {
        val ctlW = when (r.ctl) {
            Ctl.TOGGLE -> 34f
            Ctl.SLIDER -> 166f
            Ctl.SWATCH -> 16f * 4 + 8f * 3
            Ctl.KEY -> 130f
            Ctl.LANGUAGE -> 142f
            Ctl.TOKEN -> 210f
            Ctl.STATUS -> 210f
        }
        val textW = b.width - ctlW - 14f
        ui.text(ui.trim(I18n.text(r.label), textW, 12f, Theme.FONT_MEDIUM), b.x, b.y + 8f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        if (r.desc.isNotEmpty()) ui.text(ui.trim(I18n.text(r.desc), textW, 9.5f), b.x, b.y + 24f, 9.5f, Theme.TEXT_TERTIARY)
        val cy = b.y + b.height / 2f
        val cx = b.right - ctlW
        when (r.ctl) {
            Ctl.TOGGLE -> {
                val v = r.value as BooleanValue
                ui.toggleSwitch(LayoutBounds(cx, cy - 10f, 34f, 20f), v.get(), clip, key = v) { v.toggle() }
            }
            Ctl.SLIDER -> drawSlider(b, r, clip, cx, cy)
            Ctl.SWATCH -> {
                // 每个色块有自己的选中圈：选中的从色块边上放大长出来（稍微冲过一点再回来），
                // 原来选中的那个圈缩回色块里消失；色块悬停时放大一点，点一下弹一下
                val v = r.value as IntValue
                swatchColors.forEachIndexed { i, col ->
                    if (i > v.maxRange) return@forEachIndexed
                    val sb = LayoutBounds(cx + i * 24f, cy - 8f, 16f, 16f)
                    val target = if (v.value == i) 1f else 0f
                    val ring = swatchRing.getOrPut(v to i) { Spring(300f, 17f, target) }.tick(target)
                    if (ring > 0.01f) {
                        val grow = 3f * ring
                        ui.ctx.drawShape(sb.expand(grow, grow), ShapeStyle(Color(0f, 0f, 0f, 0f), 8f + grow, 1.5f,
                            Theme.TEXT_PRIMARY.copy(a = Theme.TEXT_PRIMARY.a * ring.coerceIn(0f, 1f))))
                    }
                    val (hover, pop) = swatchPop.getOrPut(v to i) { Spring(300f, 24f) to Spring(420f, 13f) }
                    val k = 1f + hover.tick(if (ui.hover(sb.expand(4f, 4f))) 1f else 0f) * 0.15f + pop.tick(0f) * 0.12f
                    ui.ctx.drawShape(sb.scaled(k), ShapeStyle(col, 8f * k))
                    ui.hits += sb.expand(4f, 4f).intersect(clip) to {
                        pop.v += 6f
                        v.setValue(i)
                    }
                }
            }
            Ctl.KEY -> drawKeyButton(r.value as IntValue, clip, cx, cy)
            Ctl.TOKEN -> drawTokenRow(r.value as NeteaseMusicModule, clip, cx, cy)
            Ctl.STATUS -> drawStatusRow(r.value as NeteaseMusicModule, clip, cx, cy)
            Ctl.LANGUAGE -> {
                // 红色选中块用弹簧滑到另一个语言上
                val v = r.value as IntValue
                val sel = langPill.getOrPut(v) { Spring(320f, 24f, v.value.coerceAtLeast(0).toFloat()) }.tick(v.value.coerceAtLeast(0).toFloat())
                listOf("English", "中文").forEachIndexed { i, _ ->
                    val button = LayoutBounds(cx + i * 74f, cy - 12f, 68f, 24f)
                    ui.card(button, if (ui.hover(button) && v.value != i) Theme.CARD_HOVER else Theme.CARD, 7f)
                }
                if (v.value >= 0) ui.card(LayoutBounds(cx + sel * 74f, cy - 12f, 68f, 24f), RED, 7f)
                listOf("English", "中文").forEachIndexed { i, label ->
                    val button = LayoutBounds(cx + i * 74f, cy - 12f, 68f, 24f)
                    ui.text(label, button.x + (button.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
                        cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                    ui.hits += button.intersect(clip) to {
                        v.setValue(i)
                        module.applyLanguage()
                    }
                }
            }
        }
    }

    private fun drawSlider(b: LayoutBounds, r: Row, clip: LayoutBounds, cx: Float, cy: Float) {
        val ctx = ui.ctx
        val track = LayoutBounds(cx + 2f, cy - 2f, 108f, 4f)
        val p = when (val v = r.value) {
            is IntValue -> v.normalize()
            is FloatValue -> v.normalize()
            else -> 0f
        }.coerceIn(0f, 1f)
        val active = dragValue === r.value
        if (active) dragTrack = track
        ctx.drawShape(track, ShapeStyle(Theme.TRACK, 2f))
        ctx.drawShape(LayoutBounds(track.x, track.y, track.width * p, track.height), ShapeStyle(RED, 2f))
        val k = if (active || ui.hover(track.expand(6f, 10f))) 14f else 11f
        ctx.drawShape(LayoutBounds(track.x + track.width * p - k / 2f, cy - k / 2f, k, k), ShapeStyle(Color(1f, 1f, 1f, 1f), k / 2f))
        // 数值：点一下可以直接输入
        val vb = LayoutBounds(b.right - 46f, cy - 10f, 46f, 20f)
        if (editRow === r) {
            editRect = vb
            ui.card(vb, Theme.CARD_HOVER, 6f)
            ctx.drawShape(vb, ShapeStyle(Color(0f, 0f, 0f, 0f), 6f, 1f, RED))
            val caret = if (caretVisible()) "|" else ""
            val et = editText + caret
            ui.text(et, vb.right - 6f - ui.width(et, 11f, Theme.FONT_SEMIBOLD), cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        } else {
            val s = r.fmt(r.value)
            val sw = ui.width(s, 11f, Theme.FONT_SEMIBOLD)
            val hot = ui.hover(vb)
            if (hot) ui.card(LayoutBounds(b.right - sw - 6f, cy - 10f, sw + 6f, 20f), Theme.CARD_HOVER, 6f)
            ui.text(s, b.right - sw - (if (hot) 3f else 0f), cy - 11f * 0.39f - 0.5f, 11f,
                if (active) RED else if (hot) Theme.TEXT_PRIMARY else Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        }
        ui.hits += vb.intersect(clip) to { startEdit(r) }
        ui.hits += track.expand(8f, 12f).intersect(clip) to {
            dragValue = r.value
            dragTrack = track
            applyDrag()
        }
    }

    private fun drawKeyButton(v: IntValue, clip: LayoutBounds, cx: Float, cy: Float) {
        val kb = LayoutBounds(cx, cy - 12f, 72f, 24f)
        val binding = bindingKey === v
        ui.card(kb, if (binding) RED.copy(a = 0.22f) else if (ui.hover(kb)) Theme.CARD_HOVER else Theme.CARD, 7f)
        if (binding) ui.ctx.drawShape(kb, ShapeStyle(Color(0f, 0f, 0f, 0f), 7f, 1f, RED))
        val name = when {
            binding -> I18n.text("Press a key")
            v.value == 0 -> I18n.text("None")
            else -> Keys.name(v.value) ?: "#${v.value}"
        }
        val s = ui.trim(name, kb.width - 10f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(s, kb.x + (kb.width - ui.width(s, 11f, Theme.FONT_SEMIBOLD)) / 2f, cy - 11f * 0.39f - 0.5f, 11f,
            if (binding) RED else if (v.value == 0) Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)
        val hb = kb.intersect(clip)
        keyButtons += hb
        ui.hits += hb to { bindingKey = if (bindingKey === v) null else v }

        val reset = LayoutBounds(kb.right + 6f, kb.y, 52f, kb.height)
        ui.card(reset, if (ui.hover(reset)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val label = I18n.text("Reset")
        ui.text(label, reset.x + (reset.width - ui.width(label, 11f, Theme.FONT_MEDIUM)) / 2f,
            cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        val resetHit: Hit = reset.intersect(clip) to {
            bindingKey = null
            module.resetKeyBinding(v)
        }
        keyResetButtons += resetHit
        ui.hits += resetHit
    }

    /** 令牌行：遮罩显示 + 粘贴 / 清除。令牌本身不画全、不进日志 */
    private fun drawTokenRow(m: NeteaseMusicModule, clip: LayoutBounds, cx: Float, cy: Float) {
        val masked = m.saltTokenMask().ifEmpty { "—" }
        ui.text(ui.trim(masked, 62f, 11f, Theme.FONT_SEMIBOLD), cx, cy - 11f * 0.39f - 0.5f, 11f,
            if (m.saltTokenMask() == "—") Theme.TEXT_TERTIARY else Theme.TEXT_PRIMARY, Theme.FONT_SEMIBOLD)

        val paste = LayoutBounds(cx + 68f, cy - 12f, 66f, 24f)
        ui.card(paste, if (ui.hover(paste)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val pl = I18n.text("Paste")
        ui.text(pl, paste.x + (paste.width - ui.width(pl, 11f, Theme.FONT_MEDIUM)) / 2f,
            cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
        ui.hits += paste.intersect(clip) to {
            m.pasteSaltToken()
        }

        val clear = LayoutBounds(cx + 140f, cy - 12f, 66f, 24f)
        ui.card(clear, if (ui.hover(clear)) Theme.CARD_HOVER else Theme.CARD, 7f)
        val cl = I18n.text("Clear")
        ui.text(cl, clear.x + (clear.width - ui.width(cl, 11f, Theme.FONT_MEDIUM)) / 2f,
            cy - 11f * 0.39f - 0.5f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        ui.hits += clear.intersect(clip) to {
            m.clearSaltToken()
        }
    }

    /** 连接状态行：动态文案 + 颜色 */
    private fun drawStatusRow(m: NeteaseMusicModule, clip: LayoutBounds, cx: Float, cy: Float) {
        val switching = m.saltSwitchNote.isNotEmpty()
        val text = I18n.text(if (switching) m.saltSwitchNote else m.saltStatusText())
        val color = when {
            switching -> Color(1f, 0.72f, 0.30f, 1f)
            m.saltConnection() == me.sorihiko.dango.playback.salt.SaltSessionSource.Connection.READY -> Color(0.42f, 0.86f, 0.52f, 1f)
            m.saltConnection() == me.sorihiko.dango.playback.salt.SaltSessionSource.Connection.STOPPED -> Theme.TEXT_TERTIARY
            m.saltConnection() == me.sorihiko.dango.playback.salt.SaltSessionSource.Connection.CONNECTING -> Theme.TEXT_SECONDARY
            else -> Color(1f, 0.45f, 0.45f, 1f)
        }
        val shown = ui.trim(text, 206f, 11f, Theme.FONT_SEMIBOLD)
        ui.text(shown, cx + 206f - ui.width(shown, 11f, Theme.FONT_SEMIBOLD), cy - 11f * 0.39f - 0.5f, 11f,
            color, Theme.FONT_SEMIBOLD)
    }

    /** 重置优先于鼠标录制，避免点击重置时反而绑定成鼠标左键。 */
    fun resetKeyAt(x: Int, y: Int): Boolean {
        val hit = keyResetButtons.firstOrNull { it.first.contains(x, y) } ?: return false
        commitEdit()
        screen.blurTextFields()
        hit.second.invoke()
        return true
    }

    private fun applyDrag() {
        val v = dragValue ?: return
        if (dragTrack.width <= 0f) return
        val p = ((ui.mx - dragTrack.x) / dragTrack.width).coerceIn(0f, 1f)
        when (v) {
            is IntValue -> v.setValue((v.minRange + p * (v.maxRange - v.minRange)).roundToInt())
            is FloatValue -> v.setValue(v.minRange + p * (v.maxRange - v.minRange))
        }
    }

    // ---------------------------------------------------------------- 按键

    /** 正在输入数值或录快捷键时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        // 正在输入数值：Enter 确定，Esc 取消
        if (editRow != null) {
            when {
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitEdit()
                keyCode == Keys.ESCAPE -> editRow = null
                keyCode == Keys.BACK -> if (editText.isNotEmpty()) editText = editText.dropLast(1)
                DangoClient.platform.ctrlDown && keyCode == Keys.A -> editText = ""
                (typedChar.isDigit() || typedChar == '.' || typedChar == ',' || typedChar == '-') && editText.length < 10 ->
                    editText += typedChar
            }
            return true
        }
        // 正在录按键：Esc 取消；Backspace / Delete 清空。
        val v = bindingKey ?: return false
        when {
            keyCode == Keys.ESCAPE -> {}
            (keyCode == Keys.BACK || keyCode == Keys.DELETE) && v.minRange <= 0 -> v.setValue(0)
            keyCode != 0 -> v.setValue(keyCode)
        }
        bindingKey = null
        return true
    }

    fun bindMouse(button: Int): Boolean {
        val value = bindingKey ?: return false
        value.setValue(Keys.mouse(button))
        bindingKey = null
        return true
    }
}

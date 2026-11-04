package me.sorihiko.dango.musicui

import me.sorihiko.dango.i18n.I18n

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.music.MusicScreen
import me.sorihiko.dango.party.Firebase
import me.sorihiko.dango.party.ServerTransport
import me.sorihiko.dango.party.ListenParty
import me.sorihiko.dango.party.PartyProxy
import me.sorihiko.dango.party.PartyTransport
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.ui.LayoutBounds
import me.sorihiko.dango.ui.ShapeStyle
import me.sorihiko.dango.ui.Theme
import me.sorihiko.dango.utils.Color

/** 一起听歌：开房间 / 输房间码加入 / 房间里有谁 */
internal class PartyPage(private val screen: MusicScreen) {
    private val ui = screen.ui

    var codeInput = ""
    var codeFocused = false
    var codeRect = LayoutBounds()
        private set
    /** 手动代理地址输入框 */
    var proxyInput = ""
    var proxyFocused = false
    var proxyRect = LayoutBounds()
        private set
    private var copiedAt = 0L

    fun draw(area: LayoutBounds) {
        val ctx = ui.ctx
        val x = area.x
        var y = area.y
        val role = ListenParty.role
        ui.text(I18n.text("Listen Together"), x, y, 22f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
        val sub = when (role) {
            ListenParty.Role.NONE -> I18n.text("Play the same song in sync with friends who also have this mod")
            ListenParty.Role.HOST -> if (ListenParty.memberControl == true) I18n.text("You are the host · everyone in the room can pick songs and control playback")
                else I18n.text("You are the host · everyone in the room follows your music")
            ListenParty.Role.MEMBER -> I18n.text("Following ") + ListenParty.hostName + when (ListenParty.memberControl) {
                true -> I18n.text(" · you can pick songs and control playback too")
                false -> I18n.text(" · the host turned off member control")
                null -> I18n.text(" · the host's mod is older, only the host can control playback")
            }
        }
        ui.text(sub, x, y + 30f, 11f, Theme.TEXT_SECONDARY, Theme.FONT_SEMIBOLD)
        y += 66f

        // 不在房间里：看看当前服务器（局域网 / 内网穿透联机时是房主的游戏）能不能直接一起听
        if (role == ListenParty.Role.NONE) ListenParty.probeServer()
        val viaServer = if (role == ListenParty.Role.NONE) ServerTransport.connected else ListenParty.transport === ServerTransport
        if (viaServer) {
            ui.text(if (role == ListenParty.Role.NONE) I18n.text("This server supports Listen Together directly. No Firebase or proxy needed.")
                else I18n.text("Syncing through the current server"), x, y, 12f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            y += 28f
        } else if (!Firebase.configured) {
            // 没配置好也照常显示下面的按钮和连接方式（点了会在下面提示原因），只在这里先说一声
            ui.text(I18n.text("Listen Together is not set up (missing firebase.properties)"), x, y, 12f, RED, Theme.FONT_MEDIUM)
            y += 28f
        }

        if (role == ListenParty.Role.NONE) {
            ui.pill(LayoutBounds(x, y, 190f, 38f), RED, if (ListenParty.busy) I18n.text("Working...") else I18n.text("Create a room"), "♬") { ListenParty.create() }
            y += 62f
            ui.text(I18n.text("OR JOIN WITH A ROOM CODE"), x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            codeRect = LayoutBounds(x, y, 190f, 38f)
            ui.card(codeRect, if (codeFocused) Theme.CARD_HOVER else Theme.CARD, 12f)
            if (codeFocused) ctx.drawShape(codeRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 12f, 1f, RED))
            val caret = if (codeFocused && caretVisible()) "|" else ""
            if (codeInput.isEmpty() && !codeFocused) {
                ui.text(I18n.text("ROOM CODE"), codeRect.x + 14f, codeRect.y + 12f, 14f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            } else {
                ui.text(codeInput + caret, codeRect.x + 14f, codeRect.y + 10f, 18f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            }
            ui.hits += codeRect to {
                codeFocused = true
                screen.sidebar.searchFocused = false
                screen.tracks.filterFocused = false
            }
            ui.pill(LayoutBounds(codeRect.right + 10f, y, 90f, 38f), Color(1f, 1f, 1f, 0.12f), I18n.text("Join")) {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            y += 56f
            y = drawConnection(x, y)
        } else {
            ui.text(I18n.text("ROOM CODE"), x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 16f
            val code = ListenParty.code
            ui.text(code, x, y, 40f, Theme.TEXT_PRIMARY, Theme.FONT_DISPLAY)
            val cw = ui.width(code, 40f, Theme.FONT_DISPLAY)
            val copied = System.currentTimeMillis() - copiedAt < 1500
            ui.pill(LayoutBounds(x + cw + 18f, y + 8f, 80f, 28f), Color(1f, 1f, 1f, 0.12f), if (copied) I18n.text("Copied") else I18n.text("Copy")) {
                DangoClient.platform.clipboard = code
                copiedAt = System.currentTimeMillis()
            }
            y += 66f

            val list = ListenParty.members
            ui.text(I18n.text("IN THIS ROOM · ") + list.size, x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
            y += 18f
            for (m in list.take(10)) {
                val failed = m.failedTrack.isNotEmpty() && m.failedTrack == me.sorihiko.dango.music.MusicPlayer.current?.key
                val row = LayoutBounds(x, y, 320f, if (failed) 52f else 34f)
                ui.card(row, Theme.CARD, 10f)
                val av = LayoutBounds(row.x + 8f, row.y + 6f, 22f, 22f)
                ctx.drawShape(av, ShapeStyle(if (m.uid == ListenParty.hostUid) RED else Theme.TRACK, 11f))
                ui.icon(m.name.take(1).uppercase(), av.x + 11f, av.y + 11f, 11f, Theme.TEXT_PRIMARY)
                val label = m.name + (if (m.uid == ListenParty.transport.uid) I18n.text("  (you)") else "")
                ui.text(ui.trim(label, 200f, 12f), av.right + 10f, row.y + 11f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                if (failed) ui.text(I18n.text("Unable to play this song"), av.right + 10f, row.y + 32f, 10f, RED)
                if (m.uid == ListenParty.hostUid) {
                    val hb = LayoutBounds(row.right - 54f, row.y + 8f, 44f, 18f)
                    ctx.drawShape(hb, ShapeStyle(RED.copy(a = 0.25f), 9f))
                    ui.text(I18n.text("HOST"), hb.x + (hb.width - ui.width(I18n.text("HOST"), 9f, Theme.FONT_SEMIBOLD)) / 2f, hb.y + 5f, 9f, RED, Theme.FONT_SEMIBOLD)
                }
                y += row.height + 6f
            }
            y += 10f
            ui.pill(LayoutBounds(x, y, 140f, 34f), Color(1f, 1f, 1f, 0.12f), if (role == ListenParty.Role.HOST) I18n.text("Close room") else I18n.text("Leave room")) {
                ListenParty.leave()
            }
            if (role == ListenParty.Role.MEMBER) {
                ui.pill(LayoutBounds(x + 150f, y, 120f, 34f), Color(1f, 1f, 1f, 0.12f), I18n.text("Resync"), "↻") { ListenParty.resync() }
            }
            y += 50f
            if (role == ListenParty.Role.HOST) {
                // 允许成员切歌 / 拖进度 / 暂停（房主不用确认，自动执行）
                val on = ListenParty.memberControl == true
                val sw = LayoutBounds(x, y, 40f, 22f)
                ui.toggleSwitch(sw, on, area, "party.memberControl") { ListenParty.setMemberControl(!on) }
                ui.text(I18n.text("Let members control playback"), sw.right + 12f, y + 5f, 12f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                y += 36f
            }
        }

        val st = I18n.message(ListenParty.status)
        // 连不上时的提示比较长，最多折成两行
        ui.wrap2(st, (area.right - x).coerceAtMost(560f), 12f, Theme.FONT_MEDIUM).forEachIndexed { i, line ->
            ui.text(line, x, y + i * 16f, 12f, RED, Theme.FONT_MEDIUM)
        }
        ui.text(I18n.text("Everyone streams from their own music account. VIP and region permissions apply to each member."),
            x, area.bottom - 20f, 10f, Theme.TEXT_TERTIARY)
    }

    /**
     * 连接方式（只管一起听歌）：跟随系统代理 / 直连 / 手动。不在房间里时才能改，改了下一个请求就生效。
     * 系统代理在后台检测，这里只显示缓存的结果。
     */
    private fun drawConnection(x: Float, top: Float): Float {
        var y = top
        ui.text(I18n.text("CONNECTION"), x, y, 10f, Theme.TEXT_TERTIARY, Theme.FONT_SEMIBOLD)
        y += 18f
        val mode = PartyProxy.currentMode()
        var px = x
        val options = listOf(
            PartyProxy.Mode.SYSTEM to I18n.text("System proxy"),
            PartyProxy.Mode.DIRECT to I18n.text("Direct"),
            PartyProxy.Mode.MANUAL to I18n.text("Manual"),
        )
        for ((m, label) in options) {
            val w = ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f
            ui.pill(LayoutBounds(px, y, w, 28f), if (m == mode) RED else Color(1f, 1f, 1f, 0.12f), label) {
                proxyFocused = false
                if (m == PartyProxy.Mode.MANUAL && mode != m) proxyInput = PartyProxy.manualAddress
                PartyProxy.set(m)
            }
            px += w + 8f
        }
        if (mode == PartyProxy.Mode.SYSTEM) {
            val label = I18n.text("Re-detect")
            ui.pill(LayoutBounds(px + 4f, y, ui.width(label, 12f, Theme.FONT_SEMIBOLD) + 28f, 28f), Color(1f, 1f, 1f, 0.06f), label, "↻") {
                PartyProxy.refreshAsync()
            }
        }
        y += 38f

        var note = ""
        var warn = false
        when (mode) {
            PartyProxy.Mode.DIRECT -> note = I18n.text("Connecting directly")
            PartyProxy.Mode.SYSTEM -> {
                val info = PartyProxy.systemInfo()
                val route = info?.route
                when {
                    info == null -> note = I18n.text("Detecting system proxy...")
                    PartyProxy.fallbackActive && route != null -> {
                        note = I18n.text("System proxy unreachable, connecting directly for now: ") + route.label
                        warn = true
                    }
                    route != null -> note = I18n.text("Using system proxy ") + route.label +
                        if (info.pac) I18n.text(" (PAC script ignored)") else ""
                    info.pac -> {
                        note = I18n.text("System uses a PAC script, which is not supported. Connecting directly; choose Manual to enter the proxy address.")
                        warn = true
                    }
                    info.error != null -> {
                        note = I18n.text("Could not read system proxy, connecting directly: ") + info.error
                        warn = true
                    }
                    else -> note = I18n.text("No system proxy found, connecting directly")
                }
            }
            PartyProxy.Mode.MANUAL -> {
                proxyRect = LayoutBounds(x, y, 260f, 32f)
                ui.card(proxyRect, if (proxyFocused) Theme.CARD_HOVER else Theme.CARD, 10f)
                if (proxyFocused) ui.ctx.drawShape(proxyRect, ShapeStyle(Color(0f, 0f, 0f, 0f), 10f, 1f, RED))
                val shown = if (proxyFocused) proxyInput else PartyProxy.manualAddress
                if (shown.isEmpty() && !proxyFocused) {
                    ui.text(I18n.text("Proxy address, e.g. 127.0.0.1:7890"), proxyRect.x + 12f, proxyRect.y + 10f, 12f, Theme.TEXT_TERTIARY, Theme.FONT_MEDIUM)
                } else {
                    val caret = if (proxyFocused && caretVisible()) "|" else ""
                    ui.text(ui.trim(shown, 230f, 13f) + caret, proxyRect.x + 12f, proxyRect.y + 9f, 13f, Theme.TEXT_PRIMARY, Theme.FONT_MEDIUM)
                }
                ui.hits += proxyRect to {
                    if (!proxyFocused) proxyInput = PartyProxy.manualAddress
                    proxyFocused = true
                    codeFocused = false
                    screen.sidebar.searchFocused = false
                    screen.tracks.filterFocused = false
                }
                y += 40f
                val addr = PartyProxy.manualAddress
                when {
                    addr.isEmpty() -> note = I18n.text("Enter the proxy address and press Enter")
                    PartyProxy.parseAddress(addr) == null -> {
                        note = I18n.text("Invalid proxy address")
                        warn = true
                    }
                    PartyProxy.manualError.isNotEmpty() -> {
                        note = I18n.text("Cannot reach proxy ") + PartyProxy.manualError
                        warn = true
                    }
                    else -> note = I18n.text("Using proxy ") + PartyProxy.parseAddress(addr)!!.label
                }
            }
        }
        ui.text(ui.trim(note, 560f, 11f), x, y, 11f, if (warn) RED else Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
        y += 16f
        // 配了中转时说明现在经哪条线路连服务器
        val server = when (ListenParty.transport.route) {
            PartyTransport.Route.DIRECT -> ""
            PartyTransport.Route.RELAY -> I18n.text("Server: via relay")
            PartyTransport.Route.RELAY_DOWN -> I18n.text("Server: relay unavailable, connecting to Firebase directly for now")
        }
        if (server.isNotEmpty()) {
            ui.text(ui.trim(server, 560f, 11f), x, y, 11f, Theme.TEXT_SECONDARY, Theme.FONT_MEDIUM)
            y += 16f
        }
        ui.text(I18n.text("Only used for Listen Together. Music sources are not affected."), x, y, 10f, Theme.TEXT_TERTIARY)
        return y + 24f
    }

    /** 把输入框里的代理地址存下来，马上生效 */
    fun commitProxy() {
        if (!proxyFocused) return
        proxyFocused = false
        PartyProxy.set(PartyProxy.Mode.MANUAL, proxyInput)
    }

    /** 房间码 / 代理地址输入框有焦点时的按键；返回 true 表示按键被吃掉了 */
    fun keyTyped(typedChar: Char, keyCode: Int): Boolean {
        if (proxyFocused) {
            when {
                keyCode == Keys.ESCAPE -> proxyFocused = false
                keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> commitProxy()
                keyCode == Keys.BACK -> if (proxyInput.isNotEmpty()) proxyInput = proxyInput.dropLast(1)
                DangoClient.platform.ctrlDown && keyCode == Keys.V ->
                    proxyInput = DangoClient.platform.clipboard.trim().filter { it.code in 33..126 }.take(200)
                typedChar.code in 33..126 -> proxyInput = (proxyInput + typedChar).take(200)
            }
            return true
        }
        if (!codeFocused) return false
        when {
            keyCode == Keys.ESCAPE -> codeFocused = false
            keyCode == Keys.RETURN || keyCode == Keys.NUMPADENTER -> {
                codeFocused = false
                ListenParty.join(codeInput)
            }
            keyCode == Keys.BACK -> if (codeInput.isNotEmpty()) codeInput = codeInput.dropLast(1)
            DangoClient.platform.ctrlDown && keyCode == Keys.V ->
                codeInput = DangoClient.platform.clipboard.filter { it.isLetterOrDigit() || it == '-' }.uppercase().take(8)
            (typedChar.isLetterOrDigit() || typedChar == '-') && typedChar.code < 128 -> codeInput = (codeInput + typedChar.uppercaseChar()).take(8)
        }
        return true
    }
}

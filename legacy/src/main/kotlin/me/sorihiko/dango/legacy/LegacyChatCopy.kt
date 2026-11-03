package me.sorihiko.dango.legacy

import net.minecraft.event.ClickEvent
import net.minecraft.event.HoverEvent
import net.minecraft.util.ChatComponentText
import net.minecraft.util.ChatStyle
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.IChatComponent
import java.util.IdentityHashMap

/** 1.8.9 没有 COPY_TO_CLIPBOARD；只由本地鼠标事件处理自己创建的链接。 */
internal object LegacyChatCopy {
    private val copies = IdentityHashMap<ClickEvent, String>()

    fun message(text: String, copyText: String, copyHint: String): IChatComponent {
        val at = text.indexOf(copyText)
        if (copyText.isEmpty() || at < 0) return ChatComponentText(text)
        val click = ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, copyText)
        copies[click] = copyText
        val style = ChatStyle().setColor(EnumChatFormatting.AQUA).setUnderlined(true).setChatClickEvent(click)
        if (copyHint.isNotEmpty()) style.setChatHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, ChatComponentText(copyHint)))
        return ChatComponentText(text.substring(0, at))
            .appendSibling(ChatComponentText(copyText).setChatStyle(style))
            .appendSibling(ChatComponentText(text.substring(at + copyText.length)))
    }

    // 按事件对象身份匹配，服务器发来的同名 SUGGEST_COMMAND 不会被接管。
    fun copyValue(event: ClickEvent?): String? = copies[event]
}

package me.sorihiko.dango.font

import me.sorihiko.dango.service.Service
class FontManager : Service {
    val fonts = mutableListOf<UiFont>()

    override fun load() {
        fonts.forEach {
            it.load()
        }
    }

    override fun unload() {

    }

    fun register(item: String) {
        fonts.add(UiFont(item, 64f))
    }

    fun get(font: String): UiFont {
        return fonts.find { it.path == font } ?: fonts.firstOrNull() ?: error("Could not find font: $font")
    }
}


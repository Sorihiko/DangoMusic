package me.sorihiko.dango.font

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

class UiFont(
    val path: String,
    val fontSize: Float,
) {
    data class Glyph(
        val u0: Float, val v0: Float,
        val u1: Float, val v1: Float,
        val width: Float,
        val height: Float,
        val bearingX: Float,
        val bearingY: Float,
        val advance: Float
    )

    var textureId: Int = 0
        private set
    private val glyphMap = mutableMapOf<Char, Glyph>()

    fun getGlyph(char: Char): Glyph? {
        return glyphMap[char]
    }

    fun getTextWidth(text: String, size: Float): Float {
        val scale = size / this.fontSize
        var width = 0f

        for (c in text) {
            val glyph = glyphMap[c] ?: continue
            width += glyph.advance * scale
        }

        return width
    }

    fun load() {
        glyphMap.clear()

        val font = this.javaClass.getResourceAsStream("/assets/dangomusic/fonts/$path")?.use {
            Font.createFont(
                Font.TRUETYPE_FONT,
                it
            ).deriveFont(this.fontSize)
        } ?: error("Could not load font $path")

        val padding = 4 // 生成 mipmap 后避免相邻字形串色
        val characters = (32..126).map { it.toChar() }.joinToString("")

        val dummyImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val dummyG = dummyImg.createGraphics()
        dummyG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val frc = dummyG.fontRenderContext
        dummyG.dispose()

        data class CharInfo(
            val char: Char,
            val w: Int,
            val h: Int,
            val advance: Float,
            val boundsX: Float,
            val boundsY: Float
        )

        val infos = characters.map { ch ->
            val gv = font.createGlyphVector(frc, charArrayOf(ch))
            val vb = gv.visualBounds
            val adv = gv.getGlyphMetrics(0).advanceX

            val left = floor(vb.x).toInt()
            val top = floor(vb.y).toInt()
            val right = ceil(vb.x + vb.width).toInt()
            val bottom = ceil(vb.y + vb.height).toInt()
            val w = (right - left).coerceAtLeast(1)
            val h = (bottom - top).coerceAtLeast(1)

            CharInfo(
                ch, w, h, adv,
                left.toFloat(),
                top.toFloat()
            )
        }

        val totalArea = infos.sumOf { ((it.w + padding) * (it.h + padding)).toLong() } * 2 // * 2 预留空间
        var atlasW = 256
        while (atlasW * atlasW < totalArea) atlasW *= 2

        val atlasImage = BufferedImage(atlasW, atlasW, BufferedImage.TYPE_BYTE_GRAY)
        val g = atlasImage.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.font = font
        g.color = Color.WHITE

        var curX = padding
        var curY = padding
        var maxRowHeight = 0

        infos.forEach { ci ->
            if (curX + ci.w + padding > atlasW) {
                curX = padding
                curY += maxRowHeight + padding
                maxRowHeight = 0
            }

            if (curY + ci.h > atlasW) error("Font atlas overflow: $path")

            val drawX = curX - ci.boundsX
            val drawY = curY - ci.boundsY
            g.drawString(ci.char.toString(), drawX, drawY)

            glyphMap[ci.char] = Glyph(
                u0 = curX.toFloat() / atlasW,
                v0 = curY.toFloat() / atlasW,
                u1 = (curX + ci.w).toFloat() / atlasW,
                v1 = (curY + ci.h).toFloat() / atlasW,
                width = ci.w.toFloat(),
                height = ci.h.toFloat(),
                bearingX = ci.boundsX,
                bearingY = -ci.boundsY,
                advance = ci.advance
            )

            curX += ci.w + padding
            maxRowHeight = maxOf(maxRowHeight, ci.h)
        }
        g.dispose()

        val pixels = (atlasImage.raster.dataBuffer as DataBufferByte).data
        val buffer = BufferUtils.createByteBuffer(pixels.size)
        buffer.put(pixels)
        buffer.flip()

        if (this.textureId != 0) {
            GL11.glDeleteTextures(this.textureId)
        }

        this.textureId = genAtlasTexture(atlasW, buffer)
    }

    private fun genAtlasTexture(atlasW: Int, data: ByteBuffer): Int {
        val id = GL11.glGenTextures()
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RED,
            atlasW,
            atlasW,
            0,
            GL11.GL_RED,
            GL11.GL_UNSIGNED_BYTE,
            data
        )
        // 图集是 64px，UI 常以 11~16px 绘制，用 mipmap 避免缩小时的锯齿和闪烁
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        return id
    }
}


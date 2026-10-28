package me.sorihiko.dango.music

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** 解码前检查尺寸，防止小文件解压为超大图片。 */
internal object CoverImage {
    fun decode(bytes: ByteArray): BufferedImage? {
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 16_777_216L) return null
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }
}

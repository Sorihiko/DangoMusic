package me.sorihiko.dango.legacy

import me.sorihiko.dango.gfx.Texture
import me.sorihiko.dango.gfx.Textures

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation

import java.awt.image.BufferedImage

/** 1.12.2 的贴图：交给原版 TextureManager 管理 */
class LegacyTexture(val location: ResourceLocation) : Texture {
    fun bind() {
        GlStateManager.bindTexture(0)
        mc.textureManager.bindTexture(location)
    }
}

object LegacyTextures : Textures {
    override fun upload(name: String, image: BufferedImage): Texture =
        LegacyTexture(mc.textureManager.getDynamicTextureLocation(name, DynamicTexture(image)))

    override fun delete(texture: Texture) {
        mc.textureManager.deleteTexture((texture as LegacyTexture).location)
    }
}



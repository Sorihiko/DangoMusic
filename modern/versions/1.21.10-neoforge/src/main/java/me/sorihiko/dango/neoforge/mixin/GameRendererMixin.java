package me.sorihiko.dango.neoforge.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** NeoForge 的画世界事件不给投影矩阵，只能挂进原版抄一份 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * 原版把这一帧画世界用的投影（已经乘上视角摇晃）交给显卡之前，抄一份给 3D 歌词用，
     * 不用再从显存读回来。没挂上也不影响游戏（那就退回从显存读）
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PerspectiveProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
        ),
        require = 0
    )
    private Matrix4f dangomusic$captureProjection(Matrix4f projection) {
        ModernDango.worldProjection(projection);
        return projection;
    }
}

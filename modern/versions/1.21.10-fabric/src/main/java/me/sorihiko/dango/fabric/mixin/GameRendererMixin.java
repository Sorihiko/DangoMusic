package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版画完整个世界（含天气、云）之后、画手之前，调用 ModernDango.worldRenderEnd()。
 * 和 NeoForge 的 RenderLevelStageEvent.AfterLevel 是同一个位置。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void dangomusic$afterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        ModernDango.worldRenderEnd();
    }

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

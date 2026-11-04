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
 * 挂在原版画一帧的两个位置：
 * - 整个世界（含天气、云）画完、画手之前：ModernDango.worldRenderEnd()（和 NeoForge 的 RenderLevelStageEvent.AfterLevel 同一个位置）；
 * - 开始画界面之前：ModernDango.beforeGui()（26.x 的界面是先收集再画，我们的界面要在这时画好）。
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void dangomusic$afterLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        ModernDango.worldRenderEnd();
    }

    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V")
    )
    private void dangomusic$beforeGui(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ModernDango.beforeGui();
    }

    /**
     * 原版把这一帧画世界用的投影（已经乘上视角摇晃）交给显卡之前，抄一份给 3D 歌词用，
     * 不用再从显存读回来。没挂上也不影响游戏（那就退回从显存读）
     */
    @ModifyArg(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
        ),
        require = 0
    )
    private Matrix4f dangomusic$captureProjection(Matrix4f projection) {
        ModernDango.worldProjection(projection);
        return projection;
    }
}

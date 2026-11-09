package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.DanceRender;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 生物跟着歌曲节奏跳（设置 → Lyrics → 跳舞）：原版把生物转到它身体朝向之后，套上 Dance 算好的姿势
 * （原点在脚下：压扁再拉长、绕竖轴转圈）。
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void dangomusic$dance(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale, CallbackInfo ci) {
        DanceRender.apply(poseStack);
    }
}

package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.ModernDango;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版画完暗角（和南瓜头、望远镜等镜头遮罩）之后：画 Song card、灵动岛、Now playing，
 * 这样暗角压不到它们，快捷栏照常盖在上面。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "renderCameraOverlays", at = @At("TAIL"))
    private void dangomusic$overlay(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ModernDango.hudOverlay(graphics);
    }
}

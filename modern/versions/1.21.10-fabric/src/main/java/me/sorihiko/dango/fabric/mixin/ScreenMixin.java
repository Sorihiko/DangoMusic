package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.modern.ModernDango;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("TAIL"))
    private void dangomusic$mainMenuNowPlaying(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ModernDango.mainMenuRender(graphics);
    }
}
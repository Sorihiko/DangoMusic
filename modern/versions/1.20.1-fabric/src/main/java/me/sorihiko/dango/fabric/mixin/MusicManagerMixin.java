package me.sorihiko.dango.fabric.mixin;

import me.sorihiko.dango.platform.VanillaMusicHold;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版音乐让路：放歌时整个跳过原版音乐管理器的这一 tick，
 * 它不会开始新的一首，等下一首的倒计时也停住；放完歌后从原来的进度接着走。
 * 正在放的那首在刚开始让路时已经停掉了（ModernPlatform.holdVanillaMusic）。
 */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dangomusic$holdForSong(CallbackInfo ci) {
        if (VanillaMusicHold.active) ci.cancel();
    }
}

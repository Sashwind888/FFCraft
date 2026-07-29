package sashwind.mc.mod.ffcraft.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void onGameRendererClose(CallbackInfo ci) {
        sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager.stopAll();
    }
}
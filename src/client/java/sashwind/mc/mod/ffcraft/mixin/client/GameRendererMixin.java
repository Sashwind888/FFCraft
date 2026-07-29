package sashwind.mc.mod.ffcraft.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sashwind.mc.mod.ffcraft.client.FFCraftClient;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlaybackManager;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void onGameRendererClose(CallbackInfo ci) {
        // 渲染器关闭时停止所有本地播放，释放 GPU 资源
        ClientVideoPlaybackManager.stopAll();
    }
}
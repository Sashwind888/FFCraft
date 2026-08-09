package sashwind.mc.mod.ffcraft.mixin.client;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sashwind.mc.mod.ffcraft.client.FFCraftClient;

/**
 * 在标题画面初始化时登记 libmpv 安装检查。
 *
 * 注意：不能在这里直接弹安装界面。TitleScreen.init 发生在 Minecraft.<init> 构造早期，
 * 此时 setScreenAndShow 会立即渲染一帧，dynamic_fps 等 mod 的 mixin 访问尚未初始化的
 * 字段（如 framerateLimitTracker）会 NPE。实际弹屏推迟到第一 tick（构造完成后）。
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        FFCraftClient.requestMpvInstallCheck();
    }
}

package sashwind.mc.mod.ffcraft.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sashwind.mc.mod.drawlib.client.lib;
import sashwind.mc.mod.ffcraft.client.player.MpvNativeLoader;
import sashwind.mc.mod.ffcraft.client.player.MpvPlayer;
import sashwind.mc.mod.ffcraft.client.screens.MpvInstallScreen;

/**
 * 在标题画面初始化时检查 libmpv，未安装则弹出安装界面。
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        // 只弹一次
        if (MpvInstallScreen.hasShown()) return;
        // 已安装则跳过
        if (MpvPlayer.isAvailable()) {
            MpvInstallScreen.markShown();
            return;
        }
        // 不支持自动下载的平台也跳过
        MpvNativeLoader.State state = MpvNativeLoader.getState();
        if (state == MpvNativeLoader.State.UNSUPPORTED) {
            MpvInstallScreen.markShown();
            return;
        }

        MpvInstallScreen.markShown();
        lib.setScreenCompat(Minecraft.getInstance(), new MpvInstallScreen());
    }
}

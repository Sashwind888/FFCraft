package sashwind.mc.mod.ffcraft.mixin.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditBox.class)
public abstract class EditBoxMixin {

    @Shadow
    private String value;

    @Shadow
    private int displayPos;

    @Shadow
    private Font font;

    @Inject(method = "updateTextPosition", at = @At("HEAD"), cancellable = true)
    private void onUpdateTextPosition(CallbackInfo ci) {
        if (this.font == null) {
            ci.cancel();
            return;
        }
        if (this.value == null) {
            this.value = "";
        }
        int len = this.value.length();
        if (this.displayPos < 0 || this.displayPos > len) {
            this.displayPos = Mth.clamp(this.displayPos, 0, len);
        }
    }
}

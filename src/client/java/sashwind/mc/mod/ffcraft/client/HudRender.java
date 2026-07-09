package sashwind.mc.mod.ffcraft.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import sashwind.mc.mod.ffcraft.client.player.Player;

public class HudRender {
    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        Player.HUDrender(graphics, tickCounter);
    }
}

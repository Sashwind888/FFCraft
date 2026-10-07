package sashwind.mc.mod.ffcraft.compat.gui.v26_1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import sashwind.mc.mod.ffcraft.compat.gui.GuiCompat;

/**
 * Minecraft 26.1 GUI 兼容实现。
 * 26.1 原生调用 client.setScreen(Screen)。
 */
public class GuiCompat26_1 implements GuiCompat {

    @Override
    public void setScreen(Minecraft client, Screen screen) {
        if (client != null) {
            client.setScreen(screen);
        }
    }
}

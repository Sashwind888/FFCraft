package sashwind.mc.mod.ffcraft.compat.gui.v26_2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import sashwind.mc.mod.ffcraft.compat.gui.GuiCompat;

/**
 * Minecraft 26.2 GUI 兼容实现。
 * 26.2 原生调用 client.setScreenAndShow(Screen)。
 */
public class GuiCompat26_2 implements GuiCompat {

    @Override
    public void setScreen(Minecraft client, Screen screen) {
        if (client != null) {
            client.setScreenAndShow(screen);
        }
    }
}

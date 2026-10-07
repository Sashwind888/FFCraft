package sashwind.mc.mod.ffcraft.compat.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 跨版本 Minecraft 客户端 GUI/Screen 兼容中层。
 * 统一屏蔽各游戏版本在窗口上打开、切换、关闭界面的 API 差异（如 26.1 setScreen 与 26.2 setScreenAndShow）。
 */
public interface GuiCompat {

    /**
     * 打开或关闭客户端界面
     *
     * @param client Minecraft 客户端实例
     * @param screen 目标界面，为 null 时表示关闭界面
     */
    void setScreen(Minecraft client, Screen screen);

    /**
     * 静态便捷方法：在窗口中打开或关闭界面
     *
     * @param client Minecraft 客户端实例
     * @param screen 目标界面，为 null 时表示关闭界面
     */
    static void openScreen(Minecraft client, Screen screen) {
        Holder.INSTANCE.setScreen(client, screen);
    }

    class Holder {
        private static final GuiCompat INSTANCE = load();

        private static GuiCompat load() {
            String[] candidateClasses = {
                "sashwind.mc.mod.ffcraft.compat.gui.v26_2.GuiCompat26_2",
                "sashwind.mc.mod.ffcraft.compat.gui.v26_1.GuiCompat26_1"
            };
            for (String clazzName : candidateClasses) {
                try {
                    Class<?> clazz = Class.forName(clazzName);
                    return (GuiCompat) clazz.getDeclaredConstructor().newInstance();
                } catch (Throwable ignored) {
                }
            }
            return new FallbackGuiCompat();
        }
    }
}

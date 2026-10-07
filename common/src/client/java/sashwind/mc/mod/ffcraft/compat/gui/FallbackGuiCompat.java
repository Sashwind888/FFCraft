package sashwind.mc.mod.ffcraft.compat.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 通用运行时反射兜底实现，保障未知微版本或开发环境正常工作。
 */
public class FallbackGuiCompat implements GuiCompat {

    private static final MethodHandle SET_SCREEN_HANDLE;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle handle = null;
        try {
            // 优先尝试 26.2+ setScreenAndShow(Screen)
            handle = lookup.findVirtual(Minecraft.class, "setScreenAndShow", MethodType.methodType(void.class, Screen.class));
        } catch (NoSuchMethodException | IllegalAccessException e1) {
            try {
                // 尝试 26.1- setScreen(Screen)
                handle = lookup.findVirtual(Minecraft.class, "setScreen", MethodType.methodType(void.class, Screen.class));
            } catch (NoSuchMethodException | IllegalAccessException e2) {
                // Ignore
            }
        }
        SET_SCREEN_HANDLE = handle;
    }

    @Override
    public void setScreen(Minecraft client, Screen screen) {
        if (client == null) return;
        if (SET_SCREEN_HANDLE != null) {
            try {
                SET_SCREEN_HANDLE.invokeExact(client, screen);
                return;
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        try {
            client.setScreenAndShow(screen);
        } catch (Throwable ignored) {
        }
    }
}

package sashwind.mc.mod.ffcraft.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

public final class VideoPlayerServerLifecycle {
    private static long tickCounter = 0;

    private VideoPlayerServerLifecycle() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(VideoPlayerServerRuntime::init);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                VideoPlayerServerRuntime.getService().stopAllPlayback();
                VideoPlayerServerNetworking.syncAll();
            } catch (Exception ignored) {}
            VideoPlayerServerRuntime.clear();
        });

        ServerTickEvents.END_SERVER_TICK.register(VideoPlayerServerLifecycle::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 20 == 0) {
            try {
                VideoPlayerServerRuntime.getService().tickProgress();
            } catch (IllegalStateException ignored) {}
        }
        if (tickCounter % 100 == 0) VideoPlayerServerNetworking.syncAll();
    }
}

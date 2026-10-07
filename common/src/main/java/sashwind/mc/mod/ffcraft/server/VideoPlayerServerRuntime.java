package sashwind.mc.mod.ffcraft.server;

import net.minecraft.server.MinecraftServer;

public final class VideoPlayerServerRuntime {
    private static MinecraftServer server;
    private static VideoPlayerService service;

    private VideoPlayerServerRuntime() {
    }

    public static void init(MinecraftServer minecraftServer) {
        server = minecraftServer;
        service = new VideoPlayerService(minecraftServer);
    }

    public static MinecraftServer getServer() {
        if (server == null) {
            throw new IllegalStateException("MinecraftServer 尚未初始化");
        }
        return server;
    }

    public static VideoPlayerService getService() {
        if (service == null) {
            throw new IllegalStateException("VideoPlayerService 尚未初始化");
        }
        return service;
    }

    public static void clear() {
        server = null;
        service = null;
    }
}

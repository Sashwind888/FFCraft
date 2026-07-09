package sashwind.mc.mod.ffcraft.server;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class VideoPlayerMessages {
    private VideoPlayerMessages() {
    }

    public static void error(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[FFCraft] " + message));
    }
}

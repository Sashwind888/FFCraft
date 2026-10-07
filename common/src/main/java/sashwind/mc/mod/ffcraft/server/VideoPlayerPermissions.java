package sashwind.mc.mod.ffcraft.server;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import sashwind.mc.mod.ffcraft.server.state.ServerVideoPlayer;

public final class VideoPlayerPermissions {
    private VideoPlayerPermissions() {
    }

    public static boolean canAdmin(ServerPlayer player) {
        return player.level().getServer() != null
                && player.level().getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()));
    }

    public static boolean canEdit(ServerPlayer player, ServerVideoPlayer videoPlayer) {
        return videoPlayer.isPublic() || canAdmin(player) || videoPlayer.editors().contains(player.getUUID());
    }
}

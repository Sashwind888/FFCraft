package sashwind.mc.mod.ffcraft.server.state;

import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerSnapshot;
import sashwind.mc.mod.ffcraft.common.model.VideoScreenData;
import sashwind.mc.mod.ffcraft.common.model.VideoSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class VideoPlayerMapper {
    private VideoPlayerMapper() {
    }

    public static VideoPlayerSnapshot toSnapshot(List<ServerVideoPlayer> players) {
        return new VideoPlayerSnapshot(players.stream().map(VideoPlayerMapper::toData).toList());
    }

    public static VideoPlayerData toData(ServerVideoPlayer player) {
        return new VideoPlayerData(
                player.id(),
                player.name(),
                player.isPublic(),
                new LinkedHashSet<>(player.editors()),
                new ArrayList<>(player.playlist()),
                copyPlayback(player.playbackState()),
                player.screens().stream().map(VideoPlayerMapper::toData).toList()
        );
    }

    public static VideoScreenData toData(ServerVideoScreen screen) {
        return new VideoScreenData(
                screen.id(),
                screen.playerId(),
                screen.name(),
                screen.dimension(),
                new ArrayList<>(screen.vertices()),
                copyUv(screen.uvTransform()),
                copyChannel(screen.channelState()),
                screen.uvManuallyEdited()
        );
    }

    private static PlaybackState copyPlayback(PlaybackState state) {
        return new PlaybackState(state.status(), state.mode(), state.currentIndex(), state.progressSeconds(), state.volume(), state.lastUpdatedEpochSeconds());
    }

    private static UvTransform copyUv(UvTransform uv) {
        return new UvTransform(uv.offsetU(), uv.offsetV(), uv.scaleU(), uv.scaleV(), uv.rotationDegrees(), uv.flipU(), uv.flipV());
    }

    private static ScreenChannelState copyChannel(ScreenChannelState state) {
        return new ScreenChannelState(state.leftEnabled(), state.rightEnabled());
    }

    public static ServerVideoPlayer createEmptyPlayer(UUID id, String name, boolean isPublic) {
        return new ServerVideoPlayer(id, name, isPublic, new LinkedHashSet<>(), new ArrayList<VideoSource>(), PlaybackState.createDefault(), new ArrayList<>());
    }

    public static ServerVideoScreen createScreen(UUID id, UUID playerId, String name, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, List<ScreenVertex> vertices) {
        UvTransform uv = calculateInitialUvTransform(vertices);
        return new ServerVideoScreen(id, playerId, name, dimension, new ArrayList<>(vertices), uv, ScreenChannelState.createDefault(), false);
    }

    private static UvTransform calculateInitialUvTransform(List<ScreenVertex> vertices) {
        // Default to 16:9, will be recalculated when actual video resolution is known
        return calculateUvTransform(vertices, 16.0 / 9.0, false, true);
    }

    public static UvTransform calculateUvTransform(List<ScreenVertex> vertices, double videoAspect,
                                                    boolean flipU, boolean flipV) {
        if (vertices.size() < 3) {
            return new UvTransform(0, 0, 1, 1, 0, flipU, flipV);
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (ScreenVertex v : vertices) {
            minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
            minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
            minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
        }

        double sizeX = maxX - minX;
        double sizeY = maxY - minY;
        double sizeZ = maxZ - minZ;

        double screenWidth, screenHeight;
        if (sizeY < 0.01) {
            // 水平面（地板/天花板）：用XZ两个维度
            screenWidth = Math.max(sizeX, sizeZ);
            screenHeight = Math.min(sizeX, sizeZ);
        } else {
            screenWidth = Math.max(sizeX, sizeZ);
            screenHeight = sizeY;
        }

        // 防止零尺寸导致 Infinity/NaN
        if (screenWidth < 0.01 || screenHeight < 0.01) {
            return new UvTransform(0, 0, 1, 1, 0, flipU, flipV);
        }

        double screenAspect = screenWidth / screenHeight;

        double scaleU, scaleV;

        // scale >= 1 扩展UV到0-1之外实现信箱效果，offset=0保持居中
        if (screenAspect > videoAspect) {
            scaleU = screenAspect / videoAspect;
            scaleV = 1.0;
        } else {
            scaleU = 1.0;
            scaleV = videoAspect / screenAspect;
        }

        double offsetU = 0.0;
        double offsetV = 0.0;

        System.out.println("[UV Init] Screen aspect=" + screenAspect + ", video aspect=" + videoAspect +
                ", scaleU=" + scaleU + ", scaleV=" + scaleV + ", offsetU=" + offsetU + ", offsetV=" + offsetV);

        return new UvTransform(offsetU, offsetV, scaleU, scaleV, 0, flipU, flipV);
    }
}

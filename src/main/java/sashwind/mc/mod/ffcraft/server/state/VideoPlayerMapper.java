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

        // 屏幕宽高比必须在 UV 坐标系下测量（u 轴 = 首条边，与渲染端 Plane 一致）。
        // 旧实现用世界坐标猜方向（sizeX/sizeZ 为宽、sizeY 为高）——玩家从竖直边
        // 开始点选时 u 轴是竖直的，letterbox 比例加错轴 → 视频被纵向拉伸。
        double[] span = uvSpan(vertices);
        if (span == null) {
            return new UvTransform(0, 0, 1, 1, 0, flipU, flipV);
        }

        double screenAspect = span[0] / span[1];

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

    /**
     * 屏幕顶点在 UV 方向上的跨距 {uSpan, vSpan}：u 轴 = 首条非退化边方向
     * （与渲染端 Plane.D3D22D 的 UV 基一致），v = 法线 × u。
     * 退化（边长为 0 / 顶点共线）返回 null。
     */
    private static double[] uvSpan(List<ScreenVertex> verts) {
        int n = verts.size();
        if (n < 3) return null;
        ScreenVertex p0 = null;
        double ux = 0, uy = 0, uz = 0;
        double eLen = 0;
        for (int i = 0; i < n; i++) {
            ScreenVertex a = verts.get(i), b = verts.get((i + 1) % n);
            double ex = b.x() - a.x(), ey = b.y() - a.y(), ez = b.z() - a.z();
            eLen = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (eLen > 1e-4) {
                p0 = a;
                ux = ex / eLen; uy = ey / eLen; uz = ez / eLen;
                break;
            }
        }
        if (p0 == null) return null;
        // 法线：在其余顶点中找一个与首边不平行的点
        double nx = 0, ny = 0, nz = 0;
        double nLen = 0;
        for (ScreenVertex v : verts) {
            double ax = v.x() - p0.x(), ay = v.y() - p0.y(), az = v.z() - p0.z();
            double cx = ay * uz - az * uy, cy = az * ux - ax * uz, cz = ax * uy - ay * ux;
            nLen = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (nLen > 1e-4) { nx = cx / nLen; ny = cy / nLen; nz = cz / nLen; break; }
        }
        if (nLen < 1e-4) return null;
        // v = 法线 × u（与渲染端一致的正交基）
        double vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (ScreenVertex v : verts) {
            double du = (v.x() - p0.x()) * ux + (v.y() - p0.y()) * uy + (v.z() - p0.z()) * uz;
            double dv = (v.x() - p0.x()) * vx + (v.y() - p0.y()) * vy + (v.z() - p0.z()) * vz;
            minU = Math.min(minU, du); maxU = Math.max(maxU, du);
            minV = Math.min(minV, dv); maxV = Math.max(maxV, dv);
        }
        double uSpan = maxU - minU, vSpan = maxV - minV;
        if (uSpan < 1e-4 || vSpan < 1e-4) return null;
        return new double[]{uSpan, vSpan};
    }
}

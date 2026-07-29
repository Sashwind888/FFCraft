package sashwind.mc.mod.ffcraft.client.state;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;
import sashwind.mc.mod.drawlib.client.TopologyCompat;
import sashwind.mc.mod.drawlib.client.WorldDraw;
import sashwind.mc.mod.ffcraft.client.player.Screen;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerSnapshot;
import sashwind.mc.mod.ffcraft.common.model.VideoScreenData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientScreenRenderManager {
    // Screen UUID → Screen object (stores vertices only)
    private static final Map<UUID, Screen> ACTIVE_SCREENS = new ConcurrentHashMap<>();
    // Player UUID → Shared WorldDraw (one per player)
    private static final Map<UUID, WorldDraw> PLAYER_WORLDDRAWS = new ConcurrentHashMap<>();
    private static final Map<UUID, UvTransform> LAST_UV_TRANSFORMS = new ConcurrentHashMap<>();
    private static long appliedSnapshotVersion = Long.MIN_VALUE;
    private ClientScreenRenderManager() {}

    public static void syncFromSnapshotIfNeeded() {
        long currentVersion = ClientVideoPlayerCache.getVersion();
        if (currentVersion == appliedSnapshotVersion) return;
        appliedSnapshotVersion = currentVersion;
        rebuild(ClientVideoPlayerCache.getSnapshot());
    }

    public static Screen getScreen(UUID screenId) {
        return ACTIVE_SCREENS.get(screenId);
    }

    public static WorldDraw getPlayerWorldDraw(UUID playerId) {
        return PLAYER_WORLDDRAWS.get(playerId);
    }

    public static void clearAll() {
        for (Screen screen : ACTIVE_SCREENS.values()) screen.close();
        ACTIVE_SCREENS.clear();
        for (WorldDraw wd : PLAYER_WORLDDRAWS.values()) wd.close();
        PLAYER_WORLDDRAWS.clear();
        LAST_UV_TRANSFORMS.clear();
    }

    private static void rebuild(VideoPlayerSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) { clearAll(); return; }
        ResourceKey<Level> currentDim = minecraft.level.dimension();
        Set<UUID> aliveScreenIds = new HashSet<>();
        Set<UUID> alivePlayerIds = new HashSet<>();
        Set<UUID> dirtyPlayers = new HashSet<>();

        for (VideoPlayerData player : snapshot.players()) {
            UUID pid = player.id();
            alivePlayerIds.add(pid);
            for (VideoScreenData sd : player.screens()) {
                if (!sd.dimension().equals(currentDim)) continue;
                aliveScreenIds.add(sd.id());

                UvTransform newUv = sd.uvTransform();
                UvTransform lastUv = LAST_UV_TRANSFORMS.get(sd.id());

                if (!ACTIVE_SCREENS.containsKey(sd.id())) {
                    Screen screen = createScreen(sd);
                    if (screen != null) {
                        ACTIVE_SCREENS.put(sd.id(), screen);
                        LAST_UV_TRANSFORMS.put(sd.id(), newUv);
                        dirtyPlayers.add(pid);
                    }
                } else if (!uvTransformsEqual(newUv, lastUv)) {
                    Screen existing = ACTIVE_SCREENS.remove(sd.id());
                    if (existing != null) existing.close();
                    Screen screen = createScreen(sd);
                    if (screen != null) {
                        ACTIVE_SCREENS.put(sd.id(), screen);
                        LAST_UV_TRANSFORMS.put(sd.id(), newUv);
                        dirtyPlayers.add(pid);
                    }
                }
            }
        }

        // Remove stale screens (not in current snapshot)
        List<UUID> staleScreens = new ArrayList<>();
        for (UUID id : ACTIVE_SCREENS.keySet()) {
            if (!aliveScreenIds.contains(id)) staleScreens.add(id);
        }
        for (UUID id : staleScreens) {
            Screen screen = ACTIVE_SCREENS.remove(id);
            if (screen != null) screen.close();
            LAST_UV_TRANSFORMS.remove(id);
        }

        // Remove stale players (players deleted entirely from snapshot)
        List<UUID> stalePlayers = new ArrayList<>();
        for (UUID pid : PLAYER_WORLDDRAWS.keySet()) {
            if (!alivePlayerIds.contains(pid)) stalePlayers.add(pid);
        }
        for (UUID pid : stalePlayers) {
            WorldDraw wd = PLAYER_WORLDDRAWS.remove(pid);
            if (wd != null) wd.close();
        }

        // 重建受影响的 WorldDraw
        // 有删除时必须全部重建（已删除屏幕不在 snapshot 中，无法反查 owner）
        Set<UUID> toRebuild = new HashSet<>(dirtyPlayers);
        if (!staleScreens.isEmpty() || !stalePlayers.isEmpty()) {
            for (VideoPlayerData player : snapshot.players()) {
                toRebuild.add(player.id());
            }
        }
        for (UUID pid : toRebuild) {
            syncPlayerWorldDraw(pid, snapshot);
        }
    }

    private static NativeImage cachedPlaceholder;
    private static void loadPlaceholder() {
        if (cachedPlaceholder != null) return;
        try {
            var mc = Minecraft.getInstance();
            if (mc == null) return;
            Identifier id = Identifier.fromNamespaceAndPath("ffcraft", "ffcraft.png");
            TextureContents tc = TextureContents.load(mc.getResourceManager(), id);
            NativeImage img = tc.image();
            cachedPlaceholder = new NativeImage(NativeImage.Format.RGBA, img.getWidth(), img.getHeight(), false);
            MemoryUtil.memByteBuffer(cachedPlaceholder.getPointer(), img.getWidth() * img.getHeight() * 4)
                    .put(MemoryUtil.memByteBuffer(img.getPointer(), img.getWidth() * img.getHeight() * 4));
            img.close();
        } catch (Exception e) {
            System.err.println("[ScreenManager] Placeholder load failed: " + e.getMessage());
        }
    }

    /** 为指定玩家创建或重建共享 WorldDraw */
    private static void syncPlayerWorldDraw(UUID pid, VideoPlayerSnapshot snapshot) {
        WorldDraw wd = PLAYER_WORLDDRAWS.get(pid);
        if (wd == null) {
            wd = new WorldDraw(0, 0, 0, TopologyCompat.TRIANGLES);
            wd.init();
            loadPlaceholder();
            if (cachedPlaceholder != null) {
                wd.setTexture(cachedPlaceholder.getWidth(), cachedPlaceholder.getHeight(), cachedPlaceholder);
            }
            PLAYER_WORLDDRAWS.put(pid, wd);
        }
        wd.clearVertices();
        for (VideoPlayerData p : snapshot.players()) {
            if (!p.id().equals(pid)) continue;
            for (VideoScreenData sd : p.screens()) {
                Screen screen = ACTIVE_SCREENS.get(sd.id());
                if (screen != null) screen.writeVertices(wd);
            }
        }
    }

    private static boolean uvTransformsEqual(UvTransform a, UvTransform b) {
        if (a == null || b == null) return false;
        return a.offsetU() == b.offsetU() && a.offsetV() == b.offsetV()
                && a.scaleU() == b.scaleU() && a.scaleV() == b.scaleV()
                && a.rotationDegrees() == b.rotationDegrees()
                && a.flipU() == b.flipU() && a.flipV() == b.flipV();
    }

    private static Screen createScreen(VideoScreenData sd) {
        List<Vector3d> verts = new ArrayList<>();
        List<Double> dists = new ArrayList<>();
        for (ScreenVertex sv : sd.vertices()) {
            verts.add(sv.toVector());
            dists.add(sv.pitch());
            dists.add(sv.yaw());
        }
        var uv = sd.uvTransform();
        try {
            Screen screen = new Screen(verts, dists,
                    uv.offsetU(), uv.offsetV(), uv.scaleU(), uv.scaleV(), uv.rotationDegrees(),
                    uv.flipU(), uv.flipV());
            screen.name = sd.name();
            return screen;
        } catch (Exception e) {
            System.err.println("[ScreenManager] Failed to create screen '" + sd.name() + "': " + e.getMessage());
            return null;
        }
    }
}

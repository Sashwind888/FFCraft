package sashwind.mc.mod.ffcraft.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.CreatePlayerRequest;
import sashwind.mc.mod.ffcraft.common.model.CreateScreenRequest;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerSnapshot;
import sashwind.mc.mod.ffcraft.common.model.VideoSource;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.server.state.ServerVideoPlayer;
import sashwind.mc.mod.ffcraft.server.state.ServerVideoScreen;
import sashwind.mc.mod.ffcraft.server.state.VideoPlayerMapper;
import sashwind.mc.mod.ffcraft.server.state.VideoPlayerSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


public class VideoPlayerService {
    private final MinecraftServer server;
    private final VideoPlayerSavedData savedData;
    public VideoPlayerService(MinecraftServer server) {
        this.server = server;
        this.savedData = VideoPlayerSavedData.get(getOverworld().getDataStorage());
    }

    public VideoPlayerSnapshot snapshot() {
        return VideoPlayerMapper.toSnapshot(savedData.players());
    }

    public List<ServerVideoPlayer> players() {
        return savedData.players();
    }

    public ServerVideoPlayer createPlayer(ServerPlayer actor, CreatePlayerRequest request) {
        requireAdmin(actor);
        String name = sanitizeName(request.name(), "Player");
        UUID id = UUID.randomUUID();
        // auto-add creator as editor so they can manage screens/playlist
        java.util.LinkedHashSet<UUID> editors = new java.util.LinkedHashSet<>();
        editors.add(actor.getUUID());
        ServerVideoPlayer videoPlayer = new ServerVideoPlayer(id, name, request.isPublic(), editors,
                java.util.List.of(), sashwind.mc.mod.ffcraft.common.model.PlaybackState.createDefault(),
                java.util.List.of());
        savedData.players().add(videoPlayer);
        savedData.setDirty();
        return videoPlayer;
    }

    public ServerVideoScreen createScreen(ServerPlayer actor, CreateScreenRequest request) {
        ServerVideoPlayer videoPlayer = findPlayer(request.playerId())
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, videoPlayer)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        validateVertices(request.vertices().size());
        String name = sanitizeName(request.name(), "Screen");
        ServerVideoScreen screen = VideoPlayerMapper.createScreen(UUID.randomUUID(), request.playerId(), name, request.dimension(), request.vertices());
        videoPlayer.screens().add(screen);
        savedData.setDirty();
        return screen;
    }

    public void deletePlayer(ServerPlayer actor, UUID playerId) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        requireAdmin(actor);
        savedData.players().remove(player);
        savedData.setDirty();
    }

    public void deleteScreen(ServerPlayer actor, UUID playerId, UUID screenId) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        player.screens().removeIf(s -> s.id().equals(screenId));
        savedData.setDirty();
    }

    public void renamePlayer(ServerPlayer actor, UUID playerId, String newName) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("权限不足");
        }
        ServerVideoPlayer renamed = new ServerVideoPlayer(
                player.id(), sanitizeName(newName, "Player"), player.isPublic(),
                player.editors(), player.playlist(), player.playbackState(), player.screens()
        );
        int idx = savedData.players().indexOf(player);
        savedData.players().set(idx, renamed);
        savedData.setDirty();
    }

    public void renameScreen(ServerPlayer actor, UUID playerId, UUID screenId, String newName) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("权限不足");
        }
        for (int i = 0; i < player.screens().size(); i++) {
            ServerVideoScreen s = player.screens().get(i);
            if (s.id().equals(screenId)) {
                ServerVideoScreen renamed = new ServerVideoScreen(
                        s.id(), s.playerId(), sanitizeName(newName, "Screen"),
                        s.dimension(), s.vertices(), s.uvTransform(), s.channelState(), s.uvManuallyEdited()
                );
                player.screens().set(i, renamed);
                savedData.setDirty();
                return;
            }
        }
    }

    public Optional<ServerVideoPlayer> findPlayer(UUID playerId) {
        return savedData.players().stream().filter(player -> player.id().equals(playerId)).findFirst();
    }

    private void requireAdmin(ServerPlayer actor) {
        if (!VideoPlayerPermissions.canAdmin(actor)) {
            throw new IllegalStateException("你没有管理权限");
        }
    }

    private void validateVertices(int count) {
        if (count < 3 || count > FFCraftConstants.MAX_SCREEN_VERTICES) {
            throw new IllegalArgumentException("屏幕顶点数量必须在 3 到 " + FFCraftConstants.MAX_SCREEN_VERTICES + " 之间");
        }
    }

    private String sanitizeName(String rawName, String fallbackPrefix) {
        String trimmed = rawName == null ? "" : rawName.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return fallbackPrefix + "-" + (savedData.players().size() + 1);
    }

    public void setPlaybackState(UUID playerId, PlaybackState next) {
        findPlayer(playerId).ifPresent(p -> {
            ServerVideoPlayer updated = new ServerVideoPlayer(
                    p.id(), p.name(), p.isPublic(), p.editors(), p.playlist(), next, p.screens()
            );
            int idx = savedData.players().indexOf(p);
            savedData.players().set(idx, updated);
            savedData.setDirty();
        });
    }

    public void stopAllPlayback() {
        for (int i = 0; i < savedData.players().size(); i++) {
            ServerVideoPlayer p = savedData.players().get(i);
            var stopped = new sashwind.mc.mod.ffcraft.common.model.PlaybackState(
                    sashwind.mc.mod.ffcraft.common.model.PlaybackStatus.STOPPED,
                    p.playbackState().mode(), p.playbackState().currentIndex(),
                    0, p.playbackState().volume(), System.currentTimeMillis() / 1000);
            savedData.players().set(i, new ServerVideoPlayer(
                    p.id(), p.name(), p.isPublic(), p.editors(),
                    p.playlist(), stopped, p.screens()));
        }
        savedData.setDirty();
    }

    public void tickProgress() {
        long now = System.currentTimeMillis() / 1000;
        boolean changed = false;
        for (int i = 0; i < savedData.players().size(); i++) {
            ServerVideoPlayer p = savedData.players().get(i);
            if (p.playbackState().status() == sashwind.mc.mod.ffcraft.common.model.PlaybackStatus.PLAYING) {
                var old = p.playbackState();
                int newProgress = old.progressSeconds() + 1;
                var next = new sashwind.mc.mod.ffcraft.common.model.PlaybackState(
                        old.status(), old.mode(), old.currentIndex(),
                        newProgress, old.volume(), now);
                ServerVideoPlayer updated = new ServerVideoPlayer(
                        p.id(), p.name(), p.isPublic(), p.editors(),
                        p.playlist(), next, p.screens());
                savedData.players().set(i, updated);
                changed = true;
                VideoPlayerServerNetworking.syncProgress(p.id(), next.status(), next.currentIndex(), next.progressSeconds());
            }
        }
        if (changed) {
            savedData.setDirty();
        }
    }

    public void setPublic(UUID playerId, boolean pub) {
        findPlayer(playerId).ifPresent(p -> {
            ServerVideoPlayer updated = new ServerVideoPlayer(
                    p.id(), p.name(), pub, p.editors(), p.playlist(), p.playbackState(), p.screens()
            );
            int idx = savedData.players().indexOf(p);
            savedData.players().set(idx, updated);
            savedData.setDirty();
        });
    }

    public void addVideoToPlaylist(ServerPlayer actor, UUID playerId, VideoSource videoSource) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        List<VideoSource> newPlaylist = new ArrayList<>(player.playlist());
        newPlaylist.add(videoSource);
        ServerVideoPlayer updated = new ServerVideoPlayer(
                player.id(), player.name(), player.isPublic(), player.editors(),
                newPlaylist, player.playbackState(), player.screens()
        );
        int idx = savedData.players().indexOf(player);
        savedData.players().set(idx, updated);
        savedData.setDirty();
    }

    public boolean removeVideoFromPlaylist(ServerPlayer actor, UUID playerId, int index) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        if (index < 0 || index >= player.playlist().size()) {
            return false;
        }

        List<VideoSource> newPlaylist = new ArrayList<>(player.playlist());
        newPlaylist.remove(index);

        ServerVideoPlayer updated = new ServerVideoPlayer(
                player.id(), player.name(), player.isPublic(), player.editors(),
                newPlaylist, player.playbackState(), player.screens()
        );
        int idx = savedData.players().indexOf(player);
        savedData.players().set(idx, updated);
        savedData.setDirty();
        return true;
    }

    public void updateScreenChannel(ServerPlayer actor, UUID playerId, UUID screenId, ScreenChannelState ch) {
        ServerVideoPlayer player = findPlayer(playerId).orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        for (int i = 0; i < player.screens().size(); i++) {
            ServerVideoScreen s = player.screens().get(i);
            if (s.id().equals(screenId)) {
                player.screens().set(i, new ServerVideoScreen(s.id(), s.playerId(), s.name(), s.dimension(), s.vertices(), s.uvTransform(), ch, s.uvManuallyEdited()));
                savedData.setDirty();
                return;
            }
        }
    }

    public void updateScreenUv(ServerPlayer actor, UUID playerId, UUID screenId, UvTransform uv, boolean uvManuallyEdited) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        for (int i = 0; i < player.screens().size(); i++) {
            ServerVideoScreen s = player.screens().get(i);
            if (s.id().equals(screenId)) {
                // 如果客户端声明是手动编辑，保留标志；否则保持原值（防止自动计算覆盖手动标志）
                boolean edited = uvManuallyEdited || s.uvManuallyEdited();
                ServerVideoScreen updated = new ServerVideoScreen(
                        s.id(), s.playerId(), s.name(), s.dimension(), s.vertices(), uv, s.channelState(), edited);
                player.screens().set(i, updated);
                savedData.setDirty();
                return;
            }
        }
    }

    public Optional<ServerVideoPlayer> getCurrentVideo(UUID playerId) {
        return findPlayer(playerId)
                .filter(p -> p.playbackState().currentIndex() >= 0 && p.playbackState().currentIndex() < p.playlist().size())
                .map(p -> {
                    VideoSource current = p.playlist().get(p.playbackState().currentIndex());
                    List<VideoSource> newPlaylist = new ArrayList<>(p.playlist());
                    newPlaylist.set(p.playbackState().currentIndex(), current);
                    return new ServerVideoPlayer(
                            p.id(), p.name(), p.isPublic(), p.editors(),
                            newPlaylist, p.playbackState(), p.screens()
                    );
                });
    }

    /** 视频分辨率探测 — 客户端 libmpv 自行处理，服务端仅保留空实现以兼容网络协议 */
    public void probeVideoResolution(UUID playerId, int videoIndex) {
        // 不再使用 JavaCV，客户端 MpvPlayer 自行获取分辨率
    }

    public void addVideo(ServerPlayer actor, UUID playerId, VideoSource videoSource) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }

        List<VideoSource> newPlaylist = new ArrayList<>(player.playlist());
        newPlaylist.add(videoSource);
        ServerVideoPlayer updated = new ServerVideoPlayer(
                player.id(), player.name(), player.isPublic(), player.editors(),
                newPlaylist, player.playbackState(), player.screens()
        );
        int idx = savedData.players().indexOf(player);
        savedData.players().set(idx, updated);
        savedData.setDirty();
    }

    public void moveVideo(ServerPlayer actor, UUID playerId, int from, int to) {
        ServerVideoPlayer player = findPlayer(playerId).orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) throw new IllegalStateException("权限不足");
        if (from < 0 || from >= player.playlist().size() || to < 0 || to >= player.playlist().size()) return;
        List<VideoSource> newList = new ArrayList<>(player.playlist());
        VideoSource item = newList.remove(from);
        newList.add(to, item);
        savedData.players().set(savedData.players().indexOf(player),
                new ServerVideoPlayer(player.id(), player.name(), player.isPublic(), player.editors(), newList, player.playbackState(), player.screens()));
        savedData.setDirty();
    }

    public boolean removeVideo(ServerPlayer actor, UUID playerId, int index) {
        ServerVideoPlayer player = findPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(actor, player)) {
            throw new IllegalStateException("你没有编辑该播放器的权限");
        }
        if (index < 0 || index >= player.playlist().size()) {
            return false;
        }

        List<VideoSource> newPlaylist = new ArrayList<>(player.playlist());
        newPlaylist.remove(index);

        ServerVideoPlayer updated = new ServerVideoPlayer(
                player.id(), player.name(), player.isPublic(), player.editors(),
                newPlaylist, player.playbackState(), player.screens()
        );
        int idx = savedData.players().indexOf(player);
        savedData.players().set(idx, updated);
        savedData.setDirty();
        return true;
    }

    private ServerLevel getOverworld() {
        return server.overworld();
    }
}

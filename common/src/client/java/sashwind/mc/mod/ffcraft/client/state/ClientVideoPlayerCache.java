package sashwind.mc.mod.ffcraft.client.state;

import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerData;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ClientVideoPlayerCache {
    private static VideoPlayerSnapshot snapshot = new VideoPlayerSnapshot(java.util.List.of());
    private static long version = 0L;

    private ClientVideoPlayerCache() {
    }

    public static VideoPlayerSnapshot getSnapshot() {
        return snapshot;
    }

    public static long getVersion() {
        return version;
    }

    /** 立即将本地缓存中某播放器设为 STOPPED + progress=0 */
    public static void forceStop(UUID playerId) {
        List<VideoPlayerData> list = new ArrayList<>();
        for (var p : snapshot.players()) {
            if (p.id().equals(playerId)) {
                var stopped = new PlaybackState(PlaybackStatus.STOPPED, p.playbackState().mode(),
                        p.playbackState().currentIndex(), 0, p.playbackState().volume(),
                        System.currentTimeMillis() / 1000);
                list.add(new VideoPlayerData(p.id(), p.name(), p.isPublic(), p.editors(), p.playlist(), stopped, p.screens()));
            } else list.add(p);
        }
        snapshot = new VideoPlayerSnapshot(list);
        version++;
    }

    public static void replace(VideoPlayerSnapshot nextSnapshot) {
        snapshot = nextSnapshot;
        version++;
    }

    /** 清空缓存（退出服务器/世界时调用）。残留快照会在重进世界时被 onTick 用来重建播放器 */
    public static void clear() {
        snapshot = new VideoPlayerSnapshot(java.util.List.of());
        version++;
    }

    public static void updateProgress(UUID playerId, PlaybackStatus status, int currentIndex, int progressSeconds) {
        List<VideoPlayerData> newPlayers = new ArrayList<>();
        boolean updated = false;
        for (VideoPlayerData player : snapshot.players()) {
            if (player.id().equals(playerId)) {
                PlaybackState oldState = player.playbackState();
                PlaybackState newState = new PlaybackState(
                        status,
                        oldState.mode(),
                        currentIndex,
                        progressSeconds,
                        oldState.volume(),
                        System.currentTimeMillis() / 1000
                );
                newPlayers.add(new VideoPlayerData(
                        player.id(),
                        player.name(),
                        player.isPublic(),
                        player.editors(),
                        player.playlist(),
                        newState,
                        player.screens()
                ));
                updated = true;
            } else {
                newPlayers.add(player);
            }
        }
        if (updated) {
            snapshot = new VideoPlayerSnapshot(newPlayers);
            version++;
        }
    }
}
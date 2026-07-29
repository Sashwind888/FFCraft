package sashwind.mc.mod.ffcraft.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import sashwind.mc.mod.ffcraft.client.state.ClientVideoPlayerCache;
import sashwind.mc.mod.ffcraft.common.model.*;
import sashwind.mc.mod.ffcraft.common.net.NetworkCodec;
import sashwind.mc.mod.ffcraft.common.net.RawPayload;

import java.nio.charset.StandardCharsets;

public final class VideoPlayerClientNetworking {

    private VideoPlayerClientNetworking() {}

    private static volatile boolean syncRequested = false;

    public static void register() {
        // 接收 raw byte channel（兼容 Bukkit 插件发送的原始 JSON 字节）
        // CustomPacketPayload 类型仍需注册以便发送
        ClientPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            String json = new String(payload.data(), StandardCharsets.UTF_8);
            handle(json);
        });

        // JOIN 时标记需要同步（重置 sent 标记，让 tick 回调在下一帧发送）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientVideoPlayerCache.replace(new VideoPlayerSnapshot(java.util.List.of()));
            syncRequested = false;
        });

        // DISCONNECT 时重置状态
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            syncRequested = false;
        });

        // 只注册一次 tick 回调，通过 syncRequested 标记控制是否发送
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(cl -> {
            if (!syncRequested && net.minecraft.client.Minecraft.getInstance().getConnection() != null) {
                syncRequested = true;
                requestSync();
            }
        });
    }

    private static void handle(String json) {
        var pkt = NetworkCodec.decode(json);
        if (pkt == null) return;
        switch (pkt.type()) {
            case "sync_players" -> ClientVideoPlayerCache.replace(NetworkCodec.parseSyncPlayers(pkt.data()));
            case "update_progress" -> ClientVideoPlayerCache.updateProgress(
                    NetworkCodec.parseUpdateProgressPlayerId(pkt.data()),
                    NetworkCodec.parseUpdateProgressStatus(pkt.data()),
                    NetworkCodec.parseUpdateProgressIndex(pkt.data()),
                    NetworkCodec.parseUpdateProgressSeconds(pkt.data()));
            case "error" -> System.err.println("[Server] " +
                    (pkt.data().has("message") ? pkt.data().get("message").getAsString() : "Unknown error"));
        }
    }

    private static void send(String json) {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) return;
        ClientPlayNetworking.send(new RawPayload(json.getBytes(StandardCharsets.UTF_8)));
    }

    public static VideoPlayerSnapshot snapshot() { return ClientVideoPlayerCache.getSnapshot(); }
    public static void requestSync() { send(NetworkCodec.requestPlayers()); }
    public static void createPlayer(CreatePlayerRequest req) { send(NetworkCodec.createPlayer(req.name(), req.isPublic())); }
    public static void deletePlayer(java.util.UUID pid) { send(NetworkCodec.deletePlayer(pid)); }
    public static void deleteScreen(java.util.UUID pid, java.util.UUID sid) { send(NetworkCodec.deleteScreen(pid, sid)); }
    public static void renamePlayer(java.util.UUID pid, String name) { send(NetworkCodec.renamePlayer(pid, name)); }
    public static void renameScreen(java.util.UUID pid, java.util.UUID sid, String name) { send(NetworkCodec.renameScreen(pid, sid, name)); }
    public static void updatePlayback(java.util.UUID pid, PlaybackStatus st, PlaybackMode md, int idx, int vol) { send(NetworkCodec.updatePlayback(pid, st, md, idx, vol)); }
    public static void seekPlayback(java.util.UUID pid, int secs) { send(NetworkCodec.seek(pid, secs)); }
    public static void updateScreenUv(java.util.UUID pid, java.util.UUID sid, UvTransform uv) { send(NetworkCodec.updateScreenUv(pid, sid, uv)); }
    public static void updateScreenChannel(java.util.UUID pid, java.util.UUID sid, ScreenChannelState ch) { send(NetworkCodec.updateScreenChannel(pid, sid, ch)); }
    public static void addVideoToPlaylist(java.util.UUID pid, String url, int w, int h, int fps) { send(NetworkCodec.addVideo(pid, url, w, h, fps)); }
    public static void removeVideoFromPlaylist(java.util.UUID pid, int idx) { send(NetworkCodec.removeVideo(pid, idx)); }
    public static void moveVideo(java.util.UUID pid, int from, int to) { send(NetworkCodec.moveVideo(pid, from, to)); }
    public static void createScreen(CreateScreenRequest req) {
        send(NetworkCodec.createScreen(req.playerId(), req.name(), req.dimension(), req.vertices()));
    }
}

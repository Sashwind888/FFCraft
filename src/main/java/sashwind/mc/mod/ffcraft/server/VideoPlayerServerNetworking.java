package sashwind.mc.mod.ffcraft.server;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import sashwind.mc.mod.ffcraft.common.model.*;
import sashwind.mc.mod.ffcraft.common.net.NetworkCodec;
import sashwind.mc.mod.ffcraft.common.net.RawPayload;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class VideoPlayerServerNetworking {
    private VideoPlayerServerNetworking() {}

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RawPayload.TYPE, RawPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RawPayload.TYPE, (payload, context) -> {
            String json = new String(payload.data(), StandardCharsets.UTF_8);
            var pkt = NetworkCodec.decode(json);
            if (pkt == null) return;
            try { handle(pkt.type(), pkt.data(), context.player()); }
            catch (RuntimeException e) { sendError(context.player(), e.getMessage()); }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncTo(handler.player));
    }

    private static void handle(String type, com.google.gson.JsonObject data, ServerPlayer player) {
        var svc = VideoPlayerServerRuntime.getService();
        Supplier<java.util.UUID> pid = () -> NetworkCodec.parsePlayerId(data);
        Supplier<java.util.UUID> sid = () -> NetworkCodec.parseScreenId(data);
        Function<String, Integer> st = k -> NetworkCodec.parseInt(data, k);
        switch (type) {
            case "request_players" -> syncTo(player);
            case "create_player" -> {
                svc.createPlayer(player, new CreatePlayerRequest(data.get("name").getAsString(), data.has("isPublic") && data.get("isPublic").getAsBoolean())); syncAll();
            }
            case "create_screen" -> {
                svc.createScreen(player, new CreateScreenRequest(pid.get(), data.get("name").getAsString(), player.level().dimension(), NetworkCodec.parseVertices(data.getAsJsonArray("vertices")))); syncAll();
            }
            case "delete_player" -> { svc.deletePlayer(player, pid.get()); syncAll(); }
            case "delete_screen" -> { svc.deleteScreen(player, pid.get(), sid.get()); syncAll(); }
            case "rename_player" -> { svc.renamePlayer(player, pid.get(), data.get("newName").getAsString()); syncAll(); }
            case "rename_screen" -> { svc.renameScreen(player, pid.get(), sid.get(), data.get("newName").getAsString()); syncAll(); }
            case "update_playback" -> {
                requireEdit(player, pid.get());
                int idx = st.apply("currentIndex");
                var old = svc.findPlayer(pid.get()).orElseThrow().playbackState();
                svc.setPlaybackState(pid.get(), new PlaybackState(PlaybackStatus.values()[st.apply("status")], PlaybackMode.values()[st.apply("mode")], idx,
                        (PlaybackStatus.values()[st.apply("status")] == PlaybackStatus.STOPPED || idx != old.currentIndex()) ? 0 : old.progressSeconds(),
                        st.apply("volume"), System.currentTimeMillis()/1000)); syncAll();
            }
            case "seek" -> {
                requireEdit(player, pid.get());
                var old = svc.findPlayer(pid.get()).orElseThrow().playbackState();
                svc.setPlaybackState(pid.get(), new PlaybackState(old.status(), old.mode(), old.currentIndex(), st.apply("seekSeconds"), old.volume(), System.currentTimeMillis()/1000)); syncAll();
            }
            case "update_screen_uv" -> { svc.updateScreenUv(player, pid.get(), sid.get(), NetworkCodec.parseUv(data.getAsJsonObject("uvTransform"))); syncAll(); }
            case "update_screen_channel" -> {
                var ch = data.getAsJsonObject("channelState");
                svc.updateScreenChannel(player, pid.get(), sid.get(), new ScreenChannelState(ch.get("leftEnabled").getAsBoolean(), ch.get("rightEnabled").getAsBoolean())); syncAll();
            }
            case "add_video" -> {
                svc.addVideo(player, pid.get(), new VideoSource(data.get("url").getAsString(), st.apply("targetWidth"), st.apply("targetHeight"), NetworkCodec.parseInt(data, "targetFps", 30)));
                var p = svc.findPlayer(pid.get()).orElse(null);
                if (p != null) svc.probeVideoResolution(p.id(), p.playlist().size() - 1); syncAll();
            }
            case "remove_video" -> { svc.removeVideo(player, pid.get(), st.apply("index")); syncAll(); }
            case "move_video" -> { svc.moveVideo(player, pid.get(), st.apply("fromIndex"), st.apply("toIndex")); syncAll(); }
        }
    }

    private static void requireEdit(ServerPlayer player, UUID playerId) {
        var svc = VideoPlayerServerRuntime.getService();
        var vp = svc.findPlayer(playerId).orElseThrow(() -> new IllegalArgumentException("播放器不存在"));
        if (!VideoPlayerPermissions.canEdit(player, vp)) throw new IllegalStateException("你没有编辑该播放器的权限");
    }

    private static void sendError(ServerPlayer player, String msg) { send(player, NetworkCodec.encodeError(msg)); }
    private static void send(ServerPlayer player, String json) { ServerPlayNetworking.send(player, new RawPayload(json.getBytes(StandardCharsets.UTF_8))); }

    public static void syncTo(ServerPlayer player) { send(player, NetworkCodec.encodeSyncPlayers(VideoPlayerServerRuntime.getService().snapshot())); }

    public static void syncAll() {
        var p = new RawPayload(NetworkCodec.encodeSyncPlayers(VideoPlayerServerRuntime.getService().snapshot()).getBytes(StandardCharsets.UTF_8));
        for (ServerPlayer sp : VideoPlayerServerRuntime.getServer().getPlayerList().getPlayers()) ServerPlayNetworking.send(sp, p);
    }

    public static void syncProgress(java.util.UUID pid, PlaybackStatus status, int idx, int secs) {
        var p = new RawPayload(NetworkCodec.encodeUpdateProgress(pid, status, idx, secs).getBytes(StandardCharsets.UTF_8));
        for (ServerPlayer sp : VideoPlayerServerRuntime.getServer().getPlayerList().getPlayers()) ServerPlayNetworking.send(sp, p);
    }
}

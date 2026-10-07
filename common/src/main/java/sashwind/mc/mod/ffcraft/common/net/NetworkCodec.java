package sashwind.mc.mod.ffcraft.common.net;

import com.google.gson.*;
import sashwind.mc.mod.ffcraft.common.model.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.core.registries.Registries;

import java.util.*;

/**
 * FFCraft JSON 协议编解码器。
 * 所有包通过 Plugin Messaging Channel "ffcraft:main" 以 JSON 格式传输。
 *
 * 包结构: { "type": "packet_type", "data": { ... } }
 */
public final class NetworkCodec {
    private static final Gson GSON = new GsonBuilder().create();

    private NetworkCodec() {}

    // ═══════════════════════════════════════════════
    //  Clientbound: sync_players
    // ═══════════════════════════════════════════════

    public static String encodeSyncPlayers(VideoPlayerSnapshot snapshot) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "sync_players");
        JsonArray arr = new JsonArray();
        for (var p : snapshot.players()) {
            arr.add(encodePlayer(p));
        }
        JsonObject data = new JsonObject();
        data.add("players", arr);
        root.add("data", data);
        return GSON.toJson(root);
    }

    private static JsonObject encodePlayer(VideoPlayerData p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", p.id().toString());
        o.addProperty("name", p.name());
        o.addProperty("isPublic", p.isPublic());
        JsonArray ed = new JsonArray();
        for (UUID e : p.editors()) ed.add(e.toString());
        o.add("editors", ed);

        JsonArray pl = new JsonArray();
        for (VideoSource vs : p.playlist()) {
            JsonObject v = new JsonObject();
            v.addProperty("url", vs.url());
            v.addProperty("targetWidth", vs.targetWidth());
            v.addProperty("targetHeight", vs.targetHeight());
            if (vs.targetFps() != null) v.addProperty("targetFps", vs.targetFps());
            v.addProperty("originalWidth", vs.originalWidth());
            v.addProperty("originalHeight", vs.originalHeight());
            pl.add(v);
        }
        o.add("playlist", pl);

        o.add("playbackState", encodePlaybackState(p.playbackState()));

        JsonArray sc = new JsonArray();
        for (VideoScreenData s : p.screens()) {
            sc.add(encodeScreen(s));
        }
        o.add("screens", sc);
        return o;
    }

    private static JsonObject encodePlaybackState(PlaybackState ps) {
        JsonObject o = new JsonObject();
        o.addProperty("status", ps.status().ordinal());
        o.addProperty("mode", ps.mode().ordinal());
        o.addProperty("currentIndex", ps.currentIndex());
        o.addProperty("progressSeconds", ps.progressSeconds());
        o.addProperty("volume", ps.volume());
        o.addProperty("lastUpdatedEpochSeconds", ps.lastUpdatedEpochSeconds());
        return o;
    }

    private static JsonObject encodeScreen(VideoScreenData s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id().toString());
        o.addProperty("playerId", s.playerId().toString());
        o.addProperty("name", s.name());
        // ResourceKey<Level>.toString() → "ResourceKey[minecraft:dimension / minecraft:overworld]"
        // 提取 "namespace:path" 部分
        String ts = s.dimension().toString();
        String dimStr = ts.substring(ts.lastIndexOf(' ') + 1, ts.length() - 1);
        o.addProperty("dimension", dimStr);
        JsonArray verts = new JsonArray();
        for (ScreenVertex v : s.vertices()) {
            JsonObject vo = new JsonObject();
            vo.addProperty("x", v.x()); vo.addProperty("y", v.y()); vo.addProperty("z", v.z());
            vo.addProperty("pitch", v.pitch()); vo.addProperty("yaw", v.yaw());
            verts.add(vo);
        }
        o.add("vertices", verts);
        o.add("uvTransform", encodeUv(s.uvTransform()));
        o.add("channelState", encodeCh(s.channelState()));
        o.addProperty("uvManuallyEdited", s.uvManuallyEdited());
        return o;
    }

    private static JsonObject encodeUv(UvTransform uv) {
        JsonObject o = new JsonObject();
        o.addProperty("offsetU", uv.offsetU()); o.addProperty("offsetV", uv.offsetV());
        o.addProperty("scaleU", uv.scaleU()); o.addProperty("scaleV", uv.scaleV());
        o.addProperty("rotationDegrees", uv.rotationDegrees());
        o.addProperty("flipU", uv.flipU()); o.addProperty("flipV", uv.flipV());
        return o;
    }

    private static JsonObject encodeCh(ScreenChannelState ch) {
        JsonObject o = new JsonObject();
        o.addProperty("leftEnabled", ch.leftEnabled());
        o.addProperty("rightEnabled", ch.rightEnabled());
        return o;
    }

    // ═══════════════════════════════════════════════
    //  Clientbound: update_progress
    // ═══════════════════════════════════════════════

    public static String encodeUpdateProgress(UUID playerId, PlaybackStatus status, int currentIndex, int progressSeconds) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "update_progress");
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("status", status.ordinal());
        d.addProperty("currentIndex", currentIndex);
        d.addProperty("progressSeconds", progressSeconds);
        root.add("data", d);
        return GSON.toJson(root);
    }

    // ═══════════════════════════════════════════════
    //  Clientbound: error
    // ═══════════════════════════════════════════════

    public static String encodeError(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "error");
        JsonObject d = new JsonObject();
        d.addProperty("message", message);
        root.add("data", d);
        return GSON.toJson(root);
    }

    // ═══════════════════════════════════════════════
    //  Serverbound: 解码
    // ═══════════════════════════════════════════════

    public static DecodedPacket decode(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String type = root.get("type").getAsString();
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : new JsonObject();
            return new DecodedPacket(type, data);
        } catch (Exception e) {
            System.err.println("[NetworkCodec] Failed to decode: " + json + " error: " + e.getMessage());
            return null;
        }
    }

    public record DecodedPacket(String type, JsonObject data) {}

    // ═══════════════════════════════════════════════
    //  Serverbound 编码 (客户端发送)
    // ═══════════════════════════════════════════════

    public static String encode(String type, JsonObject data) {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.add("data", data != null ? data : new JsonObject());
        return GSON.toJson(root);
    }

    public static String createPlayer(String name, boolean isPublic) {
        JsonObject d = new JsonObject();
        d.addProperty("name", name);
        d.addProperty("isPublic", isPublic);
        return encode("create_player", d);
    }

    public static String createScreen(UUID playerId, String name, ResourceKey<Level> dim, List<ScreenVertex> verts) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("name", name);
        // ResourceKey<Level>.toString() → "ResourceKey[minecraft:dimension / minecraft:overworld]"
        if (dim != null) {
            String ts = dim.toString();
            String clean = ts.startsWith("ResourceKey[") ? ts.substring(ts.lastIndexOf(' ') + 1, ts.length() - 1) : ts;
            d.addProperty("dimension", clean);
        }
        JsonArray arr = new JsonArray();
        for (ScreenVertex v : verts) {
            JsonObject vo = new JsonObject();
            vo.addProperty("x", v.x()); vo.addProperty("y", v.y()); vo.addProperty("z", v.z());
            vo.addProperty("pitch", v.pitch()); vo.addProperty("yaw", v.yaw());
            arr.add(vo);
        }
        d.add("vertices", arr);
        return encode("create_screen", d);
    }

    public static String deletePlayer(UUID playerId) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        return encode("delete_player", d);
    }

    public static String deleteScreen(UUID playerId, UUID screenId) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("screenId", screenId.toString());
        return encode("delete_screen", d);
    }

    public static String renamePlayer(UUID playerId, String newName) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("newName", newName);
        return encode("rename_player", d);
    }

    public static String renameScreen(UUID playerId, UUID screenId, String newName) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("screenId", screenId.toString());
        d.addProperty("newName", newName);
        return encode("rename_screen", d);
    }

    public static String updatePlayback(UUID playerId, PlaybackStatus status, PlaybackMode mode, int index, int volume) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("status", status.ordinal());
        d.addProperty("mode", mode.ordinal());
        d.addProperty("currentIndex", index);
        d.addProperty("volume", volume);
        return encode("update_playback", d);
    }

    public static String seek(UUID playerId, int seekSeconds) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("seekSeconds", seekSeconds);
        return encode("seek", d);
    }

    public static String updateScreenUv(UUID playerId, UUID screenId, UvTransform uv, boolean uvManuallyEdited) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("screenId", screenId.toString());
        d.add("uvTransform", encodeUv(uv));
        d.addProperty("uvManuallyEdited", uvManuallyEdited);
        return encode("update_screen_uv", d);
    }

    public static String updateScreenChannel(UUID playerId, UUID screenId, ScreenChannelState ch) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("screenId", screenId.toString());
        d.add("channelState", encodeCh(ch));
        return encode("update_screen_channel", d);
    }

    public static String addVideo(UUID playerId, String url, int w, int h, int fps) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("url", url);
        d.addProperty("targetWidth", w);
        d.addProperty("targetHeight", h);
        d.addProperty("targetFps", fps);
        return encode("add_video", d);
    }

    public static String removeVideo(UUID playerId, int index) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("index", index);
        return encode("remove_video", d);
    }

    public static String moveVideo(UUID playerId, int from, int to) {
        JsonObject d = new JsonObject();
        d.addProperty("playerId", playerId.toString());
        d.addProperty("fromIndex", from);
        d.addProperty("toIndex", to);
        return encode("move_video", d);
    }

    public static String requestPlayers() {
        return encode("request_players", null);
    }

    // ═══════════════════════════════════════════════
    //  解析 sync_players → VideoPlayerSnapshot
    // ═══════════════════════════════════════════════

    public static VideoPlayerSnapshot parseSyncPlayers(JsonObject data) {
        JsonArray arr = data.getAsJsonArray("players");
        List<VideoPlayerData> list = new ArrayList<>();
        for (var el : arr) {
            list.add(parsePlayer(el.getAsJsonObject()));
        }
        return new VideoPlayerSnapshot(list);
    }

    private static VideoPlayerData parsePlayer(JsonObject o) {
        UUID id = UUID.fromString(o.get("id").getAsString());
        String name = o.get("name").getAsString();
        boolean pub = o.get("isPublic").getAsBoolean();
        Set<UUID> editors = new LinkedHashSet<>();
        if (o.has("editors")) {
            for (var e : o.getAsJsonArray("editors")) editors.add(UUID.fromString(e.getAsString()));
        }
        List<VideoSource> playlist = new ArrayList<>();
        if (o.has("playlist")) {
            for (var v : o.getAsJsonArray("playlist")) {
                playlist.add(parseVideoSource(v.getAsJsonObject()));
            }
        }
        PlaybackState ps = parsePlaybackState(o.getAsJsonObject("playbackState"));
        List<VideoScreenData> screens = new ArrayList<>();
        if (o.has("screens")) {
            for (var s : o.getAsJsonArray("screens")) {
                screens.add(parseScreen(s.getAsJsonObject()));
            }
        }
        return new VideoPlayerData(id, name, pub, editors, playlist, ps, screens);
    }

    private static VideoSource parseVideoSource(JsonObject v) {
        String url = v.get("url").getAsString();
        int tw = v.get("targetWidth").getAsInt(), th = v.get("targetHeight").getAsInt();
        Integer fps = v.has("targetFps") && !v.get("targetFps").isJsonNull() ? v.get("targetFps").getAsInt() : null;
        int ow = v.has("originalWidth") ? v.get("originalWidth").getAsInt() : 0;
        int oh = v.has("originalHeight") ? v.get("originalHeight").getAsInt() : 0;
        return new VideoSource(url, tw, th, fps, ow, oh);
    }

    private static PlaybackState parsePlaybackState(JsonObject o) {
        PlaybackStatus status = PlaybackStatus.values()[o.get("status").getAsInt()];
        PlaybackMode mode = PlaybackMode.values()[o.get("mode").getAsInt()];
        int idx = o.get("currentIndex").getAsInt();
        int prog = o.get("progressSeconds").getAsInt();
        int vol = o.get("volume").getAsInt();
        long ts = o.get("lastUpdatedEpochSeconds").getAsLong();
        return new PlaybackState(status, mode, idx, prog, vol, ts);
    }

    private static VideoScreenData parseScreen(JsonObject o) {
        UUID id = UUID.fromString(o.get("id").getAsString());
        UUID pid = UUID.fromString(o.get("playerId").getAsString());
        String name = o.get("name").getAsString();
        String rawDim = o.get("dimension").getAsString();
        // 兼容多种格式: "ResourceKey[minecraft:dimension / minecraft:overworld]" / "minecraft:overworld" / "world"
        String dimStr;
        if (rawDim.startsWith("ResourceKey[")) {
            dimStr = rawDim.substring(rawDim.lastIndexOf(' ') + 1, rawDim.length() - 1);
        } else {
            dimStr = rawDim;
        }
        if (!dimStr.contains(":")) dimStr = "minecraft:" + dimStr; // "world" → "minecraft:world"
        String[] parts = dimStr.split(":", 2);
        var dim = ResourceKey.create(Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(parts[0], parts[1]));
        List<ScreenVertex> verts = new ArrayList<>();
        for (var v : o.getAsJsonArray("vertices")) {
            var vo = v.getAsJsonObject();
            verts.add(new ScreenVertex(vo.get("x").getAsDouble(), vo.get("y").getAsDouble(), vo.get("z").getAsDouble(),
                    vo.get("pitch").getAsDouble(), vo.get("yaw").getAsDouble()));
        }
        UvTransform uv = parseUv(o.getAsJsonObject("uvTransform"));
        ScreenChannelState ch = parseChannel(o.getAsJsonObject("channelState"));
        boolean uvManuallyEdited = o.has("uvManuallyEdited") && o.get("uvManuallyEdited").getAsBoolean();
        return new VideoScreenData(id, pid, name, dim, verts, uv, ch, uvManuallyEdited);
    }

    public static UvTransform parseUv(JsonObject o) {
        return new UvTransform(
                o.get("offsetU").getAsDouble(), o.get("offsetV").getAsDouble(),
                o.get("scaleU").getAsDouble(), o.get("scaleV").getAsDouble(),
                o.get("rotationDegrees").getAsDouble(),
                o.get("flipU").getAsBoolean(), o.get("flipV").getAsBoolean());
    }

    private static ScreenChannelState parseChannel(JsonObject o) {
        return new ScreenChannelState(o.get("leftEnabled").getAsBoolean(), o.get("rightEnabled").getAsBoolean());
    }

    // ═══════════════════════════════════════════════
    //  解析 update_progress
    // ═══════════════════════════════════════════════

    public static UUID parseUpdateProgressPlayerId(JsonObject data) {
        return UUID.fromString(data.get("playerId").getAsString());
    }

    public static PlaybackStatus parseUpdateProgressStatus(JsonObject data) {
        return PlaybackStatus.values()[data.get("status").getAsInt()];
    }

    public static int parseUpdateProgressIndex(JsonObject data) {
        return data.get("currentIndex").getAsInt();
    }

    public static int parseUpdateProgressSeconds(JsonObject data) {
        return data.get("progressSeconds").getAsInt();
    }

    // ═══════════════════════════════════════════════
    //  Serverbound 数据提取 (客户端发送后服务端需要的参数)
    // ═══════════════════════════════════════════════

    public static UUID parsePlayerId(JsonObject data) {
        return UUID.fromString(data.get("playerId").getAsString());
    }
    public static UUID parseScreenId(JsonObject data) {
        return UUID.fromString(data.get("screenId").getAsString());
    }
    public static String parseNewName(JsonObject data) {
        return data.get("newName").getAsString();
    }
    public static int parseInt(JsonObject data, String key) {
        return data.get(key).getAsInt();
    }
    public static List<ScreenVertex> parseVertices(JsonArray arr) {
        List<ScreenVertex> list = new ArrayList<>();
        for (var el : arr) {
            var o = el.getAsJsonObject();
            list.add(new ScreenVertex(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                    o.get("pitch").getAsDouble(), o.get("yaw").getAsDouble()));
        }
        return list;
    }

    public static int parseInt(JsonObject data, String key, int def) {
        return data.has(key) ? data.get(key).getAsInt() : def;
    }
}

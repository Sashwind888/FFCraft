package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import sashwind.mc.mod.ffcraft.common.model.CreatePlayerRequest;
import sashwind.mc.mod.ffcraft.common.model.CreateScreenRequest;
import sashwind.mc.mod.ffcraft.common.model.PlaybackMode;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
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
import java.util.Set;
import java.util.UUID;

public final class CodecHelper {
    private CodecHelper() {
    }

    public static void writeSnapshot(RegistryFriendlyByteBuf buf, VideoPlayerSnapshot snapshot) {
        buf.writeVarInt(snapshot.players().size());
        for (VideoPlayerData player : snapshot.players()) {
            writePlayerData(buf, player);
        }
    }

    public static VideoPlayerSnapshot readSnapshot(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<VideoPlayerData> players = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            players.add(readPlayerData(buf));
        }
        return new VideoPlayerSnapshot(players);
    }

    public static void writeCreatePlayerRequest(RegistryFriendlyByteBuf buf, CreatePlayerRequest request) {
        buf.writeUtf(request.name());
        buf.writeBoolean(request.isPublic());
    }

    public static CreatePlayerRequest readCreatePlayerRequest(RegistryFriendlyByteBuf buf) {
        return new CreatePlayerRequest(buf.readUtf(), buf.readBoolean());
    }

    public static void writeCreateScreenRequest(RegistryFriendlyByteBuf buf, CreateScreenRequest request) {
        buf.writeUUID(request.playerId());
        buf.writeUtf(request.name());
        writeLevelKey(buf, request.dimension());
        buf.writeVarInt(request.vertices().size());
        for (ScreenVertex vertex : request.vertices()) {
            writeScreenVertex(buf, vertex);
        }
    }

    public static CreateScreenRequest readCreateScreenRequest(RegistryFriendlyByteBuf buf) {
        UUID playerId = buf.readUUID();
        String name = buf.readUtf();
        ResourceKey<Level> dimension = readLevelKey(buf);
        int size = buf.readVarInt();
        List<ScreenVertex> vertices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vertices.add(readScreenVertex(buf));
        }
        return new CreateScreenRequest(playerId, name, dimension, vertices);
    }

    public static void writePlayerData(RegistryFriendlyByteBuf buf, VideoPlayerData player) {
        buf.writeUUID(player.id());
        buf.writeUtf(player.name());
        buf.writeBoolean(player.isPublic());
        writeUuidSet(buf, player.editors());
        buf.writeVarInt(player.playlist().size());
        for (VideoSource source : player.playlist()) {
            writeVideoSource(buf, source);
        }
        writePlaybackState(buf, player.playbackState());
        buf.writeVarInt(player.screens().size());
        for (VideoScreenData screen : player.screens()) {
            writeScreenData(buf, screen);
        }
    }

    public static VideoPlayerData readPlayerData(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        boolean isPublic = buf.readBoolean();
        Set<UUID> editors = readUuidSet(buf);

        int playlistSize = buf.readVarInt();
        List<VideoSource> playlist = new ArrayList<>(playlistSize);
        for (int i = 0; i < playlistSize; i++) {
            playlist.add(readVideoSource(buf));
        }

        PlaybackState playbackState = readPlaybackState(buf);

        int screenSize = buf.readVarInt();
        List<VideoScreenData> screens = new ArrayList<>(screenSize);
        for (int i = 0; i < screenSize; i++) {
            screens.add(readScreenData(buf));
        }

        return new VideoPlayerData(id, name, isPublic, editors, playlist, playbackState, screens);
    }

    public static void writeScreenData(RegistryFriendlyByteBuf buf, VideoScreenData screen) {
        buf.writeUUID(screen.id());
        buf.writeUUID(screen.playerId());
        buf.writeUtf(screen.name());
        writeLevelKey(buf, screen.dimension());
        buf.writeVarInt(screen.vertices().size());
        for (ScreenVertex vertex : screen.vertices()) {
            writeScreenVertex(buf, vertex);
        }
        writeUvTransform(buf, screen.uvTransform());
        writeChannelState(buf, screen.channelState());
    }

    public static VideoScreenData readScreenData(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        UUID playerId = buf.readUUID();
        String name = buf.readUtf();
        ResourceKey<Level> dimension = readLevelKey(buf);

        int size = buf.readVarInt();
        List<ScreenVertex> vertices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            vertices.add(readScreenVertex(buf));
        }

        UvTransform uvTransform = readUvTransform(buf);
        ScreenChannelState channelState = readChannelState(buf);
        return new VideoScreenData(id, playerId, name, dimension, vertices, uvTransform, channelState);
    }

    public static void writeVideoSource(RegistryFriendlyByteBuf buf, VideoSource source) {
        buf.writeUtf(source.url());
        buf.writeVarInt(source.targetWidth());
        buf.writeVarInt(source.targetHeight());
        boolean hasFps = source.targetFps() != null;
        buf.writeBoolean(hasFps);
        if (hasFps) {
            buf.writeVarInt(source.targetFps());
        }
    }

    public static VideoSource readVideoSource(RegistryFriendlyByteBuf buf) {
        String url = buf.readUtf();
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        Integer fps = buf.readBoolean() ? buf.readVarInt() : null;
        return new VideoSource(url, width, height, fps);
    }

    public static void writePlaybackState(RegistryFriendlyByteBuf buf, PlaybackState playbackState) {
        buf.writeEnum(playbackState.status());
        buf.writeEnum(playbackState.mode());
        buf.writeVarInt(playbackState.currentIndex());
        buf.writeVarInt(playbackState.progressSeconds());
        buf.writeVarInt(playbackState.volume());
        buf.writeVarLong(playbackState.lastUpdatedEpochSeconds());
    }

    public static PlaybackState readPlaybackState(RegistryFriendlyByteBuf buf) {
        PlaybackStatus status = buf.readEnum(PlaybackStatus.class);
        PlaybackMode mode = buf.readEnum(PlaybackMode.class);
        int currentIndex = buf.readVarInt();
        int progressSeconds = buf.readVarInt();
        int volume = buf.readVarInt();
        long lastUpdatedEpochSeconds = buf.readVarLong();
        return new PlaybackState(status, mode, currentIndex, progressSeconds, volume, lastUpdatedEpochSeconds);
    }

    public static void writeScreenVertex(RegistryFriendlyByteBuf buf, ScreenVertex vertex) {
        buf.writeDouble(vertex.x());
        buf.writeDouble(vertex.y());
        buf.writeDouble(vertex.z());
        buf.writeDouble(vertex.pitch());
        buf.writeDouble(vertex.yaw());
    }

    public static ScreenVertex readScreenVertex(RegistryFriendlyByteBuf buf) {
        return new ScreenVertex(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void writeUvTransform(RegistryFriendlyByteBuf buf, UvTransform uvTransform) {
        buf.writeDouble(uvTransform.offsetU());
        buf.writeDouble(uvTransform.offsetV());
        buf.writeDouble(uvTransform.scaleU());
        buf.writeDouble(uvTransform.scaleV());
        buf.writeDouble(uvTransform.rotationDegrees());
        buf.writeBoolean(uvTransform.flipU());
        buf.writeBoolean(uvTransform.flipV());
    }

    public static UvTransform readUvTransform(RegistryFriendlyByteBuf buf) {
        return new UvTransform(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readBoolean(), buf.readBoolean());
    }

    public static void writeChannelState(RegistryFriendlyByteBuf buf, ScreenChannelState state) {
        buf.writeBoolean(state.leftEnabled());
        buf.writeBoolean(state.rightEnabled());
    }

    public static ScreenChannelState readChannelState(RegistryFriendlyByteBuf buf) {
        return new ScreenChannelState(buf.readBoolean(), buf.readBoolean());
    }

    public static void writeUuidSet(RegistryFriendlyByteBuf buf, Set<UUID> values) {
        buf.writeVarInt(values.size());
        for (UUID value : values) {
            buf.writeUUID(value);
        }
    }

    public static Set<UUID> readUuidSet(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Set<UUID> values = new LinkedHashSet<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUUID());
        }
        return values;
    }

    public static void writeLevelKey(RegistryFriendlyByteBuf buf, ResourceKey<Level> key) {
        Identifier.STREAM_CODEC.encode(buf, key.identifier());
    }

    public static ResourceKey<Level> readLevelKey(RegistryFriendlyByteBuf buf) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.STREAM_CODEC.decode(buf));
    }
}

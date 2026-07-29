package sashwind.mc.mod.ffcraft.server.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.util.datafix.DataFixTypes;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.PlaybackMode;
import sashwind.mc.mod.ffcraft.common.model.PlaybackState;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;
import sashwind.mc.mod.ffcraft.common.model.ScreenVertex;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;
import sashwind.mc.mod.ffcraft.common.model.VideoSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class VideoPlayerSavedData extends SavedData {
    private static final Codec<Set<UUID>> UUID_SET_CODEC = Codec.STRING.listOf().xmap(
            values -> {
                Set<UUID> result = new LinkedHashSet<>();
                for (String value : values) {
                    result.add(UUID.fromString(value));
                }
                return result;
            },
            values -> values.stream().map(UUID::toString).toList()
    );

    private static final Codec<ScreenVertex> SCREEN_VERTEX_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(ScreenVertex::x),
            Codec.DOUBLE.fieldOf("y").forGetter(ScreenVertex::y),
            Codec.DOUBLE.fieldOf("z").forGetter(ScreenVertex::z),
            Codec.DOUBLE.fieldOf("pitch").forGetter(ScreenVertex::pitch),
            Codec.DOUBLE.fieldOf("yaw").forGetter(ScreenVertex::yaw)
    ).apply(instance, ScreenVertex::new));

    private static final Codec<UvTransform> UV_TRANSFORM_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("offsetU").forGetter(UvTransform::offsetU),
            Codec.DOUBLE.fieldOf("offsetV").forGetter(UvTransform::offsetV),
            Codec.DOUBLE.fieldOf("scaleU").forGetter(UvTransform::scaleU),
            Codec.DOUBLE.fieldOf("scaleV").forGetter(UvTransform::scaleV),
            Codec.DOUBLE.fieldOf("rotationDegrees").forGetter(UvTransform::rotationDegrees),
            Codec.BOOL.fieldOf("flipU").forGetter(UvTransform::flipU),
            Codec.BOOL.fieldOf("flipV").forGetter(UvTransform::flipV)
    ).apply(instance, UvTransform::new));

    private static final Codec<ScreenChannelState> CHANNEL_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("leftEnabled").forGetter(ScreenChannelState::leftEnabled),
            Codec.BOOL.fieldOf("rightEnabled").forGetter(ScreenChannelState::rightEnabled)
    ).apply(instance, ScreenChannelState::new));

    private static final Codec<VideoSource> VIDEO_SOURCE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("url").forGetter(VideoSource::url),
            Codec.INT.fieldOf("targetWidth").forGetter(VideoSource::targetWidth),
            Codec.INT.fieldOf("targetHeight").forGetter(VideoSource::targetHeight),
            Codec.INT.optionalFieldOf("targetFps").forGetter(source -> java.util.Optional.ofNullable(source.targetFps()))
    ).apply(instance, (url, width, height, fps) -> new VideoSource(url, width, height, fps.orElse(null))));

    private static final Codec<PlaybackState> PLAYBACK_STATE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(PlaybackStatus::valueOf, PlaybackStatus::name).fieldOf("status").forGetter(state -> state.status()),
            Codec.STRING.xmap(PlaybackMode::valueOf, PlaybackMode::name).fieldOf("mode").forGetter(state -> state.mode()),
            Codec.INT.fieldOf("currentIndex").forGetter(PlaybackState::currentIndex),
            Codec.INT.fieldOf("progressSeconds").forGetter(PlaybackState::progressSeconds),
            Codec.INT.fieldOf("volume").forGetter(PlaybackState::volume),
            Codec.LONG.fieldOf("lastUpdatedEpochSeconds").forGetter(PlaybackState::lastUpdatedEpochSeconds)
    ).apply(instance, PlaybackState::new));

    private static final Codec<ResourceKey<Level>> LEVEL_KEY_CODEC = Identifier.CODEC.xmap(
            id -> ResourceKey.create(Registries.DIMENSION, id),
            ResourceKey::identifier
    );

    private static final Codec<ServerVideoScreen> SCREEN_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("id").forGetter(screen -> screen.id()),
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("playerId").forGetter(screen -> screen.playerId()),
            Codec.STRING.fieldOf("name").forGetter(ServerVideoScreen::name),
            LEVEL_KEY_CODEC.fieldOf("dimension").forGetter(ServerVideoScreen::dimension),
            SCREEN_VERTEX_CODEC.listOf().fieldOf("vertices").forGetter(ServerVideoScreen::vertices),
            UV_TRANSFORM_CODEC.fieldOf("uvTransform").forGetter(ServerVideoScreen::uvTransform),
            CHANNEL_CODEC.fieldOf("channelState").forGetter(ServerVideoScreen::channelState),
            Codec.BOOL.optionalFieldOf("uvManuallyEdited").forGetter(s -> java.util.Optional.of(s.uvManuallyEdited()))
    ).apply(instance, (id, pid, name, dim, verts, uv, ch, edited) ->
            new ServerVideoScreen(id, pid, name, dim, verts, uv, ch, edited.orElse(false))));

    private static final Codec<ServerVideoPlayer> PLAYER_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("id").forGetter(player -> player.id()),
            Codec.STRING.fieldOf("name").forGetter(ServerVideoPlayer::name),
            Codec.BOOL.fieldOf("isPublic").forGetter(ServerVideoPlayer::isPublic),
            UUID_SET_CODEC.fieldOf("editors").forGetter(ServerVideoPlayer::editors),
            VIDEO_SOURCE_CODEC.listOf().fieldOf("playlist").forGetter(ServerVideoPlayer::playlist),
            PLAYBACK_STATE_CODEC.fieldOf("playbackState").forGetter(ServerVideoPlayer::playbackState),
            SCREEN_CODEC.listOf().fieldOf("screens").forGetter(ServerVideoPlayer::screens)
    ).apply(instance, ServerVideoPlayer::new));

    private static final Codec<VideoPlayerSavedData> CODEC = PLAYER_CODEC.listOf().xmap(VideoPlayerSavedData::new, data -> data.players);

    public static final SavedDataType<VideoPlayerSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, FFCraftConstants.PLAYERS_STATE_KEY),
            VideoPlayerSavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final List<ServerVideoPlayer> players;

    public VideoPlayerSavedData() {
        this.players = new ArrayList<>();
    }

    public VideoPlayerSavedData(List<ServerVideoPlayer> players) {
        this.players = new ArrayList<>(players);
    }

    public static VideoPlayerSavedData get(SavedDataStorage storage) {
        return storage.computeIfAbsent(TYPE);
    }

    public List<ServerVideoPlayer> players() {
        return players;
    }
}

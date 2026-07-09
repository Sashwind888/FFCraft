package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.PlaybackMode;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;

import java.util.UUID;

public record UpdatePlaybackPayload(UUID playerId, PlaybackStatus status, PlaybackMode mode, int index, int volume) implements CustomPacketPayload {
    public static final Type<UpdatePlaybackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "update_playback"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdatePlaybackPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> {
                buf.writeUUID(payload.playerId());
                buf.writeEnum(payload.status());
                buf.writeEnum(payload.mode());
                buf.writeVarInt(payload.index());
                buf.writeVarInt(payload.volume());
            },
            buf -> new UpdatePlaybackPayload(buf.readUUID(), buf.readEnum(PlaybackStatus.class), buf.readEnum(PlaybackMode.class), buf.readVarInt(), buf.readVarInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

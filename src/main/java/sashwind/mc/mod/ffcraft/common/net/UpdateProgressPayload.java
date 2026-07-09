package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.PlaybackStatus;

import java.util.UUID;

public record UpdateProgressPayload(
        UUID playerId,
        PlaybackStatus status,
        int currentIndex,
        int progressSeconds
) implements CustomPacketPayload {
    public static final Type<UpdateProgressPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "update_progress"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateProgressPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeEnum(payload.status());
                buf.writeVarInt(payload.currentIndex());
                buf.writeVarInt(payload.progressSeconds());
            },
            buf -> new UpdateProgressPayload(
                    buf.readUUID(),
                    buf.readEnum(PlaybackStatus.class),
                    buf.readVarInt(),
                    buf.readVarInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
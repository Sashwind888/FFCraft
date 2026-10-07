package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record RemoveVideoPayload(UUID playerId, int index) implements CustomPacketPayload {
    public static final Type<RemoveVideoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "remove_video"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RemoveVideoPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeVarInt(payload.index());
            },
            buf -> new RemoveVideoPayload(buf.readUUID(), buf.readVarInt())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

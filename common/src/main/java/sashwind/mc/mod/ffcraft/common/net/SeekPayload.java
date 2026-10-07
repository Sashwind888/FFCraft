package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record SeekPayload(UUID playerId, int seekSeconds) implements CustomPacketPayload {
    public static final Type<SeekPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "seek"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SeekPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeUUID(p.playerId()); buf.writeVarInt(p.seekSeconds()); },
            buf -> new SeekPayload(buf.readUUID(), buf.readVarInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

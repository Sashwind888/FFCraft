package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record MoveVideoPayload(UUID playerId, int fromIndex, int toIndex) implements CustomPacketPayload {
    public static final Type<MoveVideoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "move_video"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MoveVideoPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeUUID(p.playerId()); buf.writeVarInt(p.fromIndex()); buf.writeVarInt(p.toIndex()); },
            buf -> new MoveVideoPayload(buf.readUUID(), buf.readVarInt(), buf.readVarInt())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

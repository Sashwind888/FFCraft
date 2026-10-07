package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record DeletePlayerPayload(UUID playerId) implements CustomPacketPayload {
    public static final Type<DeletePlayerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "delete_player"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeletePlayerPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> buf.writeUUID(payload.playerId()),
            buf -> new DeletePlayerPayload(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

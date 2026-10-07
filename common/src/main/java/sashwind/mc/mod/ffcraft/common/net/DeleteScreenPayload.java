package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record DeleteScreenPayload(UUID playerId, UUID screenId) implements CustomPacketPayload {
    public static final Type<DeleteScreenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "delete_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteScreenPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> { buf.writeUUID(payload.playerId()); buf.writeUUID(payload.screenId()); },
            buf -> new DeleteScreenPayload(buf.readUUID(), buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

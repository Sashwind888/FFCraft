package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record RenameScreenPayload(UUID playerId, UUID screenId, String newName) implements CustomPacketPayload {
    public static final Type<RenameScreenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "rename_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RenameScreenPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> { buf.writeUUID(payload.playerId()); buf.writeUUID(payload.screenId()); buf.writeUtf(payload.newName()); },
            buf -> new RenameScreenPayload(buf.readUUID(), buf.readUUID(), buf.readUtf())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

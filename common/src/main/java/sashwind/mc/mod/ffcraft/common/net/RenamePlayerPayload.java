package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record RenamePlayerPayload(UUID playerId, String newName) implements CustomPacketPayload {
    public static final Type<RenamePlayerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "rename_player"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RenamePlayerPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> { buf.writeUUID(payload.playerId()); buf.writeUtf(payload.newName()); },
            buf -> new RenamePlayerPayload(buf.readUUID(), buf.readUtf())
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

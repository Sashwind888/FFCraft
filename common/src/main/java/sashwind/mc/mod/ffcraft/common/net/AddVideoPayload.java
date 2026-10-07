package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

import java.util.UUID;

public record AddVideoPayload(UUID playerId, String url, int targetWidth, int targetHeight, int targetFPS) implements CustomPacketPayload {
    public static final Type<AddVideoPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "add_video"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AddVideoPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeUtf(payload.url());
                buf.writeVarInt(payload.targetWidth());
                buf.writeVarInt(payload.targetHeight());
                buf.writeVarInt(payload.targetFPS());
            },
            buf -> new AddVideoPayload(buf.readUUID(), buf.readUtf(65536), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

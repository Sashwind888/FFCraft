package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.UvTransform;

import java.util.UUID;

public record UpdateScreenUvPayload(UUID playerId, UUID screenId, UvTransform uvTransform) implements CustomPacketPayload {
    public static final Type<UpdateScreenUvPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "update_screen_uv"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenUvPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeUUID(payload.playerId());
                buf.writeUUID(payload.screenId());
                buf.writeDouble(payload.uvTransform().offsetU());
                buf.writeDouble(payload.uvTransform().offsetV());
                buf.writeDouble(payload.uvTransform().scaleU());
                buf.writeDouble(payload.uvTransform().scaleV());
                buf.writeDouble(payload.uvTransform().rotationDegrees());
                buf.writeBoolean(payload.uvTransform().flipU());
                buf.writeBoolean(payload.uvTransform().flipV());
            },
            buf -> new UpdateScreenUvPayload(
                    buf.readUUID(), buf.readUUID(),
                    new UvTransform(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readBoolean(), buf.readBoolean())
            )
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

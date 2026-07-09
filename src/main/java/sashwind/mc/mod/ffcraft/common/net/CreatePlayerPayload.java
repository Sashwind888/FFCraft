package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.CreatePlayerRequest;

public record CreatePlayerPayload(CreatePlayerRequest request) implements CustomPacketPayload {
    public static final Type<CreatePlayerPayload> TYPE = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "create_player"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreatePlayerPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> CodecHelper.writeCreatePlayerRequest(buf, payload.request()),
            buf -> new CreatePlayerPayload(CodecHelper.readCreatePlayerRequest(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.CreateScreenRequest;

public record CreateScreenPayload(CreateScreenRequest request) implements CustomPacketPayload {
    public static final Type<CreateScreenPayload> TYPE = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "create_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateScreenPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> CodecHelper.writeCreateScreenRequest(buf, payload.request()),
            buf -> new CreateScreenPayload(CodecHelper.readCreateScreenRequest(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

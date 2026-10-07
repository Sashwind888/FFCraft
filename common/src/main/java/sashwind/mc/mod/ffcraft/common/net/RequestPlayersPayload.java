package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sashwind.mc.mod.ffcraft.FFCraftConstants;

public record RequestPlayersPayload() implements CustomPacketPayload {
    public static final Type<RequestPlayersPayload> TYPE = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "request_players"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPlayersPayload> STREAM_CODEC = StreamCodec.unit(new RequestPlayersPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.VideoPlayerSnapshot;

public record SyncPlayersPayload(VideoPlayerSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<SyncPlayersPayload> TYPE = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "sync_players"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPlayersPayload> STREAM_CODEC = CustomPacketPayload.codec(
            (payload, buf) -> CodecHelper.writeSnapshot(buf, payload.snapshot()),
            buf -> new SyncPlayersPayload(CodecHelper.readSnapshot(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

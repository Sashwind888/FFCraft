package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import sashwind.mc.mod.ffcraft.FFCraftConstants;
import sashwind.mc.mod.ffcraft.common.model.ScreenChannelState;

import java.util.UUID;

public record UpdateScreenChannelPayload(UUID playerId, UUID screenId, ScreenChannelState channel) implements CustomPacketPayload {
    public static final Type<UpdateScreenChannelPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(FFCraftConstants.MOD_ID, "update_screen_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenChannelPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeUUID(p.playerId()); buf.writeUUID(p.screenId()); buf.writeBoolean(p.channel().leftEnabled()); buf.writeBoolean(p.channel().rightEnabled()); },
            buf -> new UpdateScreenChannelPayload(buf.readUUID(), buf.readUUID(), new ScreenChannelState(buf.readBoolean(), buf.readBoolean()))
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}

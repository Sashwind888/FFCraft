package sashwind.mc.mod.ffcraft.common.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RawPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier CHANNEL = Identifier.fromNamespaceAndPath("ffcraft", "main");
    public static final Type<RawPayload> TYPE = new Type<>(CHANNEL);
    // 对齐 Bukkit sendPluginMessage 的原始 byte[] 格式
    public static final StreamCodec<FriendlyByteBuf, RawPayload> CODEC = StreamCodec.ofMember(
            (RawPayload p, FriendlyByteBuf buf) -> buf.writeBytes(p.data()),
            buf -> { byte[] b = new byte[buf.readableBytes()]; buf.readBytes(b); return new RawPayload(b); }
    );

    @Override public Type<RawPayload> type() { return TYPE; }
}

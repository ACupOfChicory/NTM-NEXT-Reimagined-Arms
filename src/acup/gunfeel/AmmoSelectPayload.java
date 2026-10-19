package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client -> server: the radial ammo menu chose this bullet config for the held gun (AmmoLock.select)
public record AmmoSelectPayload(int config) implements CustomPacketPayload {
    public static final Type<AmmoSelectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "ammo_select"));
    public static final StreamCodec<ByteBuf, AmmoSelectPayload> CODEC = ByteBufCodecs.VAR_INT.map(AmmoSelectPayload::new, AmmoSelectPayload::config);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

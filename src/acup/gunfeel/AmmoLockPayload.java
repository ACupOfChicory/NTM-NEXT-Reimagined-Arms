package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client -> server: the ammo lock key was pressed with a gun in hand (AmmoLock.toggle)
public record AmmoLockPayload() implements CustomPacketPayload {
    public static final AmmoLockPayload INSTANCE = new AmmoLockPayload();
    public static final Type<AmmoLockPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "ammo_lock"));
    public static final StreamCodec<ByteBuf, AmmoLockPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

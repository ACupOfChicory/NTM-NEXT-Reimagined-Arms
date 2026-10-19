package acup.gunfeel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Server -> client: an NTM bullet has just been fired. The client draws its own tracer and bullet hole from it,
// so a bullet is seen even when the server removes it within its first tick (a wall a few blocks away).
// eye = where the server tests the path from on the first tick (the shooter's eyes, NaN when not fired from a gun):
// the client tests from the same point, its own idea of the eyes may be off by centimetres.
// damage = the bullet's damage (what it pierces: bullet rules "pierce"). velocity and gravity are per tick, as the server moves the bullet; owner = shooter's entity id or -1.
public record ShotPayload(int config, int owner, double x, double y, double z, float vx, float vy, float vz, float gravity, int life, float damage,
        double ex, double ey, double ez)
        implements CustomPacketPayload {
    public static final Type<ShotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "shot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShotPayload> CODEC = CustomPacketPayload.codec(ShotPayload::write, ShotPayload::read);

    private void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(config);
        b.writeVarInt(owner + 1);
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(vx);
        b.writeFloat(vy);
        b.writeFloat(vz);
        b.writeFloat(gravity);
        b.writeVarInt(life);
        b.writeFloat(damage);
        b.writeDouble(ex);
        b.writeDouble(ey);
        b.writeDouble(ez);
    }

    private static ShotPayload read(RegistryFriendlyByteBuf b) {
        return new ShotPayload(b.readVarInt(), b.readVarInt() - 1, b.readDouble(), b.readDouble(), b.readDouble(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readVarInt(), b.readFloat(),
                b.readDouble(), b.readDouble(), b.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

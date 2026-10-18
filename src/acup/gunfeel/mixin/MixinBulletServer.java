package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.ShotPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// Server: bullets fly faster (bulletSpeed in ntm-gunfeel-server.properties, TaCZ-like) and every new bullet is
// announced to the players around, who draw its tracer and hole themselves.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBulletServer {
    @Shadow
    public BulletConfig config;

    @Shadow
    protected abstract double motionMult();

    @Unique
    private boolean gunfeel$announced;

    // only real bullets (NTM's default 10 blocks a tick); slow projectiles such as grenades keep their speed
    @Inject(method = "motionMult", at = @At("RETURN"), cancellable = true)
    private void gunfeel$speed(CallbackInfoReturnable<Double> cir) {
        if (config != null && config.velocity >= 5F) cir.setReturnValue(cir.getReturnValue() * ReaimTimes.number("bulletSpeed", 1.6F));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void gunfeel$announce(CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (gunfeel$announced || !(self.level() instanceof ServerLevel server)) return;
        gunfeel$announced = true;
        if (config == null) config = self.getBulletConfig();
        // only fast bullets get the client's tracer; slow projectiles (grenades, Shredder's plasma drops) stay NTM's
        if (config == null || config.velocity < 5F) return;
        double mult = motionMult();
        Vec3 v = self.getDeltaMovement().scale(mult);
        Entity owner = self.getOwner();
        ShotPayload p = new ShotPayload(config.id, owner == null ? -1 : owner.getId(), self.getX(), self.getY(), self.getZ(),
                (float) v.x, (float) v.y, (float) v.z, (float) (config.gravity * mult), config.expires);
        PacketDistributor.sendToPlayersNear(server, null, self.getX(), self.getY(), self.getZ(), 256.0, p);
    }
}

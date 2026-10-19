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

// Server: explosive bullets shatter glass; bullets fly faster (bulletSpeed in ntm-gunfeel-server.properties, TaCZ-like) and every new bullet is
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

    // a bullet that blows up where it hits (explosive buckshot) breaks the glass it hits, as a plain bullet does (NTM
    // breaks blocks that sound like glass and glowstone), and blows up there: an impact fuze goes off on the glass
    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$shatter(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || config.onImpact == null || self.level().isClientSide()
                || !(mop instanceof net.minecraft.world.phys.BlockHitResult hit)) return;
        net.minecraft.world.level.block.state.BlockState state = self.level().getBlockState(hit.getBlockPos());
        if (state.instrument() == net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HAT
                || state.is(net.minecraft.world.level.block.Blocks.GLOWSTONE))
            self.level().destroyBlock(hit.getBlockPos(), false);
    }

    // bullet rules (ntm-gunfeel-bullets.txt): a plain bullet breaks the block it hits when a "break" rule says so, then
    // stops there or, with "pass", flies on; an explosive bullet breaks it and still goes off as NTM has it
    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void gunfeel$breakBlock(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (config == null || self.level().isClientSide() || !(mop instanceof net.minecraft.world.phys.BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        int got = acup.gunfeel.BulletServer.blockHit(self, hit);
        if (got == 0 || config.onImpact != null) return;
        Vec3 at = hit.getLocation();
        self.setPos(at.x, at.y, at.z);
        if (got == 1) self.discard();
        ci.cancel();
    }

    @Unique
    private boolean gunfeel$targetAlive;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$entityBefore(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        gunfeel$targetAlive = mop instanceof net.minecraft.world.phys.EntityHitResult e && e.getEntity().isAlive();
    }

    // bullet rules: "entity" and "kill" effects of the creature the bullet hit
    @Inject(method = "onImpact", at = @At("RETURN"))
    private void gunfeel$entityAfter(net.minecraft.world.phys.HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (self.level().isClientSide() || !(mop instanceof net.minecraft.world.phys.EntityHitResult e)) return;
        acup.gunfeel.BulletServer.entityHit(self, e.getEntity(), e.getLocation(), gunfeel$targetAlive);
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
                (float) v.x, (float) v.y, (float) v.z, (float) (config.gravity * mult), config.expires, self.damage);
        PacketDistributor.sendToPlayersNear(server, null, self.getX(), self.getY(), self.getZ(), 256.0, p);
    }
}

package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// TEMPORARY diagnostic (glass stopped breaking): logs each server bullet's block hit and the block left there after it
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBulletImpactLog {
    @Unique
    private static final org.slf4j.Logger gunfeel$LOG = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    @Unique
    private BlockState gunfeel$before;

    @Inject(method = "onImpact", at = @At("HEAD"))
    private void gunfeel$impactHead(HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        gunfeel$before = null;
        if (self.level().isClientSide() || !(mop instanceof BlockHitResult b)) return;
        gunfeel$before = self.level().getBlockState(b.getBlockPos());
    }

    @Inject(method = "onImpact", at = @At("RETURN"))
    private void gunfeel$impactReturn(HitResult mop, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        if (gunfeel$before == null || !(mop instanceof BlockHitResult b)) return;
        BlockState after = self.level().getBlockState(b.getBlockPos());
        var cfg = self.config;
        gunfeel$LOG.info("[impact-debug] bullet {} hit {} at {} face {} inside={} instrument={} -> now {} (onImpact={}, onRicochet={}, removed={})",
                cfg == null ? "?" : cfg.id, gunfeel$before, b.getBlockPos(), b.getDirection(), b.isInside(), gunfeel$before.instrument(), after,
                cfg != null && cfg.onImpact != null, cfg == null ? "?" : cfg.onRicochet == com.hbm.items.weapon.sedna.BulletConfig.LAMBDA_STANDARD_RICOCHET ? "standard" : "custom",
                self.isRemoved());
        gunfeel$before = null;
    }
}

package acup.gunfeel.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;

import acup.gunfeel.PassableClip;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Server: point-blank hits. NTM's bullet starts a little in front of the shooter's eyes, and a hitbox that
// already contains the start of the bullet's path is never hit (AABB.clip misses a segment starting inside).
// On the bullet's first tick its path starts at the shooter's eyes (blocks and mobs in between count), and a
// path starting inside a mob's hitbox hits it.
@Mixin(value = EntityThrowableNT.class, remap = false)
public abstract class MixinThrowableHit {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;"))
    private Optional<Vec3> gunfeel$clip(AABB box, Vec3 from, Vec3 to) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return box.clip(from, to);
        Vec3 start = gunfeel$start(from);
        if (box.contains(start)) return Optional.of(start);
        return box.clip(start, to);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ClipContext;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V"),
            index = 0)
    private Vec3 gunfeel$blockFrom(Vec3 from) {
        return (Object) this instanceof EntityBulletBaseMK4 ? gunfeel$start(from) : from;
    }

    // bullets fly through blocks tagged ntmgunfeel:bullet_passable (PassableClip)
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult gunfeel$blocks(Level level, ClipContext context) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return level.clip(context);
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        if (level.isClientSide()) return PassableClip.clip(level, context.getFrom(), context.getTo());
        // blocks it pierced (bullet rules "pierce"), kept between ticks: one it stopped inside of is not paid twice
        if (gunfeel$pierced == null) gunfeel$pierced = new java.util.HashSet<>();
        return acup.gunfeel.BulletServer.clip(bullet, level, context.getFrom(), context.getTo(), gunfeel$pierced);
    }

    @Unique
    private java.util.Set<net.minecraft.core.BlockPos> gunfeel$pierced;

    @Unique
    private Vec3 gunfeel$start(Vec3 from) {
        EntityThrowableNT self = (EntityThrowableNT) (Object) this;
        if (self.ticksInAir != 1) return from;
        LivingEntity shooter = self.getThrower();
        if (shooter == null) return from;
        // only a bullet fired from the shooter's gun; one spawned elsewhere (Shredder's plasma drops at the beam's
        // end) starts where it is
        Vec3 eye = shooter.getEyePosition();
        return eye.distanceToSqr(from) < 9.0 ? eye : from;
    }
}

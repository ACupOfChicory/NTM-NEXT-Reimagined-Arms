package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

// Server: NTM's standard ricochet (BulletConfig.LAMBDA_STANDARD_RICOCHET, the first lambda of the class) plays its
// ricochet sound; off steel ours instead (BulletServer.ricochetSound). Not found in another NTM version: NTM's sound stays
@Mixin(value = BulletConfig.class, remap = false)
public abstract class MixinRicochetSound {
    @Redirect(method = "lambda$static$0", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private static void gunfeel$sound(Level level, Entity player, double x, double y, double z, SoundEvent sound, SoundSource source, float volume,
            float pitch, EntityBulletBaseMK4 bullet, HitResult mop) {
        if (acup.gunfeel.BulletServer.ricochetSound(level, mop)) return;
        level.playSound(player, x, y, z, sound, source, volume, pitch);
    }
}

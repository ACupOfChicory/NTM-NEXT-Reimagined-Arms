package acup.gunfeel.mixin;

import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.BusAnimation;

import net.minecraft.world.item.ItemStack;

// A sawn-off Maresleg spins around to cock; while aiming that whips the gun through the middle of the
// screen. Aimed, it cycles the lever like the full-length gun instead (same length, so sounds match).
@Mixin(value = GunConfig.class, remap = false)
public abstract class MixinGunConfigAnims {
    private static final BiFunction<ItemStack, GunAnimation, BusAnimation> SAWN_OFF = (stack, type) ->
            (type == GunAnimation.CYCLE || type == GunAnimation.CYCLE_DRY) && ItemGunBaseNT.getIsAiming(stack)
                    ? XFactory12ga.LAMBDA_MARESLEG_ANIMS.apply(stack, type)
                    : XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS.apply(stack, type);

    @Inject(method = "getAnims", at = @At("RETURN"), cancellable = true)
    private void gunfeel$sawnOffAimed(ItemStack stack, CallbackInfoReturnable<BiFunction<ItemStack, GunAnimation, BusAnimation>> cir) {
        // the akimbo pair uses the same animations but cannot be aimed; leave it alone
        if (cir.getReturnValue() == XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS && stack != null && stack.getItem() != ModItems.GUN_MARESLEG_AKIMBO.get())
            cir.setReturnValue(SAWN_OFF);
    }
}

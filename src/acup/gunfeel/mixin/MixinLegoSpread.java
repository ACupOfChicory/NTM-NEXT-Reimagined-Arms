package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;

import acup.gunfeel.Spread;
import net.minecraft.world.item.ItemStack;

// Server: NTM's bullet spread times the shooter's state (standing, moving, sneaking, crawling, aiming), see Spread
@Mixin(value = Lego.class, remap = false)
public abstract class MixinLegoSpread {
    @Inject(method = "calcSpread", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$state(ItemGunBaseNT.LambdaContext ctx, ItemStack stack, Receiver primary, BulletConfig config,
            boolean calcWear, int index, boolean aim, CallbackInfoReturnable<Float> cir) {
        if (ctx == null || ctx.entity() == null) return;
        float mult = Spread.mult(ctx.entity(), stack, aim);
        if (mult != 1F) cir.setReturnValue(cir.getReturnValue() * mult);
    }
}

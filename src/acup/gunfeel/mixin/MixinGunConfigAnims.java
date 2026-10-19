package acup.gunfeel.mixin;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Orchestras;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.render.anim.BusAnimationKeyframe.IType;
import com.hbm.render.anim.BusAnimationSequence;
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

    // Sexy / Heretic inspect: the bottle waits below the screen while the left hand lets go of the gun and
    // drops, then comes up as NTM has it; the gulps and the effects come as much later
    // NTM's bottle starts and ends at y -8, a little of it still on screen: 3 lower here; at the end the
    // animation waits while the hand goes back to the gun after the bottle is gone
    private static final int SEXY_BOTTLE_WAIT_MS = 250, SEXY_BOTTLE_WAIT_TICKS = SEXY_BOTTLE_WAIT_MS / 50, SEXY_HAND_BACK_MS = 250;

    private static final BiFunction<ItemStack, GunAnimation, BusAnimation> SEXY = (stack, type) -> {
        if (type != GunAnimation.INSPECT) return XFactory12ga.LAMBDA_SEXY_ANIMS.apply(stack, type);
        int w = SEXY_BOTTLE_WAIT_MS;
        return new BusAnimation()
                .addBus("BOTTLE", new BusAnimationSequence()
                        .setPos(8.0, -11.0, -2.0)
                        .hold(w)
                        .addPos(6.0, -4.0, -2.0, 500, IType.SIN_DOWN)
                        .addPos(3.0, -3.0, -5.0, 500, IType.SIN_FULL)
                        .addPos(3.0, -2.0, -5.0, 1000)
                        .addPos(4.0, -6.0, -2.0, 750, IType.SIN_FULL)
                        .addPos(6.0, -11.0, -2.0, 500, IType.SIN_UP)
                        .hold(SEXY_HAND_BACK_MS))
                .addBus("SIP", new BusAnimationSequence()
                        .setPos(25.0, 0.0, 0.0)
                        .hold(500 + w)
                        .addPos(-90.0, 0.0, 0.0, 500, IType.SIN_FULL)
                        .addPos(-110.0, 0.0, 0.0, 1000)
                        .addPos(25.0, 0.0, 0.0, 750, IType.SIN_FULL));
    };

    private static final BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> SEXY_ORCHESTRA = (stack, ctx) -> {
        int i = ctx.configIndex();
        if (ItemGunBaseNT.getLastAnim(stack, i) != GunAnimation.INSPECT) {
            Orchestras.ORCHESTRA_SHREDDER_SEXY.accept(stack, ctx);
            return;
        }
        int timer = ItemGunBaseNT.getAnimTimer(stack, i);
        if (timer < SEXY_BOTTLE_WAIT_TICKS) return;
        ItemGunBaseNT.setAnimTimer(stack, i, timer - SEXY_BOTTLE_WAIT_TICKS);
        try {
            Orchestras.ORCHESTRA_SHREDDER_SEXY.accept(stack, ctx);
        } finally {
            ItemGunBaseNT.setAnimTimer(stack, i, timer);
        }
    };

    @Inject(method = "getOrchestra", at = @At("RETURN"), cancellable = true)
    private void gunfeel$sexyOrchestra(ItemStack stack, CallbackInfoReturnable<BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext>> cir) {
        if (cir.getReturnValue() == Orchestras.ORCHESTRA_SHREDDER_SEXY) cir.setReturnValue(SEXY_ORCHESTRA);
    }

    @Inject(method = "getAnims", at = @At("RETURN"), cancellable = true)
    private void gunfeel$sawnOffAimed(ItemStack stack, CallbackInfoReturnable<BiFunction<ItemStack, GunAnimation, BusAnimation>> cir) {
        if (cir.getReturnValue() == XFactory12ga.LAMBDA_SEXY_ANIMS) {
            cir.setReturnValue(SEXY);
            return;
        }
        // the akimbo pair uses the same animations but cannot be aimed; leave it alone
        if (cir.getReturnValue() == XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS && stack != null && stack.getItem() != ModItems.GUN_MARESLEG_AKIMBO.get())
            cir.setReturnValue(SAWN_OFF);
    }
}

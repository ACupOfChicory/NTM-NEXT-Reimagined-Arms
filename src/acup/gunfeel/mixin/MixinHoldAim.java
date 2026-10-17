package acup.gunfeel.mixin;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Lego;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server side: the standard scope lambda toggles on press; make press = aim on, release = aim off. */
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinHoldAim {
    @Inject(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("HEAD"))
    private void gunfeel$press(LivingEntity entity, Container inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        if (key == EnumKeybind.GUN_TERTIARY && down && gunfeel$standardScope(stack)) {
            ItemGunBaseNT.setIsAiming(stack, false); // the toggle that follows turns it on
        }
    }

    @Inject(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("TAIL"))
    private void gunfeel$release(LivingEntity entity, Container inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        if (key == EnumKeybind.GUN_TERTIARY && !down && ItemGunBaseNT.getIsAiming(stack) && gunfeel$standardScope(stack)) {
            ItemGunBaseNT.setIsAiming(stack, false);
        }
    }

    /** only guns whose scope key is the plain aim toggle; guns with a custom tertiary action keep it */
    private boolean gunfeel$standardScope(ItemStack stack) {
        return ((ItemGunBaseNT) (Object) this).getConfig(stack, 0).getPressTertiary(stack) == Lego.LAMBDA_TOGGLE_AIM;
    }
}

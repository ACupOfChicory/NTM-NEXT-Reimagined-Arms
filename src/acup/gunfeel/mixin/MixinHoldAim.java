package acup.gunfeel.mixin;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import acup.gunfeel.ReaimTimes;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.MagazineFullReload;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.items.weapon.sedna.factory.Lego;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import org.jspecify.annotations.Nullable;
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
        if (key != EnumKeybind.GUN_TERTIARY || !gunfeel$standardScope(stack)) return;
        if (!down && ItemGunBaseNT.getIsAiming(stack)) ItemGunBaseNT.setIsAiming(stack, false);
        // no aiming while reloading (NTM drops the aim when a reload starts); the held key is remembered
        if (down && (ItemGunBaseNT.getState(stack, 0) == ItemGunBaseNT.GunState.RELOADING || gunfeel$inspecting(stack))) ItemGunBaseNT.setIsAiming(stack, false);
    }


    /** Back into the aim as the reload ends, if the aim key is still held. */
    @Inject(method = "inventoryTick", at = @At("TAIL"))
    private void gunfeel$reaim(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        if (slot != EquipmentSlot.MAINHAND || ItemGunBaseNT.getIsAiming(stack) || !ItemGunBaseNT.getTertiary(stack, 0)) return;
        if (!gunfeel$reloadEnding(stack) || !gunfeel$standardScope(stack)) return;
        ItemGunBaseNT.setIsAiming(stack, true);
    }

    /** the inspect animation (started by the reload key on a loaded gun, it drops the aim too) is still playing,
     *  short of its last few ticks */
    private boolean gunfeel$inspecting(ItemStack stack) {
        if (ItemGunBaseNT.getLastAnim(stack, 0) != GunAnimation.INSPECT) return false;
        int length = ((ItemGunBaseNT) (Object) this).getConfig(stack, 0).getInspectDuration(stack);
        return ItemGunBaseNT.getAnimTimer(stack, 0) < length - ReaimTimes.ticks(gunfeel$id(stack), "inspect");
    }

    private static String gunfeel$id(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** idle, or in the last few ticks of a reload: the closing step after shell-by-shell loading (RELOAD_END),
     *  or the single step of a whole-magazine reload */
    private boolean gunfeel$reloadEnding(ItemStack stack) {
        if (ItemGunBaseNT.getLastAnim(stack, 0) == GunAnimation.INSPECT) return !gunfeel$inspecting(stack);
        ItemGunBaseNT.GunState state = ItemGunBaseNT.getState(stack, 0);
        if (state == ItemGunBaseNT.GunState.IDLE) return true;
        if (ItemGunBaseNT.getTimer(stack, 0) > ReaimTimes.ticks(gunfeel$id(stack), "reload")) return false;
        GunAnimation last = ItemGunBaseNT.getLastAnim(stack, 0);
        if (state == ItemGunBaseNT.GunState.DRAWING) return last == GunAnimation.RELOAD_END;
        if (state != ItemGunBaseNT.GunState.RELOADING || last != GunAnimation.RELOAD) return false;
        Receiver rec = ((ItemGunBaseNT) (Object) this).getConfig(stack, 0).getReceivers(stack)[0];
        return rec.getMagazine(stack) instanceof MagazineFullReload && rec.getReloadEndDuration(stack) == 0;
    }

    /** only guns whose scope key is the plain aim toggle; guns with a custom tertiary action keep it */
    private boolean gunfeel$standardScope(ItemStack stack) {
        return ((ItemGunBaseNT) (Object) this).getConfig(stack, 0).getPressTertiary(stack) == Lego.LAMBDA_TOGGLE_AIM;
    }
}

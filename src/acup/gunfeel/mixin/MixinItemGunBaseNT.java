package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.config.GunVisualConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;

@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinItemGunBaseNT {
    // Take over NTM's 20 Hz camera recoil: hand the new impulse to CameraRecoil, which applies it per frame.
    @Inject(method = "clientRecoilTick", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$recoilTick(CallbackInfo ci) {
        if (!Config.enabled || !GunVisualConfig.visualRecoil) return;
        if (ItemGunBaseNT.recoilVertical != 0F || ItemGunBaseNT.recoilHorizontal != 0F) {
            CameraRecoil.addImpulse(ItemGunBaseNT.recoilVertical, ItemGunBaseNT.recoilHorizontal);
        }
        ItemGunBaseNT.recoilVertical = 0F;
        ItemGunBaseNT.recoilHorizontal = 0F;
        ItemGunBaseNT.offsetVertical = 0F;
        ItemGunBaseNT.offsetHorizontal = 0F;
        ci.cancel();
    }

    @Inject(method = "setupRecoil(FFFF)V", at = @At("HEAD"))
    private static void gunfeel$recoil(float vertical, float horizontal, float decay, float rebound, CallbackInfo ci) {
        GunFeel.pendingRecoil += Math.abs(vertical) + Math.abs(horizontal);
    }
}

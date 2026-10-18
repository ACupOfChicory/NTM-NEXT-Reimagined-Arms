package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.render.anim.HbmAnimations;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;

@Mixin(value = HbmAnimations.class, remap = false)
public abstract class MixinHbmAnimations {
    // Arms.measuring: the gun as it is with no animation playing
    @Inject(method = "getRelevantAnim(I)Lcom/hbm/render/anim/HbmAnimations$Animation;", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$still(int index, CallbackInfoReturnable<HbmAnimations.Animation> cir) {
        if (acup.gunfeel.Arms.measuring) cir.setReturnValue(null);
    }

    // Tone down NTM's own "RECOIL" animation bus in first person; the spring kick replaces most of it.
    @Inject(method = "getRelevantTransformation(Ljava/lang/String;I)[D", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$recoilBus(String bus, int index, CallbackInfoReturnable<double[]> cir) {
        if (!Config.enabled || Config.ntmRecoilAnim == 1F || GunFeel.current == null || !"RECOIL".equals(bus)) return;
        double[] t = cir.getReturnValue();
        if (t == null) return;
        double[] scaled = t.clone();
        for (int i = 0; i < Math.min(3, scaled.length); i++) scaled[i] *= Config.ntmRecoilAnim;
        cir.setReturnValue(scaled);
    }
}

package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.factory.LegoClient;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Tracers;
import net.minecraft.client.renderer.SubmitNodeCollector;

// Learns a bullet type's tracer colours: Tracers calls NTM's tracer once while probing, and the plain bullet
// tracer reports its colours here instead of drawing.
@Mixin(value = LegoClient.class, remap = false)
public abstract class MixinLegoClient {
    @Inject(method = "renderBulletStandard(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIDDDZI)V",
            at = @At("HEAD"), cancellable = true)
    private static void gunfeel$probe(PoseStack pose, SubmitNodeCollector collector, int dark, int light, double length, double widthF,
            double widthB, boolean fullbright, int packedLight, CallbackInfo ci) {
        if (!Tracers.probing()) return;
        Tracers.probed(dark, light);
        ci.cancel();
    }
}

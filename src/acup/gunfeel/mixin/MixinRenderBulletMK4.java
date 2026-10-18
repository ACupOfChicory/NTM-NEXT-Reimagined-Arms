package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.render.RenderBulletMK4;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Tracers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

// NTM's wide ribbon is not drawn for bullets whose thin tracer Tracers draws.
@Mixin(value = RenderBulletMK4.class, remap = false)
public abstract class MixinRenderBulletMK4 {
    @Inject(method = "submit(Lcom/hbm/client/render/RenderBulletMK4$State;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void gunfeel$hide(RenderBulletMK4.State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (Tracers.replaces(state.config)) ci.cancel();
    }
}

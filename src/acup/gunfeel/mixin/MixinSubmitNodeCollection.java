package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.HoleRender;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;

// Bullet holes on glass and other translucent blocks are drawn after the translucent terrain, over the glass; drawn
// with the other translucent features (before it) the glass's own pixels would cover them.
@Mixin(value = SubmitNodeCollection.class, remap = false)
public abstract class MixinSubmitNodeCollection {
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"), cancellable = true)
    private void gunfeel$afterTerrain(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo ci) {
        if (!HoleRender.isLate(renderType)) return;
        ((SubmitNodeCollection) (Object) this).afterTerrain.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer));
        ci.cancel();
    }
}

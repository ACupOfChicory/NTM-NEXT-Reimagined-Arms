package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.ItemRenderSexy;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Arms;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

// /gunfeel armdebug: where Sexy's bottle is drawn (its first model in renderFirstPerson), for the
// bottle's share on screen in the log
@Mixin(value = ItemRenderSexy.class, remap = false)
public abstract class MixinSexyBottle {
    @Inject(method = "renderFirstPerson", at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry"
            + "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private void gunfeel$bottle(ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (Arms.debugArms) Arms.bottleDrawn(pose.last().pose());
    }
}

package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.main.ResourceManager;
import com.hbm.render.item.weapon.sedna.ItemRenderCoilgun;
import com.hbm.render.item.weapon.sedna.ItemRenderM2;
import com.hbm.render.item.weapon.sedna.ItemRenderStinger;
import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Arms;
import acup.gunfeel.GunFeel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

// Guns whose body NTM draws as one whole model instead of parts: report it to Arms like a part.
@Mixin(value = {ItemRenderCoilgun.class, ItemRenderM2.class, ItemRenderStinger.class}, remap = false)
public abstract class MixinWholeModelGuns {
    @Inject(method = "renderFirstPerson", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry"
            + "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private void gunfeel$wholeModel(ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (GunFeel.current == null) return;
        Object self = this;
        HFRWavefrontObject model = self instanceof ItemRenderCoilgun ? ResourceManager.coilgun
                : self instanceof ItemRenderM2 ? ResourceManager.m2 : ResourceManager.stinger;
        Arms.part(pose, model, -1);
    }
}

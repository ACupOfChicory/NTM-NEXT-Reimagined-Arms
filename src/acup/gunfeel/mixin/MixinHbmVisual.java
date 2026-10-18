package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.client.render.flywheel.HbmBlockEntityVisual;

import acup.gunfeel.RenderedMesh;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Client: NTM's renderers leave the parts Flywheel draws (the press head) to it. While RenderedMesh reads what a
 * renderer draws, there is no visual: the renderer draws them itself, where they are now.
 */
@Mixin(value = HbmBlockEntityVisual.class, remap = false)
public abstract class MixinHbmVisual {
    @Inject(method = "hasVisual", at = @At("HEAD"), cancellable = true, require = 0)
    private static void gunfeel$noVisualWhileRead(BlockEntity be, CallbackInfoReturnable<Boolean> cir) {
        if (RenderedMesh.capturingHere()) cir.setReturnValue(false);
    }
}

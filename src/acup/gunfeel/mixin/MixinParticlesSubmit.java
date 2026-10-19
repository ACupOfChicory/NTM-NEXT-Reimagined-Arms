package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.Perf;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;

// Client, measured only (Perf "Not ours"): how long the game hands the particles to the renderer
@Mixin(ParticlesRenderState.class)
public abstract class MixinParticlesSubmit {
    @Unique
    private long gunfeel$at;

    @Inject(method = "submit", at = @At("HEAD"))
    private void gunfeel$start(CallbackInfo ci) {
        gunfeel$at = System.nanoTime();
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void gunfeel$end(CallbackInfo ci) {
        Perf.other(Perf.P_SUBMIT, gunfeel$at);
    }
}

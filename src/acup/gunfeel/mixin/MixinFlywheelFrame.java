package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.Perf;

// Client, measured only (Perf "Not ours"): Flywheel's frame (its visuals, NTM's explosion smoke among them, updated
// for the frame) and its drawing. Nothing when Flywheel (CrankShaft) is missing or changed
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class MixinFlywheelFrame {
    @Unique
    private long gunfeel$frameAt, gunfeel$renderAt;

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0)
    private void gunfeel$frameStart(CallbackInfo ci) {
        gunfeel$frameAt = System.nanoTime();
    }

    @Inject(method = "beginFrame", at = @At("RETURN"), require = 0)
    private void gunfeel$frameEnd(CallbackInfo ci) {
        Perf.other(Perf.FW_FRAME, gunfeel$frameAt);
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void gunfeel$renderStart(CallbackInfo ci) {
        gunfeel$renderAt = System.nanoTime();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void gunfeel$renderEnd(CallbackInfo ci) {
        Perf.other(Perf.FW_RENDER, gunfeel$renderAt);
    }
}

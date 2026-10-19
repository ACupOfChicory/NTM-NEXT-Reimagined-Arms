package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.BulletFx;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

// Block crumbs of NTM's machine cells (no particle texture: purple and black squares, as NTM's explosion debris
// next to a machine) get the machine's colours instead.
@Mixin(ParticleEngine.class)
public abstract class MixinParticleEngine {
    @Shadow
    protected ClientLevel level;

    @Shadow
    public abstract void add(Particle p);

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.client.particle.ParticleRenderType, net.minecraft.client.particle.ParticleGroup<?>> particles;

    // measured only (Perf "Not ours")
    @org.spongepowered.asm.mixin.Unique
    private long gunfeel$tickAt, gunfeel$extractAt;

    @Inject(method = "tick", at = @At("HEAD"))
    private void gunfeel$tickStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gunfeel$tickAt = System.nanoTime();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void gunfeel$tickEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        acup.gunfeel.Perf.other(acup.gunfeel.Perf.P_TICK, gunfeel$tickAt);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void gunfeel$extractStart(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        gunfeel$extractAt = System.nanoTime();
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void gunfeel$extractEnd(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        acup.gunfeel.Perf.other(acup.gunfeel.Perf.P_EXTRACT, gunfeel$extractAt);
        long n = 0;
        for (net.minecraft.client.particle.ParticleGroup<?> g : particles.values()) n += g.size();
        acup.gunfeel.Perf.particles(n);
    }

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void gunfeel$machineCrumbs(ParticleOptions options, double x, double y, double z, double xa, double ya, double za,
            CallbackInfoReturnable<Particle> cir) {
        if (!(options instanceof BlockParticleOption block) || options.getType() != ParticleTypes.BLOCK || level == null) return;
        Particle p = BulletFx.missingTextureCrumb(level, block.getState(), x, y, z, xa, ya, za);
        if (p == null) return;
        add(p);
        cir.setReturnValue(p);
    }
}

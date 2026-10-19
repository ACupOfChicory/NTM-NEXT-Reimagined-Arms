package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.RadialMenu;
import net.minecraft.client.MouseHandler;

// While the radial ammo menu is open the mouse moves its pointer, not the camera
@Mixin(MouseHandler.class)
public abstract class MixinMouseTurn {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void gunfeel$radial(double dt, CallbackInfo ci) {
        if (!RadialMenu.isOpen()) return;
        RadialMenu.move(accumulatedDX, accumulatedDY);
        accumulatedDX = 0;
        accumulatedDY = 0;
        ci.cancel();
    }
}

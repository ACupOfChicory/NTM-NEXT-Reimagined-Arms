package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.Keybinds;

import acup.gunfeel.RadialMenu;

// While the radial ammo menu is open NTM's gun keys (fire, aim, tertiary/inspect, reload) count as let go: NTM sends
// the release, and a key held through the menu stays up until it is pressed again
@Mixin(targets = "com.hbm.client.Keybinds$GameplayKey", remap = false)
public abstract class MixinGunKeysRadial {
    @Inject(method = "refresh", at = @At("HEAD"))
    private void gunfeel$radial(CallbackInfo ci) {
        if (!RadialMenu.isOpen()) return;
        Object self = this;
        if (self == Keybinds.GUN_PRIMARY || self == Keybinds.GUN_SECONDARY || self == Keybinds.GUN_TERTIARY || self == Keybinds.RELOAD)
            ((KeyMappingAccess) self).gunfeel$setIsDown(false);
    }
}

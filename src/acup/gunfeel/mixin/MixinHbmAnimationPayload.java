package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.packet.toclient.HbmAnimationPayload;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

import acup.gunfeel.InstantSwitch;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

@Mixin(value = HbmAnimationPayload.class, remap = false)
public abstract class MixinHbmAnimationPayload {
    // instantSwitch: the server's draw for a gun whose draw the client already started is not started again
    @Inject(method = "handleSedna", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$predicted(Player player, ItemStack stack, int slot, GunAnimation type, int receiverIndex, int gunIndex, CallbackInfo ci) {
        if (InstantSwitch.serverPacket && !InstantSwitch.accept(slot, stack.getItem(), type, gunIndex)) ci.cancel();
    }

    @Inject(method = "handleClient", at = @At("HEAD"))
    private static void gunfeel$fromServer(CallbackInfo ci) {
        InstantSwitch.serverPacket = true;
    }

    @Inject(method = "handleClient", at = @At("RETURN"))
    private static void gunfeel$fromServerEnd(CallbackInfo ci) {
        InstantSwitch.serverPacket = false;
    }
}

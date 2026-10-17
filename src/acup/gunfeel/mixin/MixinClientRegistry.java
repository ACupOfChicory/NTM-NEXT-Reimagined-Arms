package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.ClientRegistry;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.Config;
import acup.gunfeel.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

@Mixin(value = ClientRegistry.class, remap = false)
public abstract class MixinClientRegistry {
    // Replace NTM's ammo counter and durability bar next to the hotbar with the corner HUD.
    @Inject(method = "renderGunHudComponents", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$hud(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (!Config.enabled || !Config.cornerHud) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        Hud.render(graphics, player, stack, gun);
        ci.cancel();
    }
}

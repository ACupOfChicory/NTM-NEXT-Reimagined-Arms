package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.Config;
import acup.gunfeel.InstantSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;

@Mixin(value = ItemInHandRenderer.class, remap = false)
public abstract class MixinItemInHandRenderer {
    @Shadow @Final private Minecraft minecraft;
    @Shadow private ItemStack mainHandItem;
    @Shadow private float mainHandHeight;
    @Shadow private float oMainHandHeight;

    // instantSwitch: vanilla keeps drawing the old item until the next tick has lowered it; NTM's guns ignore the
    // height, so a gun stayed fully up on a slot that no longer has it. To or from a gun the new item is taken at
    // once: a gun as it is (its draw animation lifts it), anything else from below as vanilla raises it.
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void gunfeel$instant(CallbackInfo ci) {
        if (!Config.instantSwitch || minecraft.player == null) return;
        // the slot may have changed after the frame began (a scroll): start the new gun's draw before it is drawn
        InstantSwitch.frame(minecraft.player);
        ItemStack next = minecraft.player.getMainHandItem();
        if (next == mainHandItem) return;
        boolean nextGun = InstantSwitch.isGun(next);
        if (!nextGun && !InstantSwitch.isGun(mainHandItem)) return;
        mainHandItem = next;
        if (!nextGun) mainHandHeight = oMainHandHeight = 0F;
    }
}

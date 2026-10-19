package acup.gunfeel.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;

import acup.gunfeel.AmmoLock;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// Ammo lock: a belt feeds the first ammo it takes found in the inventory (NTM); on a locked gun only the type it fed
@Mixin(value = MagazineBelt.class, remap = false)
public abstract class MixinAmmoLockBelt {
    @Inject(method = "getFirstConfig", at = @At("HEAD"), cancellable = true)
    private void gunfeel$first(ItemStack stack, Container inventory, CallbackInfoReturnable<BulletConfig> cir) {
        AmmoLock.STACK.set(stack);
        // the chosen type (radial menu) while there is any; run out, the choice is gone and NTM's order counts
        if (inventory == null || AmmoLock.locked(stack)) return;
        BulletConfig next = AmmoLock.chosen(stack);
        if (next == null || !((MagazineBelt) (Object) this).acceptedBullets.contains(next)) return;
        if (AmmoLock.count(next, inventory) != 0) cir.setReturnValue(next);
        else com.hbm.items.weapon.sedna.ItemGunBaseNT.setValueInt(stack, AmmoLock.NEXT, 0);
    }

    @Redirect(method = "getFirstConfig", at = @At(value = "FIELD", opcode = 180 /* GETFIELD */,
            target = "Lcom/hbm/items/weapon/sedna/mags/MagazineBelt;acceptedBullets:Ljava/util/List;"))
    private List<BulletConfig> gunfeel$accepted(MagazineBelt self) {
        return AmmoLock.belt(self, AmmoLock.STACK.get());
    }
}

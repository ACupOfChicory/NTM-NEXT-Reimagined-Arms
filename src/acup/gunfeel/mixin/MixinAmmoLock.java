package acup.gunfeel.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import acup.gunfeel.AmmoLock;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

// Ammo lock and choice (AmmoLock): an empty magazine looks for any ammo it takes (NTM); on a locked gun only for the
// type it had, else the chosen type first
@Mixin(value = MagazineSingleTypeBase.class, remap = false)
public abstract class MixinAmmoLock {
    // our reload: inventory, then ammo bags, then infinite bags; the lock and the chosen type (AmmoLock.reload)
    @Inject(method = "standardReload", at = @At("HEAD"), cancellable = true)
    private void gunfeel$reload(ItemStack stack, Container inventory, int loadLimit, CallbackInfo ci) {
        AmmoLock.STACK.set(stack);
        if (AmmoLock.reload((MagazineSingleTypeBase) (Object) this, stack, inventory, loadLimit)) ci.cancel();
    }

    // what an empty magazine would load, the same way
    @Inject(method = "getFirstConfig", at = @At("HEAD"), cancellable = true)
    private void gunfeel$first(ItemStack stack, Container inventory, CallbackInfoReturnable<BulletConfig> cir) {
        AmmoLock.STACK.set(stack);
        MagazineSingleTypeBase self = (MagazineSingleTypeBase) (Object) this;
        if (inventory != null && self.getAmount(stack, null) == 0)
            cir.setReturnValue(AmmoLock.pick(self.acceptedBullets, self.getType(stack, null), stack, inventory));
    }

    @Redirect(method = {"standardReload", "getFirstConfig"}, at = @At(value = "FIELD", opcode = 180 /* GETFIELD */,
            target = "Lcom/hbm/items/weapon/sedna/mags/MagazineSingleTypeBase;acceptedBullets:Ljava/util/List;"))
    private List<BulletConfig> gunfeel$accepted(MagazineSingleTypeBase self) {
        ItemStack stack = AmmoLock.STACK.get();
        if (!AmmoLock.locked(stack)) return self.acceptedBullets;
        BulletConfig type = self.getType(stack, null);
        return type == null ? self.acceptedBullets : List.of(type);
    }
}

package acup.gunfeel;

import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineBelt;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

// Ammo lock (key in Controls, AmmoLockPayload): a gun keeps the ammo type it has, also once it is empty, and loads
// no other (MixinAmmoLock). Kept on the gun itself.
public final class AmmoLock {
    public static final String KEY = "gunfeel_ammolock";
    // the gun a magazine is looking for ammo for (MixinAmmoLock: set where the search starts)
    public static final ThreadLocal<ItemStack> STACK = new ThreadLocal<>();

    private AmmoLock() {
    }

    public static boolean locked(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemGunBaseNT && ItemGunBaseNT.getValueInt(stack, KEY) != 0;
    }

    // the ammo a locked belt-fed gun keeps: the type it last fed
    public static List<BulletConfig> belt(MagazineBelt mag, ItemStack stack) {
        if (stack == null || !locked(stack)) return mag.acceptedBullets;
        int id = MagazineBelt.getMagType(stack);
        BulletConfig c = id >= 0 && id < BulletConfig.configs.size() ? BulletConfig.configs.get(id) : null;
        return c != null && mag.acceptedBullets.contains(c) ? List.of(c) : mag.acceptedBullets;
    }

    // the server: the held gun's lock on or off
    public static void toggle(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        boolean on = !locked(stack);
        ItemStack icon = null;
        if (on) {
            for (int c = 0; c < gun.getConfigCount(); c++) {
                GunConfig config = gun.getConfig(stack, c);
                Receiver[] receivers = config.getReceivers(stack);
                if (receivers == null) continue;
                for (Receiver r : receivers) {
                    IMagazine<?> mag = r.getMagazine(stack);
                    if (mag == null) continue;
                    // a belt keeps what it feeds now
                    if (mag instanceof MagazineBelt belt) belt.getType(stack, player.getInventory());
                    if (icon == null) icon = mag.getIconForHUD(stack, player);
                }
            }
        }
        ItemGunBaseNT.setValueInt(stack, KEY, on ? 1 : 0);
        player.sendOverlayMessage(on
                ? Component.translatable("message.ntmgunfeel.ammo_locked", icon == null || icon.isEmpty() ? Component.literal("?") : icon.getHoverName())
                : Component.translatable("message.ntmgunfeel.ammo_unlocked"));
    }
}

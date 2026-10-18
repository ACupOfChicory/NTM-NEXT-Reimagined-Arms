package acup.gunfeel;

import com.hbm.items.weapon.sedna.AkimboGhost;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.packet.toclient.HbmAnimationPayload;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// instantSwitch: NTM starts a gun's draw animation from the server (inventoryTick sees the new slot, then a packet),
// so until the next server tick and the packet the new gun is drawn at rest, fully up. The client starts the draw
// itself the frame the slot changes, and the server's draw for that slot and gun is then not started again.
public final class InstantSwitch {
    private InstantSwitch() {
    }

    // HbmAnimationPayload.handleClient is running: the animation comes from the server
    public static boolean serverPacket;
    private static int lastSlot = -1;
    private static Item lastItem;
    // the draw the client started: slot, gun, when, which of its configs still wait for the server's draw
    private static int predictedSlot = -1;
    private static Item predictedItem;
    private static long predictedAt;
    private static boolean[] waiting = new boolean[0];
    private static final long WAIT_MS = 10000L; // real time: a slow server (tickrate 2) still counts

    public static boolean isGun(ItemStack stack) {
        return stack.getItem() instanceof ItemGunBaseNT && !AkimboGhost.isGhost(stack);
    }

    // each frame, before anything is drawn
    public static void frame(LocalPlayer player) {
        if (player == null) {
            lastSlot = -1;
            lastItem = null;
            return;
        }
        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = player.getMainHandItem();
        Item item = stack.getItem();
        if (slot == lastSlot && item == lastItem) return;
        lastSlot = slot;
        lastItem = item;
        predictedSlot = -1;
        if (!Config.instantSwitch || !isGun(stack) || slot < 0 || slot > 8) return;
        ItemGunBaseNT gun = (ItemGunBaseNT) item;
        int n = gun.getConfigCount();
        try {
            for (int i = 0; i < n; i++) HbmAnimationPayload.handleSedna(player, stack, slot, GunAnimation.EQUIP, 0, i);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.debug("Could not start the draw animation", e);
            return;
        }
        predictedSlot = slot;
        predictedItem = item;
        predictedAt = System.currentTimeMillis();
        waiting = new boolean[n];
        java.util.Arrays.fill(waiting, true);
    }

    // the server's animation for the held gun: false = skip it (the client already started this draw)
    public static boolean accept(int slot, Item item, GunAnimation type, int index) {
        if (predictedSlot < 0) return true;
        if (type != GunAnimation.EQUIP) {
            // anything else ends the wait: a later draw from the server is a new one
            predictedSlot = -1;
            return true;
        }
        if (slot != predictedSlot || item != predictedItem || System.currentTimeMillis() - predictedAt > WAIT_MS) return true;
        if (index < 0 || index >= waiting.length || !waiting[index]) return true;
        waiting[index] = false;
        return false;
    }
}

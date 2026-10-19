package acup.gunfeel;

import org.joml.Matrix3x2fStack;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;
import com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

// TaCZ-style ammo HUD in the bottom-right corner, drawn instead of NTM's counter next to the hotbar:
//   [gun]  | 006  0015
//          |      [ammo]
//          | ======== durability
// One block per receiver (akimbo and multi-barrel guns get several, stacked upward).
public final class Hud {
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0x66FFFFFF;
    private static final int GREY = 0xCCFFFFFF;
    private static final int LOW = 0xFFFF5555;

    private Hud() {
    }

    public static void render(GuiGraphicsExtractor g, LocalPlayer player, ItemStack stack, ItemGunBaseNT gun) {
        Font font = Minecraft.getInstance().font;
        Inventory inv = player.getInventory();
        int right = g.guiWidth() - 8;
        int bottom = g.guiHeight() - 8;
        boolean gunDrawn = false;
        for (int c = gun.getConfigCount() - 1; c >= 0; c--) {
            GunConfig config = gun.getConfig(stack, c);
            Receiver[] receivers = config.getReceivers(stack);
            if (receivers == null) continue;
            for (int r = receivers.length - 1; r >= 0; r--) {
                IMagazine<?> mag = receivers[r].getMagazine(stack);
                if (mag == null) continue;
                float wear = ItemGunBaseNT.getWear(stack, c);
                float dura = config.getDurability(stack);
                float health = dura > 0 ? Mth.clamp(1F - wear / dura, 0F, 1F) : 1F;
                block(g, font, player, inv, stack, mag, health, right, bottom, !gunDrawn);
                gunDrawn = true;
                bottom -= 34;
            }
        }
    }

    private static void block(GuiGraphicsExtractor g, Font font, LocalPlayer player, Inventory inv, ItemStack stack,
            IMagazine<?> mag, float health, int right, int bottom, boolean drawGun) {
        int top = bottom - 30;
        String big, small = null;
        boolean low;
        ItemStack icon = mag.getIconForHUD(stack, player);
        if (mag instanceof MagazineSingleTypeBase single) {
            int amount = single.getAmount(stack, inv);
            int capacity = single.getCapacity(stack);
            big = String.format("%03d", Math.min(amount, 999));
            BulletConfig type = reserveType(single, stack, inv);
            int reserve = reserve(type, inv);
            small = reserve == INFINITE ? "\u221E" : String.format("%04d", Math.min(reserve, 9999));
            low = capacity > 0 && amount <= Math.max(1, capacity / 4);
            // an empty magazine, not locked, loads what is found first: its icon
            if (type != null && amount == 0 && type.ammoStack() != null && !type.ammoStack().isEmpty()) icon = type.ammoStack();
        } else {
            // a belt off the inventory: an infinite ammo bag with its ammo counts 9999
            big = mag instanceof com.hbm.items.weapon.sedna.mags.MagazineBelt && mag.getAmount(stack, inv) >= 9999 ? "\u221E"
                    : mag.reportAmmoStateForHUD(stack, player);
            low = false;
        }

        // ammo icon and reserve, right-aligned
        // a fixed column (as wide as "0000"), so a short ∞ or a belt without reserve does not move the icon and padlock
        int smallW = Math.max(16, font.width("0000"));
        int smallX = right - smallW;
        if (small != null) {
            // digits fill the column; a lone ∞ sits in its middle
            int sx = small.equals("\u221E") ? smallX + (smallW - font.width(small)) / 2 : smallX;
            g.text(font, small, sx, top + 2, GREY, true);
        }
        if (icon != null && !icon.isEmpty()) g.item(icon, smallX, top + 12);
        if (AmmoLock.locked(stack)) lock(g, smallX + 18, top + 17);

        // big magazine count at 2x, leading zeros dimmed
        int bigW = font.width(big) * 2;
        int bigX = smallX - 4 - bigW;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(bigX, top + 2);
        pose.scale(2F, 2F);
        int lead = 0;
        if (small != null) {
            while (lead < big.length() - 1 && big.charAt(lead) == '0') lead++;
        }
        String zeros = big.substring(0, lead);
        int color = low ? LOW : WHITE;
        if (!zeros.isEmpty()) g.text(font, zeros, 0, 0, DIM, true);
        g.text(font, big.substring(lead), font.width(zeros), 0, color, true);
        pose.popMatrix();

        // durability bar under the numbers
        int barL = bigX, barR = right, barY = top + 28;
        g.fill(barL, barY, barR, barY + 2, 0x80000000);
        int fillR = barL + Math.round((barR - barL) * health);
        g.fill(barL, barY, fillR, barY + 2, durabilityColor(health));

        // separator and gun icon
        int sepX = bigX - 6;
        g.fill(sepX, top, sepX + 1, bottom, 0xCCFFFFFF);
        if (drawGun) {
            Identifier silhouette = Silhouette.get(stack.getItem());
            if (silhouette != null) {
                int w = 72, h = w * Silhouette.H / Silhouette.W;
                g.blit(RenderPipelines.GUI_TEXTURED, silhouette, sepX - 6 - w, top + (30 - h) / 2, 0F, 0F, w, h,
                        Silhouette.W, Silhouette.H, Silhouette.W, Silhouette.H);
            } else {
                g.item(stack, sepX - 6 - 16, top + 7);
            }
        }
    }

    // the ammo lock: a small grey padlock (5 x 7), with a shadow
    private static void lock(GuiGraphicsExtractor g, int x, int y) {
        g.nextStratum(); // above the item icon
        for (int pass = 0; pass < 2; pass++) {
            int o = pass == 0 ? 1 : 0, c = pass == 0 ? 0x80000000 : 0xFFA0A0A0;
            g.fill(x + 1 + o, y + o, x + 4 + o, y + 1 + o, c); // shackle top
            g.fill(x + 1 + o, y + 1 + o, x + 2 + o, y + 3 + o, c);
            g.fill(x + 3 + o, y + 1 + o, x + 4 + o, y + 3 + o, c);
            g.fill(x + o, y + 3 + o, x + 5 + o, y + 7 + o, c); // body
        }
        g.fill(x + 2, y + 4, x + 3, y + 6, 0xFF505050); // keyhole
    }

    private static final int INFINITE = AmmoLock.INFINITE;

    // the ammo the reserve counts: the magazine's type, or for an empty one not locked what a reload finds first
    private static BulletConfig reserveType(MagazineSingleTypeBase mag, ItemStack gun, Inventory inv) {
        BulletConfig type = mag.getAmount(gun, inv) == 0 ? mag.getFirstConfig(gun, inv) : null;
        return type != null ? type : mag.getType(gun, inv);
    }

    private static int reserve(BulletConfig type, Inventory inv) {
        return AmmoLock.count(type, inv);
    }

    private static int durabilityColor(float health) {
        int r = (int) (255 * Mth.clamp(2F - 2F * health, 0F, 1F));
        int gr = (int) (255 * Mth.clamp(2F * health, 0F, 1F));
        return 0xFF000000 | r << 16 | gr << 8 | 0x40;
    }
}

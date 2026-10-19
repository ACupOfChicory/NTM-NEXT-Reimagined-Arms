package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3x2fStack;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

// Client: the radial ammo menu (ammo key held, AmmoLockKey). The ammo the held gun takes and the player has, one
// sector each, clockwise from the top; the mouse moves a pointer out of the centre instead of the camera
// (MixinMouseTurn), the sector it points at is taken when the key is let go.
public final class RadialMenu {
    private static final int MAX = 16, SIZE = 128, TEX = 256, ICON_R = 42, DEAD = 10, REACH = 50;
    private static final long OPEN_MS = 110;

    private static List<BulletConfig> types;
    private static ItemStack gun = ItemStack.EMPTY;
    private static BulletConfig current;
    private static double px, py;
    private static long openedAt;

    private RadialMenu() {
    }

    public static boolean isOpen() {
        return types != null;
    }

    // the choices for that gun: what its first magazine takes and the inventory or its bags hold
    static List<BulletConfig> choices(ItemStack stack) {
        List<BulletConfig> out = new ArrayList<>();
        IMagazine<?> mag = AmmoLock.primary(stack);
        var player = Minecraft.getInstance().player;
        if (mag == null || player == null) return out;
        for (BulletConfig c : AmmoLock.accepted(mag)) {
            if (out.size() < MAX && !out.contains(c) && AmmoLock.count(c, player.getInventory()) != 0) out.add(c);
        }
        return out;
    }

    static boolean open(ItemStack stack) {
        List<BulletConfig> list = choices(stack);
        if (list.isEmpty()) return false;
        types = list;
        gun = stack;
        IMagazine<?> mag = AmmoLock.primary(stack);
        Object t = mag == null ? null : mag.getType(stack, Minecraft.getInstance().player.getInventory());
        current = t instanceof BulletConfig b ? b : null;
        px = py = 0;
        openedAt = System.currentTimeMillis();
        return true;
    }

    static void close() {
        types = null;
        gun = ItemStack.EMPTY;
    }

    static ItemStack gun() {
        return gun;
    }

    // MixinMouseTurn: mouse movement while open
    public static void move(double dx, double dy) {
        px += dx * 0.4;
        py += dy * 0.4;
        double l = Math.sqrt(px * px + py * py);
        if (l > REACH) {
            px *= REACH / l;
            py *= REACH / l;
        }
    }

    static BulletConfig hovered() {
        int i = hoveredIndex();
        return i < 0 ? null : types.get(i);
    }

    private static int hoveredIndex() {
        if (types == null || px * px + py * py < DEAD * DEAD) return -1;
        int n = types.size();
        double a = Math.atan2(px, -py); // clockwise from the top
        double step = Math.PI * 2 / n;
        return Math.floorMod((int) Math.floor((a + step / 2) / step), n);
    }

    static void render(GuiGraphicsExtractor g) {
        if (types == null) return;
        var mc = Minecraft.getInstance();
        int n = types.size();
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        float t = Math.min(1F, (System.currentTimeMillis() - openedAt) / (float) OPEN_MS);
        float scale = 0.85F + 0.15F * (1 - (1 - t) * (1 - t));
        int alpha = Math.round(255 * t);
        int tint = alpha << 24 | 0xFFFFFF;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx, cy);
        pose.scale(scale, scale);

        g.blit(RenderPipelines.GUI_TEXTURED, tex("ring", n), -SIZE / 2, -SIZE / 2, 0F, 0F, SIZE, SIZE, TEX, TEX, TEX, TEX, tint);
        int hover = hoveredIndex();
        if (hover >= 0) {
            pose.pushMatrix();
            pose.rotate((float) (Math.PI * 2 * hover / n));
            g.blit(RenderPipelines.GUI_TEXTURED, tex("wedge", n), -SIZE / 2, -SIZE / 2, 0F, 0F, SIZE, SIZE, TEX, TEX, TEX, TEX, tint);
            pose.popMatrix();
        }

        var inv = mc.player.getInventory();
        for (int i = 0; i < n; i++) {
            BulletConfig c = types.get(i);
            double a = Math.PI * 2 * i / n;
            int x = (int) Math.round(Math.sin(a) * ICON_R), y = (int) Math.round(-Math.cos(a) * ICON_R);
            ItemStack icon = c.ammoStack();
            if (icon != null && !icon.isEmpty()) g.item(icon, x - 8, y - 8);
            if (c == current) g.fill(x - 3, y + 9, x + 3, y + 10, alpha << 24 | 0xFFC446); // loaded now
            int count = AmmoLock.count(c, inv);
            String s = count == AmmoLock.INFINITE ? "∞" : Integer.toString(count);
            pose.pushMatrix();
            pose.translate(x + 9, y + 3);
            pose.scale(0.5F, 0.5F);
            g.text(mc.font, s, -mc.font.width(s), 0, alpha << 24 | 0xDDDDDD, true);
            pose.popMatrix();
        }

        // the pointer
        int dx = (int) Math.round(px * 0.4), dy = (int) Math.round(py * 0.4);
        g.fill(dx - 1, dy - 1, dx + 1, dy + 1, alpha << 24 | 0xFFFFFF);

        // the hovered ammo's name in the middle-bottom
        BulletConfig h = hovered();
        if (h != null) {
            ItemStack icon = h.ammoStack();
            if (icon != null && !icon.isEmpty())
                g.centeredText(mc.font, icon.getHoverName(), 0, SIZE / 2 + 6, alpha << 24 | 0xFFFFFF);
        }
        pose.popMatrix();
    }

    private static final Identifier[] RING = new Identifier[MAX + 1], WEDGE = new Identifier[MAX + 1];

    private static Identifier tex(String kind, int n) {
        Identifier[] cache = kind.equals("ring") ? RING : WEDGE;
        if (cache[n] == null) cache[n] = Identifier.fromNamespaceAndPath("ntmgunfeel", "textures/gui/radial/" + kind + "_" + n + ".png");
        return cache[n];
    }
}

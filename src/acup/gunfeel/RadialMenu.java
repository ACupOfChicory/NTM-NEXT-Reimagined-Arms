package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3x2fStack;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

// Client: the radial ammo menu (ammo key held, AmmoLockKey). The ammo the held gun takes and the player has, one
// sector each, clockwise from the top. The mouse turns an unseen pointer out of the centre instead of the camera
// (MixinMouseTurn); the sector it points at slides out and is taken when the key is let go. Looks: Config.ammoMenu
// (steel, petals, bubbles, ntm, glass, arc), colour Config.ammoMenuColor; drafts in notes/radial-site.
public final class RadialMenu {
    private static final int MAX = 16;
    private static final float R_IN = 23, R_OUT = 62, DEAD = 10, REACH = 50, TAU = (float) (Math.PI * 2);
    private static final int WHITE = 0xFFFFFF;

    private static List<BulletConfig> types;
    private static ItemStack gun = ItemStack.EMPTY;
    private static BulletConfig current;
    private static double px, py;
    private static long openedAt, lastFrame;
    private static float[] h = new float[0];
    private static float any;

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
        h = new float[list.size()];
        any = 0;
        openedAt = lastFrame = System.nanoTime();
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
        double a = Math.atan2(px, -py);
        double step = Math.PI * 2 / n;
        return Math.floorMod((int) Math.floor((a + step / 2) / step), n);
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static float sx(float r, float a) {
        return (float) Math.sin(a) * r;
    }

    private static float sy(float r, float a) {
        return (float) -Math.cos(a) * r;
    }

    // ---- drawing state of one frame ----
    private static final class Frame {
        GuiGraphicsExtractor g;
        Font font;
        int n, hover, cur, acc;
        float step, open, any, ms;
        float[] in;
        List<BulletConfig> items;

        float ang(int i) {
            return i * step;
        }
    }

    static void render(GuiGraphicsExtractor g) {
        if (types == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.nanoTime();
        float dt = Math.min(50, (now - lastFrame) / 1e6F);
        lastFrame = now;
        Frame f = new Frame();
        f.g = g;
        f.font = mc.font;
        f.items = types;
        f.n = types.size();
        f.step = TAU / f.n;
        f.hover = hoveredIndex();
        f.cur = types.indexOf(current);
        f.acc = Config.ammoMenuColor;
        f.ms = (now - openedAt) / 1e6F;
        float k = 1 - (float) Math.exp(-dt / 50);
        for (int i = 0; i < f.n; i++) h[i] += ((i == f.hover ? 1 : 0) - h[i]) * k;
        any += ((f.hover >= 0 ? 1 : 0) - any) * k;
        f.any = any;
        f.in = new float[f.n];
        for (int i = 0; i < f.n; i++) f.in[i] = ease((f.ms - i * 22) / 90); // the sectors come in clockwise
        f.open = ease(f.ms / 110);
        String style = Config.ammoMenu;

        g.nextStratum();
        if (style.equals("glass")) {
            try {
                g.blurBeforeThisStratum();
            } catch (RuntimeException e) {
                // blurred once this frame already
            }
        }
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(g.guiWidth() / 2F, g.guiHeight() / 2F);
        float sc = 0.88F + 0.12F * f.open;
        pose.scale(sc, sc);
        GuiShapes sh = new GuiShapes(g);
        switch (style) {
            case "petals" -> petals(f, sh, true);
            case "bubbles" -> bubbles(f, sh, true);
            case "ntm" -> ntm(f, sh, true);
            case "glass" -> glass(f, sh, true);
            case "arc" -> arc(f, sh, true);
            default -> steel(f, sh, true);
        }
        sh.submit(g);
        GuiShapes over = new GuiShapes(g); // marks above the icons
        switch (style) {
            case "petals" -> petals(f, over, false);
            case "bubbles" -> bubbles(f, over, false);
            case "ntm" -> ntm(f, over, false);
            case "glass" -> glass(f, over, false);
            case "arc" -> arc(f, over, false);
            default -> steel(f, over, false);
        }
        over.submit(g);
        pose.popMatrix();
    }

    // ---- shared bits ----

    private static void icon(Frame f, int i, float x, float y, float scale) {
        ItemStack s = f.items.get(i).ammoStack();
        if (s == null || s.isEmpty()) return;
        Matrix3x2fStack pose = f.g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        float k = scale * (0.6F + 0.4F * f.in[i]);
        pose.scale(k, k);
        f.g.item(s, -8, -8);
        pose.popMatrix();
    }

    private static void text(Frame f, String s, float x, float y, int rgb, float alpha, float scale) {
        if (alpha < 0.03F) return;
        Matrix3x2fStack pose = f.g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        f.g.text(f.font, s, -f.font.width(s) / 2, 0, GuiShapes.argb(rgb, alpha * f.open), true);
        pose.popMatrix();
    }

    private static String count(Frame f, int i) {
        int c = AmmoLock.count(f.items.get(i), Minecraft.getInstance().player.getInventory());
        return c == AmmoLock.INFINITE ? "∞" : Integer.toString(c);
    }

    // icon above, count below; both grow with the hover
    private static float[] entry(Frame f, int i, float r, float grow, int rgb, String prefix, float alpha) {
        float a = f.ang(i), x = sx(r, a), y = sy(r, a);
        icon(f, i, x, y - 4 - 2 * h[i], 1 + grow * h[i]);
        text(f, prefix + count(f, i), x, y + 6 + 3 * h[i], rgb, alpha * f.in[i], 1);
        return new float[] {x, y};
    }

    // the hovered ammo's name: "Патрон 12 калибра (Пуля)" as two lines
    private static void caption(Frame f, float y, int top, int main) {
        if (f.hover < 0) return;
        ItemStack s = f.items.get(f.hover).ammoStack();
        if (s == null || s.isEmpty()) return;
        String name = s.getHoverName().getString();
        int p = name.lastIndexOf(" ("), q = name.lastIndexOf(')');
        if (p > 0 && q > p) {
            text(f, name.substring(0, p), 0, y, top, 1, 1);
            text(f, name.substring(p + 2, q), 0, y + 10, main, 1, 1);
        } else {
            text(f, name, 0, y + 10, main, 1, 1);
        }
    }

    private static int c(int rgb, float alpha, Frame f) {
        return GuiShapes.argb(rgb, alpha * f.open);
    }

    // ---- the looks ----

    // A: a solid gunmetal ring with thin dividers; the hovered sector slides out lit from the rim
    private static void steel(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        float step = f.step;
        if (base) {
            sh.ring(0, 0, R_IN, R_OUT, c(0x22252A, .86F, f), c(0x0C0D10, .88F, f));
            for (int i = 0; i < n; i++) {
                float hv = h[i];
                if (hv < .01F) continue;
                float a = f.ang(i), ox = sx(5 * hv, a), oy = sy(5 * hv, a);
                sh.sector(ox, oy, R_IN, R_OUT * .55F + R_IN * .45F, a - step / 2, a + step / 2, c(0x2C2F35, .96F * hv, f), c(0x303238, .96F * hv, f));
                sh.sector(ox, oy, R_OUT * .55F + R_IN * .45F, R_OUT - 2.5F, a - step / 2, a + step / 2, c(0x303238, .96F * hv, f),
                        c(GuiShapes.mix(0x303238, f.acc, .45F), .96F * hv, f));
                sh.sector(ox, oy, R_OUT - 2.5F, R_OUT, a - step / 2, a + step / 2, c(f.acc, hv, f), c(f.acc, hv, f));
            }
            if (n > 1) for (int i = 0; i < n; i++) sh.radial(f.ang(i) + step / 2, R_IN + 4, R_OUT - 4, 1, c(WHITE, .13F, f));
            sh.ring(0, 0, R_OUT - 1, R_OUT - .2F, c(WHITE, .10F, f), c(WHITE, .10F, f));
            sh.ring(0, 0, R_IN + .2F, R_IN + 1, c(WHITE, .10F, f), c(WHITE, .10F, f));
            return;
        }
        for (int i = 0; i < n; i++) {
            float[] p = entry(f, i, 42 + 5 * h[i], .25F, i == f.cur ? f.acc : 0xEBEBEB, "", 1);
            if (i == f.cur) { // the loaded type: four corners round its icon (entry: centre y - 4 - 2h, 16 px x 1 + .25h), upright
                int col = c(f.acc, f.in[i], f);
                float half = 8 * (1 + .25F * h[i]) * (.6F + .4F * f.in[i]) + 2.5F, cy = p[1] - 4 - 2 * h[i];
                float x = p[0], t = cy - half, b = cy + half;
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float cx = x + half * sgn;
                    sh.line(cx, t, cx, t + 4, 1, col);
                    sh.line(cx + .5F * sgn, t, cx - 4 * sgn, t, 1, col);
                    sh.line(cx, b, cx, b - 4, 1, col);
                    sh.line(cx + .5F * sgn, b, cx - 4 * sgn, b, 1, col);
                }
            }
        }
        caption(f, R_OUT + 10, 0xAAAAAA, WHITE);
    }

    // B: separate rounded segments; the hovered one grows out and fills with colour, the rest dim
    private static void petals(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        float step = f.step, gap = n > 1 ? 3 : 0;
        if (base) {
            for (int i = 0; i < n; i++) {
                float hv = h[i], dim = f.any * (1 - hv), a = f.ang(i), al = f.in[i];
                float r0 = R_IN + 1, r1 = R_OUT - 5 + 9 * hv - 2 * dim;
                sh.petal(0, 0, r0, r1, a - step / 2, a + step / 2, gap, 4, c(0x111317, (.80F - .22F * dim) * al, f), c(0x111317, (.80F - .22F * dim) * al, f));
                if (hv > .01F) sh.petal(0, 0, r0, r1, a - step / 2, a + step / 2, gap, 4, c(f.acc, .10F * hv, f), c(f.acc, .55F * hv, f));
                // a thin rim on the outer edge
                sh.petal(0, 0, r1 - 1, r1, a - step / 2, a + step / 2, gap, 2,
                        hv > .01F ? c(f.acc, hv * al, f) : c(WHITE, .09F * al, f), hv > .01F ? c(f.acc, hv * al, f) : c(WHITE, .09F * al, f));
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            float dim = 1 - .45F * f.any * (1 - h[i]);
            entry(f, i, 40 + 6 * h[i], .5F, WHITE, "", dim);
            if (i == f.cur) {
                float a = f.ang(i);
                sh.disc(sx(R_IN + 5, a), sy(R_IN + 5, a), 1.6F, c(f.acc, f.in[i], f), c(f.acc, f.in[i], f));
            }
        }
        caption(f, R_OUT + 12, 0xAAAAAA, GuiShapes.mix(WHITE, f.acc, .35F));
    }

    // C: no ring, a circle each; the hovered one swells and moves out, its count big in the middle
    private static void bubbles(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        if (base) {
            for (int k = 0; k < 64; k += 2) sh.sector(0, 0, 43.5F, 44.5F, TAU * k / 64, TAU * (k + 1) / 64, c(WHITE, .2F, f), c(WHITE, .2F, f));
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (p, q) -> Float.compare(h[p], h[q]));
            for (int i : order) {
                float hv = h[i], a = f.ang(i), x = sx(44 + 7 * hv, a), y = sy(44 + 7 * hv, a), cr = (13 + 5 * hv) * (.6F + .4F * f.in[i]), al = f.in[i];
                if (i == f.cur) sh.ring(x, y, cr + 2.5F, cr + 3.3F, c(WHITE, .65F * al, f), c(WHITE, .65F * al, f));
                sh.disc(x, y, cr, c(0x2E323A, .92F * al, f), c(0x0E1014, .9F * al, f));
                if (hv > .01F) {
                    sh.disc(x, y, cr, c(f.acc, .12F * hv, f), c(f.acc, .28F * hv, f));
                    sh.ring(x, y, cr - .5F, cr + 1, c(f.acc, hv * al, f), c(f.acc, hv * al, f));
                } else sh.ring(x, y, cr - .2F, cr + .6F, c(WHITE, .14F * al, f), c(WHITE, .14F * al, f));
            }
            sh.disc(0, 0, 21, c(0x0E1014, .78F, f), c(0x0E1014, .78F, f));
            sh.ring(0, 0, 21, 21.8F, c(WHITE, .12F, f), c(WHITE, .12F, f));
            return;
        }
        for (int i = 0; i < n; i++) {
            float hv = h[i], a = f.ang(i);
            icon(f, i, sx(44 + 7 * hv, a), sy(44 + 7 * hv, a), 1 + .45F * hv);
        }
        if (f.hover >= 0) text(f, count(f, f.hover), 0, -7, f.acc, 1, 2);
        caption(f, R_OUT + 12, 0xAAAAAA, WHITE);
    }

    // E: an olive ring around a radiation sign; the hovered sector slides out with a hazard stripe running round
    private static void ntm(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        float step = f.step;
        if (base) {
            sh.ring(0, 0, R_IN, R_OUT, c(0x343624, .88F, f), c(0x1E1F15, .9F, f));
            for (int i = 0; i < n; i++) {
                float hv = h[i];
                if (hv < .01F) continue;
                float a = f.ang(i), ox = sx(6 * hv, a), oy = sy(6 * hv, a);
                sh.sector(ox, oy, R_IN, R_OUT - 6, a - step / 2, a + step / 2, c(0x4E5032, .97F * hv, f), c(0x4E5032, .97F * hv, f));
                sh.hazard(ox, oy, R_OUT - 6, R_OUT, a - step / 2, a + step / 2, c(f.acc, hv, f), c(0x10100A, hv, f), f.ms / 60);
            }
            if (n > 1) for (int i = 0; i < n; i++) sh.radial(f.ang(i) + step / 2, R_IN, R_OUT, 1.5F, c(0x000000, .55F, f));
            sh.ring(0, 0, R_OUT - .7F, R_OUT + .7F, c(0x000000, .6F, f), c(0x000000, .6F, f));
            // the trefoil, turning slowly
            sh.disc(0, 0, R_IN - 4, c(f.acc, .95F, f), c(f.acc, .95F, f));
            int dark = c(0x15150E, 1, f);
            sh.disc(0, 0, 2.2F, dark, dark);
            float spin = f.ms / 9000;
            for (int k = 0; k < 3; k++) {
                float b = k * TAU / 3 + spin;
                sh.sector(0, 0, 4, 15, b - .5F, b + .5F, dark, dark);
            }
            return;
        }
        for (int i = 0; i < n; i++) entry(f, i, 41 + 6 * h[i], .25F, i == f.cur ? f.acc : 0xE8E6D6, "", 1);
        caption(f, R_OUT + 12, 0xAAAAAA, GuiShapes.mix(WHITE, f.acc, .5F));
    }

    // F: frosted glass over the blurred world; the hovered sector lifts with a soft shadow and a coloured rim
    private static void glass(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        float step = f.step, gap = n > 1 ? 2 : 0;
        if (base) {
            sh.ring(0, 0, R_IN, R_OUT, c(WHITE, .10F, f), c(WHITE, .20F, f));
            sh.ring(0, 0, R_OUT - 1, R_OUT, c(WHITE, .45F, f), c(WHITE, .45F, f));
            sh.ring(0, 0, R_IN, R_IN + 1, c(WHITE, .25F, f), c(WHITE, .25F, f));
            if (n > 1) for (int i = 0; i < n; i++) sh.radial(f.ang(i) + step / 2, R_IN + 5, R_OUT - 5, 1, c(WHITE, .22F, f));
            for (int i = 0; i < n; i++) {
                float hv = h[i];
                if (hv < .01F) continue;
                float a = f.ang(i), ox = sx(6 * hv, a), oy = sy(6 * hv, a), r1 = R_OUT + 2 * hv;
                float keep = sh.feather;
                sh.feather = 4; // the shadow: soft and a little lower
                sh.petal(ox, oy + 3, R_IN + 2, r1 - 1, a - step / 2, a + step / 2, gap, 3, c(0x000000, .22F * hv, f), c(0x000000, .22F * hv, f));
                sh.feather = keep;
                sh.petal(ox, oy, R_IN + 1, r1, a - step / 2, a + step / 2, gap, 3, c(WHITE, .30F * hv, f), c(WHITE, .38F * hv, f));
                sh.petal(ox, oy, r1 - 2, r1, a - step / 2, a + step / 2, gap, 1, c(f.acc, hv, f), c(f.acc, hv, f));
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            entry(f, i, 42 + 6 * h[i], .25F, WHITE, "", 1);
            if (i == f.cur) { // a small triangle outside, pointing in
                float a = f.ang(i), r = R_OUT + 6 + 6 * h[i];
                int col = c(WHITE, f.in[i], f);
                float tx = sx(r, a), ty = sy(r, a), ux = sx(1, a), uy = sy(1, a), vx = -uy, vy = ux;
                sh.quad(tx - ux * 2.5F, ty - uy * 2.5F, col, tx + ux * 1.5F + vx * 3, ty + uy * 1.5F + vy * 3, col,
                        tx + ux * 1.5F - vx * 3, ty + uy * 1.5F - vy * 3, col, tx - ux * 2.5F, ty - uy * 2.5F, col);
            }
        }
        caption(f, R_OUT + 14, 0xD2D2D2, WHITE);
    }

    // G: just icons round a thin arc; the hovered one grows, its part of the arc thickens in colour, the rest fade
    private static void arc(Frame f, GuiShapes sh, boolean base) {
        int n = f.n;
        float step = f.step, gap = n > 1 ? .06F : 0;
        if (base) {
            sh.ring(0, 0, 0, 49, c(0x000000, .42F, f), c(0x000000, .25F, f));
            sh.ring(0, 0, 49, 70, c(0x000000, .25F, f), c(0x000000, 0, f));
            for (int i = 0; i < n; i++) {
                float hv = h[i], a = f.ang(i), al = f.in[i];
                int col = hv > .01F ? c(GuiShapes.mix(WHITE, f.acc, hv), (.4F + .6F * hv) * al, f) : c(WHITE, .4F * al, f);
                sh.sector(0, 0, R_OUT - 1.5F - 2.5F * hv, R_OUT + .5F + 1.5F * hv, a - step / 2 + gap, a + step / 2 - gap, col, col);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            float dim = 1 - .5F * f.any * (1 - h[i]);
            entry(f, i, 44 + 5 * h[i], .6F, i == f.cur ? f.acc : WHITE, "", dim);
        }
        if (f.hover >= 0) {
            text(f, count(f, f.hover), 0, -12, f.acc, 1, 2);
            ItemStack s = f.items.get(f.hover).ammoStack();
            if (s != null && !s.isEmpty()) {
                String name = s.getHoverName().getString();
                int p = name.lastIndexOf(" ("), q = name.lastIndexOf(')');
                text(f, p > 0 && q > p ? name.substring(p + 2, q) : name, 0, 6, 0xEBEBEB, 1, 1);
            }
        }
    }
}

package acup.gunfeel;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.mixin.GuiGraphicsAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

// Client: flat vector shapes for the GUI (RadialMenu) — quads with a colour per corner, drawn by the GUI's own
// colour pipeline in the current stratum, so items and text of that stratum stay above them. Angles: 0 = up,
// clockwise, radians. Edges along circles get a one-pixel alpha fade against stair steps.
public final class GuiShapes implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private float[] xy = new float[256];
    private int[] col = new int[128];
    private int n; // vertices
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    public float feather = 0.5F; // GUI px of the soft edge

    public GuiShapes(GuiGraphicsExtractor g) {
        pose = new Matrix3x2f(g.pose());
    }

    public void submit(GuiGraphicsExtractor g) {
        if (n > 0) ((GuiGraphicsAccess) g).gunfeel$state().addGuiElement(this);
    }

    private void vertex(float x, float y, int c) {
        if (n * 2 + 2 > xy.length) {
            xy = java.util.Arrays.copyOf(xy, xy.length * 2);
            col = java.util.Arrays.copyOf(col, col.length * 2);
        }
        xy[n * 2] = x;
        xy[n * 2 + 1] = y;
        col[n++] = c;
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
    }

    public void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
        if ((c0 | c1 | c2 | c3) >>> 24 == 0) return;
        // the GUI pipeline culls back faces: keep the winding that line() has (it shows), turn the others round
        float area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3);
        if (area > 0) {
            vertex(x3, y3, c3);
            vertex(x2, y2, c2);
            vertex(x1, y1, c1);
            vertex(x0, y0, c0);
            return;
        }
        vertex(x0, y0, c0);
        vertex(x1, y1, c1);
        vertex(x2, y2, c2);
        vertex(x3, y3, c3);
    }

    public static int argb(int rgb, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return a << 24 | rgb & 0xFFFFFF;
    }

    public static int alpha(int argb, float mul) {
        return argb(argb, (argb >>> 24) / 255F * mul);
    }

    public static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    private static float px(float r, float a) {
        return (float) Math.sin(a) * r;
    }

    private static float py(float r, float a) {
        return (float) -Math.cos(a) * r;
    }

    private static int segs(float r, float span) {
        return Math.max(2, (int) Math.ceil(Math.abs(span) * Math.max(r, 4) / 2.5F));
    }

    // an annular sector (a full ring for span >= 2 pi); colour goes from cIn at r0 to cOut at r1
    public void sector(float ox, float oy, float r0, float r1, float a0, float a1, int cIn, int cOut) {
        int k = segs(r1, a1 - a0);
        float f = feather;
        for (int i = 0; i < k; i++) {
            float b0 = a0 + (a1 - a0) * i / k, b1 = a0 + (a1 - a0) * (i + 1) / k;
            band(ox, oy, r0, r1, b0, b1, cIn, cOut);
            band(ox, oy, r1, r1 + f, b0, b1, cOut, cOut & 0xFFFFFF);
            if (r0 > f) band(ox, oy, r0 - f, r0, b0, b1, cIn & 0xFFFFFF, cIn);
        }
    }

    public void ring(float ox, float oy, float r0, float r1, int cIn, int cOut) {
        sector(ox, oy, r0, r1, 0, (float) (Math.PI * 2), cIn, cOut);
    }

    private void band(float ox, float oy, float r0, float r1, float b0, float b1, int c0, int c1) {
        quad(ox + px(r0, b0), oy + py(r0, b0), c0, ox + px(r1, b0), oy + py(r1, b0), c1,
                ox + px(r1, b1), oy + py(r1, b1), c1, ox + px(r0, b1), oy + py(r0, b1), c0);
    }

    // a filled circle, colour cMid at the centre to cEdge at the rim
    public void disc(float x, float y, float r, int cMid, int cEdge) {
        int k = segs(r, (float) (Math.PI * 2));
        for (int i = 0; i < k; i++) {
            float b0 = (float) (Math.PI * 2 * i / k), b1 = (float) (Math.PI * 2 * (i + 1) / k);
            quad(x, y, cMid, x + px(r, b0), y + py(r, b0), cEdge, x + px(r, b1), y + py(r, b1), cEdge, x, y, cMid);
            band(x, y, r, r + feather, b0, b1, cEdge, cEdge & 0xFFFFFF);
        }
    }

    // a straight line w wide
    public void line(float x0, float y0, float x1, float y1, float w, int c) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-4F) return;
        float nx = -dy / l * w / 2, ny = dx / l * w / 2;
        quad(x0 + nx, y0 + ny, c, x1 + nx, y1 + ny, c, x1 - nx, y1 - ny, c, x0 - nx, y0 - ny, c);
    }

    public void radial(float a, float r0, float r1, float w, int c) {
        line(px(r0, a), py(r0, a), px(r1, a), py(r1, a), w, c);
    }

    // a donut piece with parallel gaps of `gap` px at its sides and corners rounded by `rad` px; colour by radius
    public void petal(float ox, float oy, float r0, float r1, float a0, float a1, float gap, float rad, int cIn, int cOut) {
        if (a1 - a0 >= Math.PI * 2 - 1e-4) {
            sector(ox, oy, r0, r1, 0, (float) (Math.PI * 2), cIn, cOut);
            return;
        }
        float g1 = (float) Math.asin(Math.min(.9, gap / 2 / r1)), g0 = (float) Math.asin(Math.min(.9, gap / 2 / r0));
        Vector2f A = pt(r1, a0 + g1), B = pt(r1, a1 - g1), C = pt(r0, a1 - g0), D = pt(r0, a0 + g0);
        float side = A.distance(D);
        rad = Math.min(rad, Math.min(side / 2.2F, (a1 - a0 - 2 * g0) * r0 / 2.2F));
        int k = segs(r1, a1 - a0) + 8;
        Vector2f[] out = resample(edge(A, D, B, C, r1, a0 + g1, a1 - g1, rad), k);
        Vector2f[] in = resample(edge(D, A, C, B, r0, a0 + g0, a1 - g0, rad), k);
        float f = feather;
        for (int i = 0; i < k - 1; i++) {
            Vector2f i0 = in[i], i1 = in[i + 1], o0 = out[i], o1 = out[i + 1];
            int ci0 = shade(i0, r0, r1, cIn, cOut), ci1 = shade(i1, r0, r1, cIn, cOut), co0 = shade(o0, r0, r1, cIn, cOut), co1 = shade(o1, r0, r1, cIn, cOut);
            quad(ox + i0.x, oy + i0.y, ci0, ox + o0.x, oy + o0.y, co0, ox + o1.x, oy + o1.y, co1, ox + i1.x, oy + i1.y, ci1);
            // soft rim outside and inside, pushed away from the centre / toward it
            quad(ox + o0.x, oy + o0.y, co0, ox + grow(o0, f).x, oy + grow(o0, f).y, co0 & 0xFFFFFF,
                    ox + grow(o1, f).x, oy + grow(o1, f).y, co1 & 0xFFFFFF, ox + o1.x, oy + o1.y, co1);
            quad(ox + grow(i0, -f).x, oy + grow(i0, -f).y, ci0 & 0xFFFFFF, ox + i0.x, oy + i0.y, ci0,
                    ox + i1.x, oy + i1.y, ci1, ox + grow(i1, -f).x, oy + grow(i1, -f).y, ci1 & 0xFFFFFF);
        }
    }

    private static Vector2f pt(float r, float a) {
        return new Vector2f(px(r, a), py(r, a));
    }

    private static Vector2f grow(Vector2f p, float d) {
        float l = p.length();
        return l < 1e-4F ? p : new Vector2f(p).mul((l + d) / l);
    }

    private static int shade(Vector2f p, float r0, float r1, int c0, int c1) {
        float t = r1 > r0 ? Math.max(0, Math.min(1, (p.length() - r0) / (r1 - r0))) : 0;
        int a = Math.round((c0 >>> 24) + ((c1 >>> 24) - (c0 >>> 24)) * t);
        return a << 24 | mix(c0, c1, t);
    }

    // one long edge of a petal, side to side: corner p (other corner along its side: q), arc at r, corner s (side: u)
    private static java.util.List<Vector2f> edge(Vector2f p, Vector2f q, Vector2f s, Vector2f u, float r, float b0, float b1, float rad) {
        java.util.List<Vector2f> l = new java.util.ArrayList<>();
        float o0 = b0 + rad / r, o1 = b1 - rad / r;
        Vector2f start = toward(p, q, rad), end = toward(s, u, rad), arc0 = pt(r, o0), arc1 = pt(r, o1);
        for (int i = 0; i <= 4; i++) l.add(bez(start, p, arc0, i / 4F));
        int k = Math.max(2, (int) Math.ceil(Math.abs(o1 - o0) * r / 2F));
        for (int i = 1; i < k; i++) l.add(pt(r, o0 + (o1 - o0) * i / k));
        for (int i = 0; i <= 4; i++) l.add(bez(arc1, s, end, i / 4F));
        return l;
    }

    private static Vector2f toward(Vector2f p, Vector2f q, float d) {
        float l = p.distance(q);
        return l < 1e-4F ? new Vector2f(p) : new Vector2f(q).sub(p).mul(d / l).add(p);
    }

    private static Vector2f bez(Vector2f a, Vector2f c, Vector2f b, float t) {
        float u = 1 - t;
        return new Vector2f(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y);
    }

    // the polyline as k points evenly spaced along its length
    private static Vector2f[] resample(java.util.List<Vector2f> l, int k) {
        float[] acc = new float[l.size()];
        for (int i = 1; i < l.size(); i++) acc[i] = acc[i - 1] + l.get(i).distance(l.get(i - 1));
        float total = acc[acc.length - 1];
        Vector2f[] out = new Vector2f[k];
        int j = 0;
        for (int i = 0; i < k; i++) {
            float d = total * i / (k - 1);
            while (j < acc.length - 2 && acc[j + 1] < d) j++;
            float seg = acc[j + 1] - acc[j], t = seg < 1e-6F ? 0 : (d - acc[j]) / seg;
            out[i] = new Vector2f(l.get(j)).lerp(l.get(j + 1), Math.max(0, Math.min(1, t)));
        }
        return out;
    }

    // black-and-colour hazard stripes in the band r0..r1 of the sector a0..a1, leaning, sliding along the circle by `shift` px
    public void hazard(float ox, float oy, float r0, float r1, float a0, float a1, int bg, int fg, float shift) {
        sector(ox, oy, r0, r1, a0, a1, bg, bg);
        float rm = (r0 + r1) / 2, pitch = 6, lean = 3;
        float da = pitch / rm, w = da / 2, l = lean / rm;
        float start = a0 - da * 2 + (float) ((shift / rm) % da + da) % da;
        for (float s = start; s < a1 + da; s += da) {
            // stripe: inner edge s..s+w, outer edge shifted by the lean
            float i0 = Math.max(a0, s), i1 = Math.min(a1, s + w), q0 = Math.max(a0, s + l), q1 = Math.min(a1, s + w + l);
            if (i1 <= i0 && q1 <= q0) continue;
            if (i1 < i0) i1 = i0 = Math.min(Math.max(s, a0), a1);
            if (q1 < q0) q1 = q0 = Math.min(Math.max(s + l, a0), a1);
            quad(ox + px(r0, i0), oy + py(r0, i0), fg, ox + px(r1, q0), oy + py(r1, q0), fg,
                    ox + px(r1, q1), oy + py(r1, q1), fg, ox + px(r0, i1), oy + py(r0, i1), fg);
        }
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < n; i++) vc.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(col[i]);
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        if (n == 0) return new ScreenRectangle(0, 0, 0, 0);
        Vector2f a = pose.transformPosition(minX, minY, new Vector2f()), b = pose.transformPosition(maxX, maxY, new Vector2f());
        Vector2f c = pose.transformPosition(minX, maxY, new Vector2f()), d = pose.transformPosition(maxX, minY, new Vector2f());
        float x0 = Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x)), y0 = Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y));
        float x1 = Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x)), y1 = Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y));
        return new ScreenRectangle((int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1 - x0) + 1, (int) Math.ceil(y1 - y0) + 1);
    }
}

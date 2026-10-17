package acup.gunfeel;

import java.util.BitSet;

import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.HFRWavefrontObject;

// Geometry of one NTM gun model, limited to the parts NTM actually draws in first person.
// Model space: +Z toward the muzzle, +Y up.
public final class GunGeometry {
    public final HFRWavefrontObject model;
    public final BitSet parts = new BitSet();
    public float minX, minY, minZ, maxX, maxY, maxZ;
    private boolean sealed;

    public GunGeometry(HFRWavefrontObject model) {
        this.model = model;
    }

    public void addPart(int part) {
        if (!sealed && part >= 0 && part < model.groups.length) parts.set(part);
    }

    // after the first full frame: fix the part set and compute bounds
    public boolean seal() {
        if (sealed) return true;
        if (parts.isEmpty()) return false;
        minX = minY = minZ = Float.MAX_VALUE;
        maxX = maxY = maxZ = -Float.MAX_VALUE;
        for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
            float[] b = model.groups[p].bounds();
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[3]);
            maxY = Math.max(maxY, b[4]);
            maxZ = Math.max(maxZ, b[5]);
        }
        sealed = minX <= maxX;
        return sealed;
    }

    public float length() {
        return maxZ - minZ;
    }

    // Grip point: z = fraction of the length (stock 0 -> muzzle 1); x and y = fractions of the
    // cross-section of the model around that z (y 0 = underside), so hands sit on the surface.
    public float[] grip(float[] f) {
        float[] g = grip(f, 0.04F);
        return g != null ? g : bboxGrip(f);
    }

    private float[] bboxGrip(float[] f) {
        return new float[] {minX + (maxX - minX) * f[0], minY + (maxY - minY) * f[1], minZ + length() * f[2]};
    }

    // Hand positions along the whole gun for x/y fractions (xf, yf): n + 1 points from the stock (0) to the
    // muzzle (1) following the model's surface; z steps without geometry take the nearest neighbour.
    public float[][] profile(float xf, float yf, int n) {
        float[][] out = new float[n + 1][];
        float band = 0.5F / n;
        for (int k = 0; k <= n; k++) out[k] = grip(new float[] {xf, yf, (float) k / n}, band);
        for (int k = 0; k <= n; k++) {
            if (out[k] != null) continue;
            for (int d = 1; d <= n; d++) {
                float[] near = k - d >= 0 && out[k - d] != null && (k - d >= 0) ? out[k - d] : k + d <= n ? out[k + d] : null;
                if (near != null) {
                    out[k] = new float[] {near[0], near[1], minZ + length() * k / n};
                    break;
                }
            }
            if (out[k] == null) out[k] = bboxGrip(new float[] {xf, yf, (float) k / n});
        }
        return out;
    }

    // Centre of the part the hand holds at grip f: across the section at x fraction f[0], and as high above
    // its underside as the section is half wide (a round forend's axis). The gun turns around this
    // point when it rolls inside a loosely holding hand.
    public float[] holdCenter(float[] f) {
        float[] lo = grip(new float[] {0F, 0F, f[2]}), hi = grip(new float[] {1F, 0F, f[2]}), g = grip(f);
        return new float[] {g[0], g[1] + (hi[0] - lo[0]) * 0.5F, g[2]};
    }

    // null when no geometry lies within bandFrac * length of that z
    public float[] grip(float[] f, float bandFrac) {
        float z = minZ + length() * f[2];
        float band = Math.max(length() * bandFrac, 1e-3F);
        float lx = Float.MAX_VALUE, hx = -Float.MAX_VALUE, ly = Float.MAX_VALUE, hy = -Float.MAX_VALUE;
        for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
            GroupObject g = model.groups[p];
            float[] q = g.quads(model.smoothing());
            for (int i = 0; i + 2 < q.length; i += GroupObject.STRIDE) {
                if (Math.abs(q[i + 2] - z) > band) continue;
                lx = Math.min(lx, q[i]);
                hx = Math.max(hx, q[i]);
                ly = Math.min(ly, q[i + 1]);
                hy = Math.max(hy, q[i + 1]);
            }
        }
        if (lx > hx) return null;
        return new float[] {lx + (hx - lx) * f[0], ly + (hy - ly) * f[1], z};
    }
}

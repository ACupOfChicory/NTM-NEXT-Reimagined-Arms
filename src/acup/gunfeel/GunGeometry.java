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
        float z = minZ + length() * f[2];
        float band = Math.max(length() * 0.04F, 1e-3F);
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
        if (lx > hx) { // nothing near this z: fall back to the bounding box
            lx = minX;
            hx = maxX;
            ly = minY;
            hy = maxY;
        }
        return new float[] {lx + (hx - lx) * f[0], ly + (hy - ly) * f[1], z};
    }
}

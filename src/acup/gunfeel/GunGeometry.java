package acup.gunfeel;

import java.util.BitSet;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.HFRWavefrontObject;

// Geometry of one NTM gun model, limited to the parts NTM actually draws in first person.
// Some renderers turn the model before drawing it (its barrel along X, or pointing back); the
// geometry is kept in the gun's own frame ("aligned": +Z toward the muzzle, +Y up, +X to the gun's left),
// found from how NTM places the model while no animation plays (see align).
public final class GunGeometry {
    public final HFRWavefrontObject model;
    // the parts NTM draws for this variant of the gun (a sawn-off draws fewer than the full gun)
    public final BitSet parts;
    public final int variant;
    // the model parts this variant does not draw, "-Barrel-Stock" ("" = all drawn): names the variant in the grip file
    public final String missing;
    public float minX, minY, minZ, maxX, maxY, maxZ;
    // the part that carries the gun's body: the biggest one (others may move in animations)
    public int bodyPart = -1;
    // model space -> aligned frame
    public final Matrix3f toAligned = new Matrix3f();
    // where NTM puts each part at rest relative to the body part (model space), null = with the body
    public final Matrix4f[] partRest;
    private boolean aligned;
    // aligned positions of every drawn quad, 4 vertices x 3 floats each
    private float[] verts = new float[0];

    public GunGeometry(HFRWavefrontObject model, BitSet parts, int variant) {
        this.model = model;
        this.parts = parts;
        this.variant = variant;
        this.partRest = new Matrix4f[model.groups.length];
        StringBuilder name = new StringBuilder();
        for (int p = 0; p < model.groups.length; p++) if (!parts.get(p)) name.append('-').append(model.groups[p].name);
        this.missing = name.toString();
        float best = -1F;
        for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
            float[] b = model.groups[p].bounds();
            float v = Math.max(b[3] - b[0], 1e-3F) * Math.max(b[4] - b[1], 1e-3F) * Math.max(b[5] - b[2], 1e-3F);
            if (v > best) {
                best = v;
                bodyPart = p;
            }
        }
    }

    public boolean aligned() {
        return aligned;
    }

    // Fix the aligned frame from the body's rotation relative to the gun frame (snapped to whole quarter turns).
    public void align(Matrix3f bodyToGun) {
        if (aligned) return;
        Matrix3f r = new Matrix3f();
        for (int c = 0; c < 3; c++) {
            Vector3f col = bodyToGun.getColumn(c, new Vector3f());
            int k = Math.abs(col.x) >= Math.abs(col.y) && Math.abs(col.x) >= Math.abs(col.z) ? 0 : Math.abs(col.y) >= Math.abs(col.z) ? 1 : 2;
            float sign = Math.signum(col.get(k));
            Vector3f snapped = new Vector3f();
            snapped.setComponent(k, sign == 0F ? 1F : sign);
            r.setColumn(c, snapped);
        }
        if (Math.abs(r.determinant()) < 0.5F) r.identity(); // two columns snapped to the same axis
        toAligned.set(r);
        int n = 0;
        for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) n += model.groups[p].quads(model.smoothing()).length / GroupObject.STRIDE;
        verts = new float[n * 3];
        int o = 0;
        Vector3f v = new Vector3f();
        minX = minY = minZ = Float.MAX_VALUE;
        maxX = maxY = maxZ = -Float.MAX_VALUE;
        for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
            float[] q = model.groups[p].quads(model.smoothing());
            for (int i = 0; i + 2 < q.length; i += GroupObject.STRIDE) {
                r.transform(v.set(q[i], q[i + 1], q[i + 2]));
                verts[o++] = v.x;
                verts[o++] = v.y;
                verts[o++] = v.z;
                minX = Math.min(minX, v.x);
                minY = Math.min(minY, v.y);
                minZ = Math.min(minZ, v.z);
                maxX = Math.max(maxX, v.x);
                maxY = Math.max(maxY, v.y);
                maxZ = Math.max(maxZ, v.z);
            }
        }
        aligned = minX <= maxX;
    }

    public float length() {
        return maxZ - minZ;
    }

    public float fraction(float z) {
        return length() > 0F ? (z - minZ) / length() : 0.5F;
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

    // x/y fractions of a point (aligned frame) within the section at its z: {xf, yf, zf}
    public float[] fractions(float[] at) {
        float[] s = section(at[2], Math.max(length() * 0.04F, 1e-3F));
        if (s == null) return new float[] {0.5F, 0F, fraction(at[2])};
        return new float[] {s[1] > s[0] ? (at[0] - s[0]) / (s[1] - s[0]) : 0.5F, s[3] > s[2] ? (at[1] - s[2]) / (s[3] - s[2]) : 0F, fraction(at[2])};
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
                float[] near = k - d >= 0 && out[k - d] != null ? out[k - d] : k + d <= n ? out[k + d] : null;
                if (near != null) {
                    out[k] = new float[] {near[0], near[1], minZ + length() * k / n};
                    break;
                }
            }
            if (out[k] == null) out[k] = bboxGrip(new float[] {xf, yf, (float) k / n});
        }
        return out;
    }

    // Centre of the part the hand holds at point g: as high above it as the section there is half wide
    // (a round forend's axis). The gun turns around this point when it rolls inside a loosely holding hand.
    public float[] holdCenter(float[] g) {
        float[] s = section(g[2], Math.max(length() * 0.04F, 1e-3F));
        float half = s != null ? (s[1] - s[0]) * 0.5F : 0F;
        return new float[] {g[0], g[1] + half, g[2]};
    }

    // cross-section of the gun around z: {minX, maxX, minY, maxY}, or null where there is none
    public float[] sectionAt(float z) {
        return section(z, Math.max(length() * 0.04F, 1e-3F));
    }

    // null when no geometry lies within bandFrac * length of that z
    public float[] grip(float[] f, float bandFrac) {
        float z = minZ + length() * f[2];
        float[] s = section(z, Math.max(length() * bandFrac, 1e-3F));
        if (s == null) return null;
        return new float[] {s[0] + (s[1] - s[0]) * f[0], s[2] + (s[3] - s[2]) * f[1], z};
    }

    // Cross-section around z: vertices within band, plus the points where quad edges cross z (long faces
    // such as a barrel have vertices only at their ends). {minX, maxX, minY, maxY} or null.
    private float[] section(float z, float band) {
        float lx = Float.MAX_VALUE, hx = -Float.MAX_VALUE, ly = Float.MAX_VALUE, hy = -Float.MAX_VALUE;
        float[] v = verts;
        for (int i = 0; i + 11 < v.length; i += 12) {
            for (int k = 0; k < 4; k++) {
                int a = i + k * 3, b = i + (k + 1) % 4 * 3;
                float za = v[a + 2], zb = v[b + 2];
                if (Math.abs(za - z) <= band) {
                    lx = Math.min(lx, v[a]);
                    hx = Math.max(hx, v[a]);
                    ly = Math.min(ly, v[a + 1]);
                    hy = Math.max(hy, v[a + 1]);
                }
                if ((za - z) * (zb - z) < 0F) {
                    float t = (z - za) / (zb - za);
                    float x = v[a] + (v[b] - v[a]) * t, y = v[a + 1] + (v[b + 1] - v[a + 1]) * t;
                    lx = Math.min(lx, x);
                    hx = Math.max(hx, x);
                    ly = Math.min(ly, y);
                    hy = Math.max(hy, y);
                }
            }
        }
        return lx > hx ? null : new float[] {lx, hx, ly, hy};
    }
}

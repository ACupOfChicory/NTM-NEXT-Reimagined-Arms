package acup.gunfeel;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

// Client: the triangles near a hole sorted into a grid of boxes, so a line looks only at those in the boxes it
// passes through instead of at all of them. It only picks which are looked at: the test on each stays the caller's,
// and every triangle whose bounds the line's bounds meet is still among them
final class TriGrid {
    private final List<RenderedMesh.Tri> tris;
    private final double[] bounds;
    private final double x0, y0, z0, cs;
    private final int nx, ny, nz;
    private final int[][] cells;
    private final int[] seen;
    private int stamp;

    TriGrid(List<RenderedMesh.Tri> tris) {
        this.tris = tris;
        int n = tris.size();
        bounds = new double[n * 6];
        double ax = Double.MAX_VALUE, ay = ax, az = ax, bx = -ax, by = bx, bz = bx;
        for (int i = 0; i < n; i++) {
            double[] p = tris.get(i).p();
            double lx = Math.min(p[0], Math.min(p[3], p[6])), hx = Math.max(p[0], Math.max(p[3], p[6]));
            double ly = Math.min(p[1], Math.min(p[4], p[7])), hy = Math.max(p[1], Math.max(p[4], p[7]));
            double lz = Math.min(p[2], Math.min(p[5], p[8])), hz = Math.max(p[2], Math.max(p[5], p[8]));
            int o = i * 6;
            bounds[o] = lx; bounds[o + 1] = hx; bounds[o + 2] = ly; bounds[o + 3] = hy; bounds[o + 4] = lz; bounds[o + 5] = hz;
            ax = Math.min(ax, lx); ay = Math.min(ay, ly); az = Math.min(az, lz);
            bx = Math.max(bx, hx); by = Math.max(by, hy); bz = Math.max(bz, hz);
        }
        if (n == 0) ax = ay = az = bx = by = bz = 0;
        double e = 1e-3;
        x0 = ax - e;
        y0 = ay - e;
        z0 = az - e;
        double ext = Math.max(bx - ax, Math.max(by - ay, bz - az)) + 2 * e;
        cs = Math.max(ext / 32, 1.0 / 32);
        nx = Math.max(1, (int) Math.ceil((bx - ax + 2 * e) / cs));
        ny = Math.max(1, (int) Math.ceil((by - ay + 2 * e) / cs));
        nz = Math.max(1, (int) Math.ceil((bz - az + 2 * e) / cs));
        int[] count = new int[nx * ny * nz];
        for (int pass = 0; pass < 2; pass++) {
            for (int t = 0; t < n; t++) {
                int o = t * 6;
                int i0 = cx(bounds[o] - e), i1 = cx(bounds[o + 1] + e), j0 = cy(bounds[o + 2] - e), j1 = cy(bounds[o + 3] + e);
                int k0 = cz(bounds[o + 4] - e), k1 = cz(bounds[o + 5] + e);
                for (int a = i0; a <= i1; a++) for (int b = j0; b <= j1; b++) for (int c = k0; c <= k1; c++) {
                    int id = (a * ny + b) * nz + c;
                    if (pass == 0) count[id]++;
                    else cellsTmp[id][count[id]++] = t;
                }
            }
            if (pass == 0) {
                cellsTmp = new int[count.length][];
                for (int id = 0; id < count.length; id++) cellsTmp[id] = new int[count[id]];
                java.util.Arrays.fill(count, 0);
            }
        }
        cells = cellsTmp;
        seen = new int[n];
    }

    private int[][] cellsTmp;

    private int cx(double x) {
        return Math.clamp((int) Math.floor((x - x0) / cs), 0, nx - 1);
    }

    private int cy(double y) {
        return Math.clamp((int) Math.floor((y - y0) / cs), 0, ny - 1);
    }

    private int cz(double z) {
        return Math.clamp((int) Math.floor((z - z0) / cs), 0, nz - 1);
    }

    private boolean boundsMeet(int i, double lx, double hx, double ly, double hy, double lz, double hz) {
        int o = i * 6;
        return bounds[o + 1] >= lx && bounds[o] <= hx && bounds[o + 3] >= ly && bounds[o + 2] <= hy && bounds[o + 5] >= lz && bounds[o + 4] <= hz;
    }

    private void next() {
        if (++stamp == Integer.MAX_VALUE) {
            java.util.Arrays.fill(seen, 0);
            stamp = 1;
        }
    }

    // a triangle whose bounds meet the box and that passes the test
    boolean anyIn(AABB box, Predicate<RenderedMesh.Tri> test) {
        next();
        int i0 = cx(box.minX), i1 = cx(box.maxX), j0 = cy(box.minY), j1 = cy(box.maxY), k0 = cz(box.minZ), k1 = cz(box.maxZ);
        for (int a = i0; a <= i1; a++) for (int b = j0; b <= j1; b++) for (int c = k0; c <= k1; c++) {
            for (int t : cells[(a * ny + b) * nz + c]) {
                if (seen[t] == stamp) continue;
                seen[t] = stamp;
                if (boundsMeet(t, box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ) && test.test(tris.get(t))) return true;
            }
        }
        return false;
    }

    // the first triangle (in the order the line meets the boxes) whose bounds meet the bounds of the line from a to
    // b and that passes the test; null = none
    RenderedMesh.Tri first(Vector3d a, Vector3d b, Predicate<RenderedMesh.Tri> test) {
        int[] got = {-1};
        each(a, b, t -> {
            if (!test.test(tris.get(t))) return false;
            got[0] = t;
            return true;
        });
        return got[0] < 0 ? null : tris.get(got[0]);
    }

    // each triangle (its index in the list) whose bounds meet the bounds of the line from a to b, once, until visit
    // says stop
    void each(Vector3d a, Vector3d b, java.util.function.IntPredicate visit) {
        next();
        double lx = Math.min(a.x, b.x), hx = Math.max(a.x, b.x), ly = Math.min(a.y, b.y), hy = Math.max(a.y, b.y);
        double lz = Math.min(a.z, b.z), hz = Math.max(a.z, b.z);
        // the part of the line inside the grid; then walk the boxes it passes (Amanatides & Woo): a meeting point
        // lies in one of them (each triangle is in every box its bounds, grown by 1e-3, touch)
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double t0 = 0, t1 = 1;
        double[][] slab = {{a.x, dx, x0, x0 + nx * cs}, {a.y, dy, y0, y0 + ny * cs}, {a.z, dz, z0, z0 + nz * cs}};
        for (double[] sl : slab) {
            if (Math.abs(sl[1]) < 1e-15) {
                if (sl[0] < sl[2] || sl[0] > sl[3]) return;
                continue;
            }
            double ta = (sl[2] - sl[0]) / sl[1], tb = (sl[3] - sl[0]) / sl[1];
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
        }
        if (t0 > t1) return;
        double sx = a.x + dx * t0, sy = a.y + dy * t0, sz = a.z + dz * t0;
        int i = cx(sx), j = cy(sy), k = cz(sz);
        int ie = cx(a.x + dx * t1), je = cy(a.y + dy * t1), ke = cz(a.z + dz * t1);
        int si = dx > 0 ? 1 : dx < 0 ? -1 : 0, sj = dy > 0 ? 1 : dy < 0 ? -1 : 0, sk = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = si != 0 ? cs / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tdy = sj != 0 ? cs / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tdz = sk != 0 ? cs / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tmx = si > 0 ? (x0 + (i + 1) * cs - a.x) / dx : si < 0 ? (x0 + i * cs - a.x) / dx : Double.POSITIVE_INFINITY;
        double tmy = sj > 0 ? (y0 + (j + 1) * cs - a.y) / dy : sj < 0 ? (y0 + j * cs - a.y) / dy : Double.POSITIVE_INFINITY;
        double tmz = sk > 0 ? (z0 + (k + 1) * cs - a.z) / dz : sk < 0 ? (z0 + k * cs - a.z) / dz : Double.POSITIVE_INFINITY;
        int guard = nx + ny + nz + 3;
        while (true) {
            if (cellEach(i, j, k, lx, hx, ly, hy, lz, hz, visit)) return;
            if ((i == ie && j == je && k == ke) || --guard < 0) break;
            if (tmx <= tmy && tmx <= tmz) {
                i += si;
                tmx += tdx;
            } else if (tmy <= tmz) {
                j += sj;
                tmy += tdy;
            } else {
                k += sk;
                tmz += tdz;
            }
            if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) break;
        }
    }

    private boolean cellEach(int i, int j, int k, double lx, double hx, double ly, double hy, double lz, double hz, java.util.function.IntPredicate visit) {
        for (int t : cells[(i * ny + j) * nz + k]) {
            if (seen[t] == stamp) continue;
            seen[t] = stamp;
            if (boundsMeet(t, lx, hx, ly, hy, lz, hz) && visit.test(t)) return true;
        }
        return false;
    }
}

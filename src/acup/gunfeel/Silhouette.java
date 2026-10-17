package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

// Flat side view of an NTM gun for the HUD, like TaCZ's hud icons: the drawn parts of the
// first-person model rasterised on the CPU (orthographic, looking along -X of the aligned frame, muzzle to the right),
// light grey-white with simple diffuse shading, 2x supersampled.
public final class Silhouette {
    public static final int W = 192, H = 64;
    private static final int SS = 2;
    private static final Map<GunGeometry, Identifier> TEXTURES = new IdentityHashMap<>();

    private Silhouette() {
    }

    // null until the gun has been drawn once in first person
    public static Identifier get(Item item) {
        GunGeometry geo = Arms.geometry(item);
        if (geo == null) return null;
        Identifier id = TEXTURES.get(geo);
        if (id != null) return id;
        Identifier key = BuiltInRegistries.ITEM.getKey(item);
        id = Identifier.fromNamespaceAndPath("ntmgunfeel", "hud/" + key.getNamespace() + "/" + key.getPath() + (geo.variant > 0 ? "_" + geo.variant : ""));
        NativeImage image = render(geo);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
        TEXTURES.put(geo, id);
        return id;
    }

    private static NativeImage render(GunGeometry geo) {
        int w = W * SS, h = H * SS;
        float[] depth = new float[w * h];
        int[] shade = new int[w * h];
        java.util.Arrays.fill(depth, -Float.MAX_VALUE);
        // bounds of the parts as placed
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int p = geo.parts.nextSetBit(0); p >= 0; p = geo.parts.nextSetBit(p + 1)) {
            float[] q = geo.model.groups[p].quads(false);
            org.joml.Matrix4f place = place(geo, p);
            for (int v = 0; v + 2 < q.length; v += GroupObject.STRIDE) {
                org.joml.Vector3f a = place.transformPosition(new org.joml.Vector3f(q[v], q[v + 1], q[v + 2]));
                minY = Math.min(minY, a.y);
                maxY = Math.max(maxY, a.y);
                minZ = Math.min(minZ, a.z);
                maxZ = Math.max(maxZ, a.z);
            }
        }
        if (minY > maxY) return new NativeImage(W, H, true);
        float len = Math.max(maxZ - minZ, 1e-3F), tall = Math.max(maxY - minY, 1e-3F);
        float pad = 2F * SS;
        float scale = Math.min((w - 2 * pad) / len, (h - 2 * pad) / tall);
        float ox = (w - len * scale) / 2F, oy = (h - tall * scale) / 2F;
        // light from the viewer's upper left, slightly toward the screen
        float lx = 0.55F, ly = 0.75F, lz = -0.35F;

        for (int p = geo.parts.nextSetBit(0); p >= 0; p = geo.parts.nextSetBit(p + 1)) {
            float[] q = geo.model.groups[p].quads(false);
            org.joml.Matrix4f place = place(geo, p);
            for (int f = 0; f + 4 * GroupObject.STRIDE <= q.length; f += 4 * GroupObject.STRIDE) {
                org.joml.Vector3f n = place.transformDirection(new org.joml.Vector3f(q[f + 5], q[f + 6], q[f + 7])).normalize();
                float nx = n.x, ny = n.y, nz = n.z;
                float diffuse = Math.abs(nx * lx + ny * ly + nz * lz);
                int grey = (int) (150 + 105 * Math.min(1F, diffuse));
                float[] sx = new float[4], sy = new float[4], sd = new float[4];
                for (int c = 0; c < 4; c++) {
                    int v = f + c * GroupObject.STRIDE;
                    org.joml.Vector3f a = place.transformPosition(new org.joml.Vector3f(q[v], q[v + 1], q[v + 2]));
                    sx[c] = ox + (a.z - minZ) * scale;
                    sy[c] = oy + (maxY - a.y) * scale;
                    sd[c] = a.x;
                }
                tri(sx, sy, sd, 0, 1, 2, grey, depth, shade, w, h);
                tri(sx, sy, sd, 0, 2, 3, grey, depth, shade, w, h);
            }
        }

        NativeImage image = new NativeImage(W, H, true);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int a = 0, g = 0;
                for (int dy = 0; dy < SS; dy++) {
                    for (int dx = 0; dx < SS; dx++) {
                        int i = (y * SS + dy) * w + x * SS + dx;
                        if (shade[i] != 0) {
                            a++;
                            g += shade[i];
                        }
                    }
                }
                int n = SS * SS;
                int grey = a == 0 ? 0 : g / a;
                int alpha = a * 255 / n;
                image.setPixel(x, y, alpha << 24 | grey << 16 | grey << 8 | grey);
            }
        }
        return image;
    }

    // the part where NTM draws it at rest (e.g. a shell sunk into the receiver), in the aligned frame
    private static org.joml.Matrix4f place(GunGeometry geo, int part) {
        org.joml.Matrix4f m = new org.joml.Matrix4f().set(geo.toAligned);
        return geo.partRest[part] != null ? m.mul(geo.partRest[part]) : m;
    }

    private static void tri(float[] x, float[] y, float[] d, int a, int b, int c, int grey, float[] depth, int[] shade, int w, int h) {
        float area = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]);
        if (Math.abs(area) < 1e-6F) return;
        int x0 = Math.max(0, (int) Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
        int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
        int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float cx = px + 0.5F, cy = py + 0.5F;
                float w0 = ((x[b] - cx) * (y[c] - cy) - (x[c] - cx) * (y[b] - cy)) / area;
                float w1 = ((x[c] - cx) * (y[a] - cy) - (x[a] - cx) * (y[c] - cy)) / area;
                float w2 = 1F - w0 - w1;
                if (w0 < 0F || w1 < 0F || w2 < 0F) continue;
                float z = w0 * d[a] + w1 * d[b] + w2 * d[c];
                int i = py * w + px;
                if (z > depth[i]) {
                    depth[i] = z;
                    shade[i] = grey;
                }
            }
        }
    }
}

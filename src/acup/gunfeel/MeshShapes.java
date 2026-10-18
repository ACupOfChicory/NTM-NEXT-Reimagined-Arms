package acup.gunfeel;

import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

// Client: the 3D shapes bullets meet in blocks whose model sits inside their hitbox (NTM's steel scaffold and
// poles). The server has no models, so on joining a world the client turns the models of the blocks listed under
// "generate" in ntmgunfeel-bullet-shapes.properties into 16 x 16 x 16 cells (set where the model shows a pixel) and
// writes them to config/ntm-gunfeel-bullet-shapes.properties when they differ from the jar's; copied into the jar's
// file they reach the server.
public final class MeshShapes {
    private static boolean done;

    private MeshShapes() {
    }

    public static List<Block> listed() {
        List<Block> out = new ArrayList<>();
        try (var in = MeshShapes.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String id : p.getProperty("generate", "").split(",")) {
                Identifier i = Identifier.tryParse(id.trim());
                if (i != null) BuiltInRegistries.BLOCK.getOptional(i).ifPresent(out::add);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not read the bullet shape list", e);
        }
        return out;
    }

    public static void generate() {
        if (done) return;
        done = true;
        long start = System.nanoTime();
        Map<String, long[]> made = new TreeMap<>();
        // states drawn with the same model (powered, waterlogged) share its cells
        Map<Object, long[]> byModel = new java.util.IdentityHashMap<>();
        boolean differs = false;
        for (Block block : listed()) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                long[] bits = byModel.computeIfAbsent(Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state), m -> cells(state));
                String key = PassableClip.key(state);
                long[] had = PassableClip.shape(state);
                if (had == null || !java.util.Arrays.equals(had, bits)) differs = true;
                made.put(key, bits);
            }
        }
        if (made.isEmpty()) return;
        GunFeel.LOGGER.info("Made {} bullet shapes ({} models) in {} ms", made.size(), byModel.size(), (System.nanoTime() - start) / 1_000_000);
        PassableClip.putShapes(made);
        if (!differs) return;
        Path f = FMLPaths.CONFIGDIR.get().resolve(PassableClip.SHAPES_FILE);
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("# made by NTM Gun Feel from this client's block models: 16x16x16 cells a bullet meets, bit x+16*(y+16*z),\n");
            w.write("# deflated and in base64 after z; states with the same cells point to one @n\n");
            Map<String, String> ids = new java.util.LinkedHashMap<>();
            Map<String, Integer> uses = new TreeMap<>();
            for (long[] bits : made.values()) uses.merge(PassableClip.pack(bits), 1, Integer::sum);
            for (var e : uses.entrySet()) if (e.getValue() > 1) ids.put(e.getKey(), "@" + ids.size());
            for (var e : ids.entrySet()) w.write(e.getValue() + " = " + e.getKey() + "\n");
            for (var e : made.entrySet()) {
                String packed = PassableClip.pack(e.getValue());
                w.write(e.getKey().replace(":", "\\:").replace("=", "\\=") + " = " + ids.getOrDefault(packed, packed) + "\n");
            }
            GunFeel.LOGGER.info("Wrote {} bullet shapes to {}", made.size(), f);
        } catch (Exception e) {
            GunFeel.LOGGER.warn("Could not write {}", f, e);
        }
    }

    // all of the model's faces, culled or not
    public static List<BakedQuad> quads(BlockState state) {
        List<BakedQuad> out = new ArrayList<>();
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
        for (var part : parts) {
            for (Direction d : Direction.values()) out.addAll(part.getQuads(d));
            out.addAll(part.getQuads(null));
        }
        return out;
    }

    private static long[] cells(BlockState state) {
        long[] bits = new long[65];
        List<BakedQuad> quads = quads(state);
        if (shell(state, quads)) bits[64] = 1L;
        for (BakedQuad q : quads) {
            for (int t = 0; t < 2; t++) {
                int a = 0, b = t == 0 ? 1 : 2, c = t == 0 ? 2 : 3;
                var pa = q.position(a); var pb = q.position(b); var pc = q.position(c);
                double edge = Math.max(dist(pa, pb), Math.max(dist(pb, pc), dist(pa, pc)));
                int n = Math.max(2, (int) Math.ceil(edge * 64));
                for (int i = 0; i <= n; i++) {
                    for (int j = 0; j <= n - i; j++) {
                        double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                        double x = wa * pa.x() + wb * pb.x() + wc * pc.x();
                        double y = wa * pa.y() + wb * pb.y() + wc * pc.y();
                        double z = wa * pa.z() + wb * pb.z() + wc * pc.z();
                        float u = (float) (wa * UVPair.unpackU(q.packedUV(a)) + wb * UVPair.unpackU(q.packedUV(b)) + wc * UVPair.unpackU(q.packedUV(c)));
                        float v = (float) (wa * UVPair.unpackV(q.packedUV(a)) + wb * UVPair.unpackV(q.packedUV(b)) + wc * UVPair.unpackV(q.packedUV(c)));
                        if (!opaque(q.materialInfo().sprite(), u, v)) continue;
                        int cx = (int) Math.floor(x * 16), cy = (int) Math.floor(y * 16), cz = (int) Math.floor(z * 16);
                        if (x >= 1.0 && x < 1.0 + 1e-3) cx = 15;
                        if (y >= 1.0 && y < 1.0 + 1e-3) cy = 15;
                        if (z >= 1.0 && z < 1.0 + 1e-3) cz = 15;
                        if (cx < 0 || cx > 15 || cy < 0 || cy > 15 || cz < 0 || cz > 15) continue;
                        int k = cx + 16 * (cy + 16 * cz);
                        bits[k >> 6] |= 1L << (k & 63);
                    }
                }
            }
        }
        return bits;
    }

    // The model's faces (shown pixels or not) cover every face of the block's hitbox: a closed box such as a door,
    // a trapdoor, a lantern or copper grate, entered only through its outer faces. Not: iron bars, a ladder, chains,
    // scaffold poles, whose model lies inside the hitbox.
    static boolean shell(BlockState state, List<BakedQuad> quads) {
        var boxes = state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).toAabbs();
        if (boxes.isEmpty()) return false;
        int points = 0, covered = 0;
        for (var b : boxes) {
            double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ};
            for (int ax = 0; ax < 3; ax++) {
                int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
                for (double plane : new double[] {lo[ax], hi[ax]}) {
                    int facePoints = 0, faceCovered = 0;
                    for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                        double[] p = new double[3];
                        p[ax] = plane;
                        p[a1] = lo[a1] + (hi[a1] - lo[a1]) * (i + 0.5) / 4;
                        p[a2] = lo[a2] + (hi[a2] - lo[a2]) * (j + 0.5) / 4;
                        // a face shared with another of the boxes is inside the hitbox
                        if (inOther(boxes, b, p, ax)) continue;
                        facePoints++;
                        if (onAnyQuad(quads, p, ax)) faceCovered++;
                    }
                    // a bare face on the block's edge is a seam to the other half
                    // (door halves have no face where they meet)
                    boolean edge = Math.abs(plane) < 1e-4 || Math.abs(plane - 1) < 1e-4;
                    if (edge && faceCovered == 0) continue;
                    points += facePoints;
                    covered += faceCovered;
                }
            }
        }
        return points > 0 && covered >= points * 0.95;
    }

    private static boolean inOther(List<net.minecraft.world.phys.AABB> boxes, net.minecraft.world.phys.AABB self, double[] p, int ax) {
        for (var o : boxes) {
            if (o == self) continue;
            double[] lo = {o.minX, o.minY, o.minZ}, hi = {o.maxX, o.maxY, o.maxZ};
            boolean in = true;
            for (int i = 0; i < 3; i++) in &= p[i] >= lo[i] - 1e-4 && p[i] <= hi[i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    // an axis-aligned quad of the model lies in that plane over the point
    private static boolean onAnyQuad(List<BakedQuad> quads, double[] p, int ax) {
        for (BakedQuad q : quads) {
            double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int k = 0; k < 4; k++) {
                var v = q.position(k);
                double[] c = {v.x(), v.y(), v.z()};
                for (int i = 0; i < 3; i++) {
                    mn[i] = Math.min(mn[i], c[i]);
                    mx[i] = Math.max(mx[i], c[i]);
                }
            }
            if (mx[ax] - mn[ax] > 1e-4 || Math.abs(mn[ax] - p[ax]) > 1e-3) continue;
            boolean in = true;
            for (int i = 0; i < 3; i++) if (i != ax) in &= p[i] >= mn[i] - 1e-4 && p[i] <= mx[i] + 1e-4;
            if (in) return true;
        }
        return false;
    }

    private static double dist(org.joml.Vector3fc a, org.joml.Vector3fc b) {
        return Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y()) + (a.z() - b.z()) * (a.z() - b.z()));
    }

    // the sprite's pixel at atlas coordinates (u, v) is shown
    public static boolean opaque(TextureAtlasSprite sprite, float u, float v) {
        var contents = sprite.contents();
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        if (du == 0F || dv == 0F) return true;
        int px = Math.clamp((int) ((u - sprite.getU0()) / du * contents.width()), 0, contents.width() - 1);
        int py = Math.clamp((int) ((v - sprite.getV0()) / dv * contents.height()), 0, contents.height() - 1);
        return !contents.isTransparent(0, px, py);
    }
}

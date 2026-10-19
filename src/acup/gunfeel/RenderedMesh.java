package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState;
import net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group;
import net.minecraft.client.renderer.item.ItemStackRenderState.FoilType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// Client: the triangles that block entity renderers draw (NTM's machines and doors are OBJ models drawn by their
// renderer, not block models; their other blocks are invisible). A block entity's renderer is run into a recorder
// instead of the screen, the same way the game runs it each frame, so any mod's renderer drawing through
// submitCustomGeometry, submitModel or submitBlockModel is covered, in whatever pose it is in right now (a door half
// open, a press head down).
public final class RenderedMesh {
    private RenderedMesh() {
    }

    // what a texture shows at (u, v)
    public interface Tex {
        boolean opaque(float u, float v);

        // it has see-through pixels anywhere
        boolean seeThrough();

        // its pixel grid in its (u, v): {u of a pixel edge, pixel width, v of a pixel edge, pixel height}; null =
        // not known (holes are then cut on a grid of their own)
        default double @Nullable [] grid() {
            return null;
        }

        // glass: holes cover it whole, and what lies behind it shows through (its holes are drawn after the glass)
        default boolean glass() {
            return false;
        }

        // the pixel's colour, ARGB (0 = not known): bullet crumbs on a model drawn with its own texture
        default int color(float u, float v) {
            return 0;
        }
    }

    public static final Tex OPAQUE = new Tex() {
        public boolean opaque(float u, float v) {
            return true;
        }

        public boolean seeThrough() {
            return false;
        }
    };

    // glass (a glass block, a door's window of clear pixels): taken as solid, so holes stay on it
    public static final Tex GLASS_TEX = new Tex() {
        public boolean opaque(float u, float v) {
            return true;
        }

        public boolean seeThrough() {
            return false;
        }

        public boolean glass() {
            return true;
        }
    };

    // a drawn triangle: corners in world space, their texture coordinates, its texture; oneSided = drawn with back
    // faces culled; moving = drawn by a renderer (may move: a door's leaf), not a block model; anchor = the part of
    // the renderer it belongs to and that part's pose then (null: a block model, or a part that can not be followed)
    // block = the block whose model it is (null: drawn by a renderer)
    public record Tri(double[] p, float[] uv, Tex tex, boolean oneSided, boolean moving, @Nullable Anchor anchor, @Nullable BlockPos block) {
        public Vector3d corner(int i) {
            return new Vector3d(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        }

        public @Nullable Vector3d normal() {
            Vector3d a = corner(0), n = corner(1).sub(a).cross(corner(2).sub(a));
            return n.lengthSquared() < 1e-14 ? null : n.normalize();
        }

        public boolean meets(AABB box) {
            double x0 = Math.min(p[0], Math.min(p[3], p[6])), x1 = Math.max(p[0], Math.max(p[3], p[6]));
            double y0 = Math.min(p[1], Math.min(p[4], p[7])), y1 = Math.max(p[1], Math.max(p[4], p[7]));
            double z0 = Math.min(p[2], Math.min(p[5], p[8])), z1 = Math.max(p[2], Math.max(p[5], p[8]));
            return x1 >= box.minX && x0 <= box.maxX && y1 >= box.minY && y0 <= box.maxY && z1 >= box.minZ && z0 <= box.maxZ;
        }

        // barycentric weights of a point in the triangle's plane (null when outside it, by more than eps)
        public double @Nullable [] weights(Vector3d pt, double eps) {
            // as JOML's sub and dot work them out (fused multiply-adds, the same order), without new vectors
            double ax = p[0], ay = p[1], az = p[2];
            double e0x = p[3] - ax, e0y = p[4] - ay, e0z = p[5] - az, e1x = p[6] - ax, e1y = p[7] - ay, e1z = p[8] - az;
            double e2x = pt.x - ax, e2y = pt.y - ay, e2z = pt.z - az;
            double d00 = dot(e0x, e0y, e0z, e0x, e0y, e0z), d01 = dot(e0x, e0y, e0z, e1x, e1y, e1z), d11 = dot(e1x, e1y, e1z, e1x, e1y, e1z);
            double d20 = dot(e2x, e2y, e2z, e0x, e0y, e0z), d21 = dot(e2x, e2y, e2z, e1x, e1y, e1z);
            double den = d00 * d11 - d01 * d01;
            if (Math.abs(den) < 1e-18) return null;
            double wb = (d11 * d20 - d01 * d21) / den, wc = (d00 * d21 - d01 * d20) / den, wa = 1 - wb - wc;
            if (wa < -eps || wb < -eps || wc < -eps) return null;
            return new double[] {wa, wb, wc};
        }

        private static double dot(double x, double y, double z, double vx, double vy, double vz) {
            return org.joml.Math.fma(x, vx, org.joml.Math.fma(y, vy, z * vz));
        }

        public boolean opaqueAt(double[] w) {
            float u = (float) (w[0] * uv[0] + w[1] * uv[2] + w[2] * uv[4]);
            float v = (float) (w[0] * uv[1] + w[1] * uv[3] + w[2] * uv[5]);
            return tex.opaque(u, v);
        }

        // where the line from o along d meets it, as a distance along d; NaN = it does not
        public double cross(Vector3d o, Vector3d d) {
            double ax = p[0], ay = p[1], az = p[2];
            double e1x = p[3] - ax, e1y = p[4] - ay, e1z = p[5] - az, e2x = p[6] - ax, e2y = p[7] - ay, e2z = p[8] - az;
            // h = d x e2 (JOML's cross: fma(y, v.z, -z * v.y) ...)
            double hx = org.joml.Math.fma(d.y, e2z, -d.z * e2y), hy = org.joml.Math.fma(d.z, e2x, -d.x * e2z), hz = org.joml.Math.fma(d.x, e2y, -d.y * e2x);
            double det = dot(e1x, e1y, e1z, hx, hy, hz);
            if (Math.abs(det) < 1e-12) return Double.NaN;
            double f = 1 / det;
            double sx = o.x - ax, sy = o.y - ay, sz = o.z - az;
            double u = f * dot(sx, sy, sz, hx, hy, hz);
            if (u < -1e-6 || u > 1 + 1e-6) return Double.NaN;
            double qx = org.joml.Math.fma(sy, e1z, -sz * e1y), qy = org.joml.Math.fma(sz, e1x, -sx * e1z), qz = org.joml.Math.fma(sx, e1y, -sy * e1x);
            double v = f * dot(d.x, d.y, d.z, qx, qy, qz);
            if (v < -1e-6 || u + v > 1 + 1e-6) return Double.NaN;
            return f * dot(e2x, e2y, e2z, qx, qy, qz);
        }
    }

    private record Got(long at, List<Tri> tris) {
    }

    // One part a renderer submits (a door's leaf): the same each frame by its render type, the drawing code and the
    // objects it draws from (model, mesh), and how many alike came before it
    public record PartKey(Object type, Class<?> code, List<Object> from, int n) {
    }

    // the part a triangle was drawn as, at block entity `be`, with the part's pose then (relative to `be`)
    public record Anchor(BlockPos be, PartKey key, org.joml.Matrix4fc pose) {
    }

    // where a part is now: its pose relative to its block entity, and the planes NTM cuts it with (a leaf sliding
    // into its frame), in the part's own space: kept where n . p - d >= 0; null = not cut
    public record PartPose(org.joml.Matrix4fc pose, float @Nullable [][] planes) {
    }

    private static final Map<BlockPos, Map<PartKey, PartPose>> POSES = new HashMap<>();
    private static long posesAt;

    // the parts block entity `pos` draws now, run once a frame (only the poses: nothing is drawn); null = no block
    // entity there with a renderer that can be read
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static @Nullable Map<PartKey, PartPose> poses(ClientLevel level, BlockPos pos, long frame) {
        if (frame != posesAt) {
            POSES.clear();
            posesAt = frame;
        }
        if (POSES.containsKey(pos)) return POSES.get(pos);
        Map<PartKey, PartPose> got = null;
        BlockEntity be = level.getBlockEntity(pos);
        BlockEntityRenderer renderer = be == null ? null : Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer != null && !FAILED.contains(renderer.getClass())) {
            Recorder rec = record(be, renderer);
            if (rec != null) got = rec.poses;
        }
        POSES.put(pos.immutable(), got);
        return got;
    }

    // what each block entity drew, kept a moment: one burst into a machine reads it once
    private static final Map<BlockPos, Got> CACHE = new HashMap<>();
    private static final long FRESH = 250_000_000L;
    private static final Set<Class<?>> FAILED = new HashSet<>();
    private static final int REACH = 16;

    public static void clear() {
        CACHE.clear();
    }

    // the triangles of every block entity whose renderer draws into the box (their render bounds meet it)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<Tri> within(ClientLevel level, AABB box) {
        List<Tri> out = new ArrayList<>();
        long now = System.nanoTime();
        CACHE.values().removeIf(g -> now - g.at() > 8 * FRESH);
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        int cx0 = SectionPos.blockToSectionCoord(Mth.floor(box.minX) - REACH), cx1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxX) + REACH);
        int cz0 = SectionPos.blockToSectionCoord(Mth.floor(box.minZ) - REACH), cz1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ) + REACH);
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockEntityRenderer renderer = dispatcher.getRenderer(be);
                if (renderer == null || FAILED.contains(renderer.getClass())) continue;
                AABB bounds;
                try {
                    // NeoForge adds this to every renderer when it loads the game (not in the jar compiled against)
                    bounds = ((net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension) (Object) renderer).getRenderBoundingBox(be);
                } catch (Throwable t) {
                    continue;
                }
                if (bounds == null || !bounds.intersects(box)) continue;
                Got got = CACHE.get(be.getBlockPos());
                if (got == null || now - got.at() > FRESH) {
                    got = new Got(now, capture(be, renderer));
                    CACHE.put(be.getBlockPos().immutable(), got);
                }
                for (Tri t : got.tris()) if (t.meets(box)) out.add(t);
            }
        }
        return out;
    }

    // the triangles this block entity's renderer draws now (none: no renderer, or it could not be read)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<Tri> of(BlockEntity be) {
        BlockEntityRenderer renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null || FAILED.contains(renderer.getClass())) return List.of();
        return capture(be, renderer);
    }

    // true while a renderer is run here: NTM's hasVisual answers no, so its renderer draws what Flywheel would (the
    // press head, at its height)
    // (only on the thread reading it: Flywheel may ask from its own threads meanwhile)
    private static volatile Thread capturing;

    public static boolean capturingHere() {
        return capturing == Thread.currentThread();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Tri> capture(BlockEntity be, BlockEntityRenderer renderer) {
        Recorder rec = record(be, renderer);
        if (rec == null) return List.of();
        try {
            List<Tri> out = new ArrayList<>();
            BlockPos o = be.getBlockPos();
            for (Recorder.Part part : rec.parts) part.emit(out, o);
            return out;
        } catch (Throwable t) {
            FAILED.add(renderer.getClass());
            GunFeel.LOGGER.warn("Could not read what {} draws: bullet holes go on the hitbox of its blocks", renderer.getClass().getName(), t);
            return List.of();
        }
    }

    // the renderer run into a recorder: what it submits, not drawn yet
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable Recorder record(BlockEntity be, BlockEntityRenderer renderer) {
        capturing = Thread.currentThread();
        try {
            Minecraft mc = Minecraft.getInstance();
            Vec3 cam = mc.gameRenderer.mainCamera().position();
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            BlockEntityRenderState state = (BlockEntityRenderState) renderer.createRenderState();
            renderer.extractRenderState(be, state, partial, cam, null);
            drawItself(state);
            CameraRenderState camera = new CameraRenderState();
            camera.pos = cam;
            camera.blockPos = BlockPos.containing(cam);
            camera.initialized = true;
            Recorder rec = new Recorder();
            renderer.submit(state, new PoseStack(), rec, camera);
            return rec;
        } catch (Throwable t) {
            FAILED.add(renderer.getClass());
            GunFeel.LOGGER.warn("Could not read what {} draws: bullet holes go on the hitbox of its blocks", renderer.getClass().getName(), t);
            return null;
        } finally {
            capturing = null;
        }
    }

    // /gunfeel meshdump: what is read around the block looked at, to config/ntm-gunfeel-meshdump.txt
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int dump(net.minecraft.commands.CommandSourceStack src) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return 0;
        var pick = mc.player.pick(64.0, 1.0F, false);
        if (!(pick instanceof net.minecraft.world.phys.BlockHitResult hit) || pick.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            src.sendFailure(Component.literal("Посмотри на блок"));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        AABB cell = new AABB(pos), around = cell.inflate(1.0);
        StringBuilder b = new StringBuilder();
        b.append("block ").append(pos.toShortString()).append(' ').append(level.getBlockState(pos)).append(" hit ").append(hit.getLocation()).append(' ').append(hit.getDirection()).append('\n');
        CACHE.clear();
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        int cx0 = SectionPos.blockToSectionCoord(pos.getX() - REACH - 1), cx1 = SectionPos.blockToSectionCoord(pos.getX() + REACH + 1);
        int cz0 = SectionPos.blockToSectionCoord(pos.getZ() - REACH - 1), cz1 = SectionPos.blockToSectionCoord(pos.getZ() + REACH + 1);
        Map<Tex, Identifier> names = new java.util.IdentityHashMap<>();
        for (var e : TEXTURES.entrySet()) names.put(e.getValue(), e.getKey());
        List<Tri> all = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null) continue;
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                BlockEntityRenderer renderer = dispatcher.getRenderer(be);
                if (renderer == null) continue;
                AABB bounds = ((net.neoforged.neoforge.client.extensions.IBlockEntityRendererExtension) (Object) renderer).getRenderBoundingBox(be);
                if (bounds == null || !bounds.intersects(around)) continue;
                List<Tri> tris = capture(be, renderer);
                for (var e : TEXTURES.entrySet()) names.put(e.getValue(), e.getKey());
                int meets = 0;
                double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
                Map<String, Integer> byTex = new java.util.TreeMap<>();
                for (Tri t : tris) {
                    if (t.meets(cell)) meets++;
                    for (int i = 0; i < 9; i++) {
                        lo[i % 3] = Math.min(lo[i % 3], t.p()[i]);
                        hi[i % 3] = Math.max(hi[i % 3], t.p()[i]);
                    }
                    byTex.merge(String.valueOf(names.getOrDefault(t.tex(), null)) + (t.tex().seeThrough() ? " (see-through)" : "") + (t.oneSided() ? " cull" : " two-sided"), 1, Integer::sum);
                }
                b.append("BE ").append(be.getType()).append(" at ").append(be.getBlockPos().toShortString()).append(" renderer ").append(renderer.getClass().getName())
                        .append(" failed=").append(FAILED.contains(renderer.getClass())).append(" bounds ").append(bounds).append('\n');
                b.append("  ").append(tris.size()).append(" tris, ").append(meets).append(" in the block, extent ")
                        .append(String.format("%.3f %.3f %.3f .. %.3f %.3f %.3f", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])).append('\n');
                for (var e : byTex.entrySet()) b.append("    ").append(e.getValue()).append(" x ").append(e.getKey()).append('\n');
                for (Tri t : tris) if (t.meets(around)) all.add(t);
            }
        }
        boolean[] reaches = {false};
        List<Tri> models = models(level, around, pos, cell, reaches, new java.util.HashSet<>());
        b.append("block models: ").append(models.size()).append(" tris, a bigger model reaches in: ").append(reaches[0]).append('\n');
        all.addAll(models);
        Vec3 eye = mc.player.getEyePosition(1.0F), look = mc.player.getViewVector(1.0F);
        Vector3d o = new Vector3d(eye.x, eye.y, eye.z), d = new Vector3d(look.x, look.y, look.z);
        double first = Double.POSITIVE_INFINITY, shown = Double.POSITIVE_INFINITY;
        for (Tri t : all) {
            double k = t.cross(o, d);
            if (Double.isNaN(k) || k < 0) continue;
            first = Math.min(first, k);
            double[] w = t.weights(new Vector3d(d).mul(k).add(o), 1e-4);
            if (w != null && t.opaqueAt(w)) shown = Math.min(shown, k);
        }
        b.append(String.format("look: hitbox at %.3f, first triangle at %.3f, first shown pixel at %.3f%n", eye.distanceTo(hit.getLocation()), first, shown));
        java.nio.file.Path f = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("ntm-gunfeel-meshdump.txt");
        try {
            java.nio.file.Files.writeString(f, b.toString());
        } catch (Exception e) {
            src.sendFailure(Component.literal("Не записал " + f + ": " + e));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("meshdump: " + all.size() + " треугольников рядом, записал в " + f.getFileName()), false);
        return 1;
    }

    // ---- block models bigger than their block (NTM machines: an OBJ model on the core block over the whole machine)

    private static final Map<net.minecraft.world.level.block.state.BlockState, AABB> REACH_OF = new java.util.IdentityHashMap<>();
    private static final AABB INSIDE = new AABB(0, 0, 0, 0, 0, 0);
    private static final int MODEL_REACH = 8;

    // the model's bounds in its block's space; INSIDE when it stays in the block
    private static AABB reachOf(net.minecraft.world.level.block.state.BlockState state) {
        return REACH_OF.computeIfAbsent(state, st -> {
            double x0 = 0, y0 = 0, z0 = 0, x1 = 1, y1 = 1, z1 = 1;
            for (BakedQuad q : MeshShapes.quads(st)) for (int i = 0; i < 4; i++) {
                var v = q.position(i);
                x0 = Math.min(x0, v.x()); y0 = Math.min(y0, v.y()); z0 = Math.min(z0, v.z());
                x1 = Math.max(x1, v.x()); y1 = Math.max(y1, v.y()); z1 = Math.max(z1, v.z());
            }
            boolean out = x0 < -1e-3 || y0 < -1e-3 || z0 < -1e-3 || x1 > 1 + 1e-3 || y1 > 1 + 1e-3 || z1 > 1 + 1e-3;
            return out ? new AABB(x0, y0, z0, x1, y1, z1) : INSIDE;
        });
    }

    // The block model triangles in the box: the models of the blocks in it (faces a neighbour hides left out, as the
    // game draws them), and those of blocks around whose model reaches out of their block into the box. reaches[0] is
    // set when one of the latter meets `cell`. The triangles of models that stay in their block go into `plain` too.
    public static List<Tri> models(ClientLevel level, AABB box, BlockPos own, AABB cell, boolean[] reaches, java.util.Set<Tri> plain) {
        List<Tri> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int bx0 = Mth.floor(box.minX), bx1 = Mth.ceil(box.maxX) - 1, by0 = Mth.floor(box.minY), by1 = Mth.ceil(box.maxY) - 1;
        int bz0 = Mth.floor(box.minZ), bz1 = Mth.ceil(box.maxZ) - 1;
        int x0 = bx0 - MODEL_REACH, x1 = bx1 + MODEL_REACH;
        int y0 = Math.max(level.getMinY(), by0 - MODEL_REACH), y1 = Math.min(level.getMaxY(), by1 + MODEL_REACH);
        int z0 = bz0 - MODEL_REACH, z1 = bz1 + MODEL_REACH;
        List<BlockStateModelPart> parts = new ArrayList<>();
        List<Tri> made = new ArrayList<>();
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
        // sections (16 cubed) with no model reaching out of its block are passed by for the blocks round the box
        Map<Long, Boolean> reaching = new HashMap<>();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
            boolean near = x >= bx0 && x <= bx1 && y >= by0 && y <= by1 && z >= bz0 && z <= bz1;
            if (!near && !reaching.computeIfAbsent(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> sectionReaches(level, k))) continue;
            at.set(x, y, z);
            var st = level.getBlockState(at);
            if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            AABB r = reachOf(st);
            if (r == INSIDE && !near) continue;
            if (r != INSIDE && !r.move(x, y, z).intersects(box)) continue;
            // only what a bullet can hit (it goes by collision): no holes on grass, flowers, crops, torches
            if (r == INSIDE && st.getCollisionShape(level, at).isEmpty()) continue;
            BlockPos block = at.immutable();
            parts.clear();
            random.setSeed(st.getSeed(block));
            Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st).collectParts(random, parts);
            for (BlockStateModelPart part : parts) {
                for (int side = 0; side <= 6; side++) {
                    Direction dir = side < 6 ? Direction.values()[side] : null;
                    if (dir != null && !net.minecraft.world.level.block.Block.shouldRenderFace(st, level.getBlockState(block.relative(dir)), dir)) continue;
                    for (BakedQuad q : part.getQuads(dir)) {
                        double[] p = new double[12];
                        float[] uv = new float[8];
                        for (int i = 0; i < 4; i++) {
                            p[i * 3] = x + q.position(i).x();
                            p[i * 3 + 1] = y + q.position(i).y();
                            p[i * 3 + 2] = z + q.position(i).z();
                            uv[i * 2] = UVPair.unpackU(q.packedUV(i));
                            uv[i * 2 + 1] = UVPair.unpackV(q.packedUV(i));
                        }
                        made.clear();
                        quad(made, p, uv, atlasSprite(q.materialInfo().sprite()), true, false, block);
                        for (Tri t : made) {
                            if (r != INSIDE && !reaches[0] && t.meets(cell)) reaches[0] = true;
                            if (!t.meets(box)) continue;
                            out.add(t);
                            if (r == INSIDE) plain.add(t);
                        }
                    }
                }
            }
        }
        return out;
    }

    // a section holds a block whose model reaches out of its block (or it is not loaded: then nothing is there)
    private static boolean sectionReaches(ClientLevel level, long key) {
        int sx = net.minecraft.core.SectionPos.x(key), sy = net.minecraft.core.SectionPos.y(key), sz = net.minecraft.core.SectionPos.z(key);
        var chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return true;
        int index = level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return true;
        var section = chunk.getSection(index);
        if (section.hasOnlyAir()) return false;
        return section.maybeHas(st -> st.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL && reachOf(st) != INSIDE);
    }

    // NTM leaves parts to Flywheel (CrankShaft) when a visual draws them ("visualized"): draw them here too
    private static final Map<Class<?>, Field> VISUALIZED = new HashMap<>();

    private static void drawItself(BlockEntityRenderState state) {
        Field f = VISUALIZED.computeIfAbsent(state.getClass(), c -> {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    Field v = k.getDeclaredField("visualized");
                    if (v.getType() != boolean.class) return null;
                    v.setAccessible(true);
                    return v;
                } catch (NoSuchFieldException e) {
                    // up
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        });
        if (f == null) return;
        try {
            f.setBoolean(state, false);
        } catch (Exception e) {
            // leave it
        }
    }

    // what a drawing lambda holds: the objects it draws from (model, mesh: not numbers such as light or a group, not
    // the cut planes, which change as the part moves) and the cut planes. known = its fields could be read
    private record Captured(List<Object> from, float @Nullable [][] planes, boolean known) {
    }

    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    private static Captured captured(Object lambda) {
        Field[] fields = FIELDS.computeIfAbsent(lambda.getClass(), c -> {
            try {
                Field[] fs = c.getDeclaredFields();
                for (Field f : fs) f.setAccessible(true);
                return fs;
            } catch (Throwable t) {
                return null;
            }
        });
        if (fields == null) return new Captured(List.of(), null, false);
        List<Object> from = new ArrayList<>();
        float[][] planes = null;
        try {
            for (Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Object v = f.get(lambda);
                if (v instanceof float[][] fp) planes = fp;
                else if (v != null && !(v instanceof Number) && !v.getClass().isArray()) from.add(new Same(v));
            }
        } catch (Throwable t) {
            return new Captured(List.of(), null, false);
        }
        return new Captured(from, planes, true);
    }

    // compared by identity: the same model object
    private record Same(Object o) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Same s && s.o == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(o);
        }
    }

    // ---- the recorder: keeps what is submitted, then draws it into Capture

    private static final class Recorder implements SubmitNodeCollector {
        interface Part {
            void emit(List<Tri> out, BlockPos origin);
        }

        final List<Part> parts = new ArrayList<>();
        final Map<PartKey, PartPose> poses = new HashMap<>();
        private final Map<List<Object>, Integer> alike = new HashMap<>();

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType type, CustomGeometryRenderer renderer) {
            PoseStack.Pose pose = poseStack.last().copy();
            Captured got = captured(renderer);
            List<Object> sig = new ArrayList<>();
            sig.add(type);
            sig.add(renderer.getClass());
            sig.addAll(got.from());
            int n = alike.merge(sig, 1, Integer::sum) - 1;
            PartKey key = got.known() ? new PartKey(type, renderer.getClass(), got.from(), n) : null;
            org.joml.Matrix4f now = new org.joml.Matrix4f(pose.pose());
            if (key != null) poses.put(key, new PartPose(now, got.planes()));
            parts.add((out, o) -> {
                Capture c = new Capture(type, texOf(type), o, out, key == null ? null : new Anchor(o.immutable(), key, now));
                renderer.render(pose, c);
                c.finish();
            });
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType type, int light, int overlay, int tint,
                @Nullable TextureAtlasSprite sprite, int outline, @Nullable CrumblingOverlay crumbling) {
            PoseStack.Pose pose = poseStack.last().copy();
            parts.add((out, o) -> {
                PoseStack ps = new PoseStack();
                ps.last().pose().set(pose.pose());
                ps.last().normal().set(pose.normal());
                model.setupAnim(state);
                Capture c = new Capture(type, sprite != null ? spriteTex(sprite) : texOf(type), o, out);
                model.renderToBuffer(ps, c, light, overlay, tint);
                c.finish();
            });
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType type, List<BlockStateModelPart> modelParts, int[] tints, int light, int overlay, int outline) {
            PoseStack.Pose pose = poseStack.last().copy();
            boolean cull = type.pipeline().isCull();
            parts.add((out, o) -> {
                for (BlockStateModelPart part : modelParts) {
                    List<BakedQuad> quads = new ArrayList<>(part.getQuads(null));
                    for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
                    for (BakedQuad q : quads) {
                        Tex tex = atlasSprite(q.materialInfo().sprite());
                        double[] p = new double[12];
                        float[] uv = new float[8];
                        for (int i = 0; i < 4; i++) {
                            var v = pose.pose().transformPosition(q.position(i).x(), q.position(i).y(), q.position(i).z(), new org.joml.Vector3f());
                            p[i * 3] = o.getX() + v.x;
                            p[i * 3 + 1] = o.getY() + v.y;
                            p[i * 3 + 2] = o.getZ() + v.z;
                            uv[i * 2] = UVPair.unpackU(q.packedUV(i));
                            uv[i * 2 + 1] = UVPair.unpackV(q.packedUV(i));
                        }
                        quad(out, p, uv, tex, cull, true);
                    }
                }
            });
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<ShadowPiece> pieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, @Nullable Vec3 at, int offset, Component name, boolean seeThrough, int light, CameraRenderState camera) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, DisplayMode mode, int light, int color,
                int background, int outline) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, LeashState state) {
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outline) {
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
        }

        @Override
        public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType type, int color, float width, boolean afterTerrain) {
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, List<BakedQuad> quads,
                FoilType foil) {
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        }

        @Override
        public void submitGizmoPrimitives(Group group, CameraRenderState camera, boolean onTop) {
        }
    }

    private static void quad(List<Tri> out, double[] p, float[] uv, Tex tex, boolean cull, boolean moving) {
        quad(out, p, uv, tex, cull, moving, null);
    }

    private static void quad(List<Tri> out, double[] p, float[] uv, Tex tex, boolean cull, boolean moving, @Nullable BlockPos block) {
        out.add(new Tri(new double[] {p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8]}, new float[] {uv[0], uv[1], uv[2], uv[3], uv[4], uv[5]}, tex, cull, moving, null, block));
        out.add(new Tri(new double[] {p[0], p[1], p[2], p[6], p[7], p[8], p[9], p[10], p[11]}, new float[] {uv[0], uv[1], uv[4], uv[5], uv[6], uv[7]}, tex, cull, moving, null, block));
    }

    // collects the vertices a renderer writes (position, texture coordinates), then cuts them into triangles
    private static final class Capture implements VertexConsumer {
        private final PrimitiveTopology topology;
        private final Tex tex;
        private final boolean cull;
        private final BlockPos origin;
        private final List<Tri> out;
        private final @Nullable Anchor anchor;
        private float[] v = new float[5 * 64];
        private int n;

        Capture(RenderType type, Tex tex, BlockPos origin, List<Tri> out) {
            this(type, tex, origin, out, null);
        }

        Capture(RenderType type, Tex tex, BlockPos origin, List<Tri> out, @Nullable Anchor anchor) {
            this.topology = type.primitiveTopology();
            this.tex = tex;
            this.cull = type.pipeline().isCull();
            this.origin = origin;
            this.out = out;
            this.anchor = anchor;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            if ((n + 1) * 5 > v.length) v = java.util.Arrays.copyOf(v, v.length * 2);
            v[n * 5] = x;
            v[n * 5 + 1] = y;
            v[n * 5 + 2] = z;
            n++;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float vv) {
            if (n > 0) {
                v[(n - 1) * 5 + 3] = u;
                v[(n - 1) * 5 + 4] = vv;
            }
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int vv) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int vv) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        private void tri(int a, int b, int c) {
            double[] p = new double[9];
            float[] uv = new float[6];
            int[] idx = {a, b, c};
            for (int i = 0; i < 3; i++) {
                int k = idx[i] * 5;
                p[i * 3] = origin.getX() + v[k];
                p[i * 3 + 1] = origin.getY() + v[k + 1];
                p[i * 3 + 2] = origin.getZ() + v[k + 2];
                uv[i * 2] = v[k + 3];
                uv[i * 2 + 1] = v[k + 4];
            }
            out.add(new Tri(p, uv, tex, cull, true, anchor, null));
        }

        void finish() {
            switch (topology) {
                case QUADS -> {
                    for (int i = 0; i + 3 < n; i += 4) {
                        tri(i, i + 1, i + 2);
                        tri(i, i + 2, i + 3);
                    }
                }
                case TRIANGLES -> {
                    for (int i = 0; i + 2 < n; i += 3) tri(i, i + 1, i + 2);
                }
                case TRIANGLE_STRIP -> {
                    for (int i = 0; i + 2 < n; i++) tri(i, i + 1, i + 2);
                }
                case TRIANGLE_FAN -> {
                    for (int i = 1; i + 1 < n; i++) tri(0, i, i + 1);
                }
                default -> {
                }
            }
        }
    }

    // ---- textures

    private static final Map<Identifier, Tex> TEXTURES = new HashMap<>();
    private static final Map<TextureAtlasSprite, Tex> SPRITES = new java.util.IdentityHashMap<>();
    private static Field typeState, setupTextures;
    private static Method bindingLocation;
    private static boolean toldTextures;

    // a texture shows nothing below this alpha (the cutout shaders drop it)
    private static final int CLEAR = 26;

    // the texture a render type draws with (its Sampler0)
    private static @Nullable Identifier textureOf(RenderType type) {
        try {
            if (typeState == null) {
                typeState = RenderType.class.getDeclaredField("state");
                typeState.setAccessible(true);
            }
            Object setup = typeState.get(type);
            if (setup == null) return null;
            if (setupTextures == null) {
                setupTextures = setup.getClass().getDeclaredField("textures");
                setupTextures.setAccessible(true);
            }
            Map<?, ?> map = (Map<?, ?>) setupTextures.get(setup);
            Object binding = map.containsKey("Sampler0") ? map.get("Sampler0") : map.values().stream().findFirst().orElse(null);
            if (binding == null) return null;
            if (bindingLocation == null) {
                bindingLocation = binding.getClass().getDeclaredMethod("location");
                bindingLocation.setAccessible(true);
            }
            return (Identifier) bindingLocation.invoke(binding);
        } catch (Exception e) {
            if (!toldTextures) {
                toldTextures = true;
                GunFeel.LOGGER.warn("Could not read the textures renderers draw with: their see-through pixels count as solid", e);
            }
            return null;
        }
    }

    // alpha 0 that still reads as glass: holes stay on it (QE sliding door window)
    private static final java.util.Set<String> GLASS = java.util.Set.of("hbm:textures/block/models/pheodoors/sliding_door.png",
            // sliding blast door: its armoured window
            "hbm:textures/block/models/pheodoors/blast_door.png");

    // a texture's name, for /gunfeel holedebug
    public static String texName(Tex tex) {
        if (tex == OPAQUE) return "OPAQUE(no texture read)";
        if (tex == GLASS_TEX) return "GLASS";
        for (var e : TEXTURES.entrySet()) if (e.getValue() == tex) return e.getKey().toString();
        for (var e : SPRITES.entrySet()) if (e.getValue() == tex) return "sprite " + e.getKey().contents().name();
        return tex.getClass().getSimpleName() + (tex.seeThrough() ? "(see-through)" : "");
    }

    private static Tex texOf(RenderType type) {
        Identifier loc = textureOf(type);
        if (loc == null) return OPAQUE;
        if (GLASS.contains(loc.toString())) return GLASS_TEX;
        return TEXTURES.computeIfAbsent(loc, RenderedMesh::load);
    }

    private static Tex load(Identifier loc) {
        Minecraft mc = Minecraft.getInstance();
        try {
            AbstractTexture t = mc.getTextureManager().getTexture(loc);
            if (t instanceof TextureAtlas atlas) return new AtlasTex(atlas);
            NativeImage img = t instanceof DynamicTexture d ? d.getPixels() : null;
            if (img != null) return image(img);
            try (var in = mc.getResourceManager().open(loc); NativeImage read = NativeImage.read(in)) {
                return image(read);
            }
        } catch (Exception e) {
            GunFeel.LOGGER.debug("No pixels for {}: counted as solid", loc, e);
            return OPAQUE;
        }
    }

    private static Tex image(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        boolean[] shown = new boolean[w * h];
        int[] argb = new int[w * h];
        boolean any = false;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int px = img.getPixel(x, y);
            boolean s = (px >>> 24) >= CLEAR;
            shown[y * w + x] = s;
            argb[y * w + x] = px;
            any |= !s;
        }
        boolean clear = any;
        return new Tex() {
            public boolean opaque(float u, float v) {
                int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
                return shown[y * w + x];
            }

            public int color(float u, float v) {
                int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
                return argb[y * w + x];
            }

            public boolean seeThrough() {
                return clear;
            }

            public double[] grid() {
                return new double[] {0, 1.0 / w, 0, 1.0 / h};
            }
        };
    }

    // a sprite of an atlas, its own coordinates 0..1 (a model drawn with a sprite)
    private static Tex spriteTex(TextureAtlasSprite sprite) {
        return new Tex() {
            public boolean opaque(float u, float v) {
                return MeshShapes.opaque(sprite, sprite.getU(u), sprite.getV(v));
            }

            public int color(float u, float v) {
                return MeshShapes.color(sprite, sprite.getU(u), sprite.getV(v));
            }

            public boolean seeThrough() {
                return atlasSprite(sprite).seeThrough();
            }

            public double[] grid() {
                var c = sprite.contents();
                return new double[] {0, 1.0 / c.width(), 0, 1.0 / c.height()};
            }
        };
    }

    // a sprite of an atlas, atlas coordinates (block model quads)
    static Tex atlasSprite(TextureAtlasSprite sprite) {
        return SPRITES.computeIfAbsent(sprite, sp -> {
            var c = sp.contents();
            boolean clear = false;
            for (int y = 0; y < c.height() && !clear; y++) for (int x = 0; x < c.width(); x++) if (c.isTransparent(0, x, y)) {
                clear = true;
                break;
            }
            boolean any = clear;
            return new Tex() {
                public boolean opaque(float u, float v) {
                    return MeshShapes.opaque(sp, u, v);
                }

                public int color(float u, float v) {
                    return MeshShapes.color(sp, u, v);
                }

                public boolean seeThrough() {
                    return any;
                }

                public double[] grid() {
                    return new double[] {sp.getU0(), (sp.getU1() - sp.getU0()) / (double) c.width(), sp.getV0(), (sp.getV1() - sp.getV0()) / (double) c.height()};
                }
            };
        });
    }

    // custom geometry drawn straight onto an atlas: the sprite under (u, v)
    private static final class AtlasTex implements Tex {
        private static Field byName;
        private final List<TextureAtlasSprite>[] grid;
        private static final int G = 32;

        @SuppressWarnings("unchecked")
        AtlasTex(TextureAtlas atlas) throws Exception {
            if (byName == null) {
                byName = TextureAtlas.class.getDeclaredField("texturesByName");
                byName.setAccessible(true);
            }
            grid = new List[G * G];
            for (TextureAtlasSprite sp : ((Map<Identifier, TextureAtlasSprite>) byName.get(atlas)).values()) {
                int x0 = cell(sp.getU0()), x1 = cell(sp.getU1()), y0 = cell(sp.getV0()), y1 = cell(sp.getV1());
                for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                    if (grid[y * G + x] == null) grid[y * G + x] = new ArrayList<>();
                    grid[y * G + x].add(sp);
                }
            }
        }

        private static int cell(float f) {
            return Math.clamp((int) (f * G), 0, G - 1);
        }

        public boolean opaque(float u, float v) {
            List<TextureAtlasSprite> l = grid[cell(v) * G + cell(u)];
            if (l != null) for (TextureAtlasSprite sp : l) {
                if (u >= sp.getU0() && u <= sp.getU1() && v >= sp.getV0() && v <= sp.getV1()) return MeshShapes.opaque(sp, u, v);
            }
            return true;
        }

        public int color(float u, float v) {
            List<TextureAtlasSprite> l = grid[cell(v) * G + cell(u)];
            if (l != null) for (TextureAtlasSprite sp : l) {
                if (u >= sp.getU0() && u <= sp.getU1() && v >= sp.getV0() && v <= sp.getV1()) return MeshShapes.color(sp, u, v);
            }
            return 0;
        }

        public boolean seeThrough() {
            return true;
        }
    }
}

package acup.gunfeel;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.hbm.client.render.RenderBulletMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

// Client: TaCZ-like bullets. The server announces every NTM bullet (ShotPayload); here it gets a thin tracer in the
// colours of its NTM tracer, flown along the same path through the client's world (from the muzzle for the player's
// own shots), and a bullet hole where it hits a block. NTM's own wide ribbon is then not drawn for those bullets.
public final class Tracers {
    // eight hole shapes, then the scorch mark left by bullets that do something on impact (explosive, incendiary)
    private static final int HOLE_KINDS = 8, SCORCH = HOLE_KINDS;
    private static final Identifier[] TEXTURES = new Identifier[HOLE_KINDS + 1];
    static {
        for (int i = 0; i < HOLE_KINDS; i++) TEXTURES[i] = Identifier.fromNamespaceAndPath("ntmgunfeel", "textures/effect/bullet_hole_" + i + ".png");
        TEXTURES[SCORCH] = Identifier.fromNamespaceAndPath("ntmgunfeel", "textures/effect/scorch.png");
    }
    // the tracer joins the bullet's real path over this many blocks from the muzzle
    private static final float JOIN = 4F;

    private Tracers() {
    }

    private static final class Shot {
        final BulletConfig config;
        final int owner;
        final Vec3 start;
        final Vec3 v;
        final float g;
        final double speed;
        final int dark, light;
        final long born = System.nanoTime();
        final long lifeTicks;
        Vec3 muzzleOffset = Vec3.ZERO;
        double checked; // ticks of path already tested against the world
        Vec3 eye; // the shooter's eyes when the bullet starts near them: its first test starts there
        double end = Double.NaN; // ticks at which it hit something

        Shot(ShotPayload p, BulletConfig config, int[] colors) {
            this.config = config;
            this.owner = p.owner();
            this.start = new Vec3(p.x(), p.y(), p.z());
            this.v = new Vec3(p.vx(), p.vy(), p.vz());
            this.g = p.gravity();
            this.speed = Math.max(v.length(), 1e-3);
            this.dark = colors[0];
            this.light = colors[1];
            this.lifeTicks = p.life();
        }

        // where the bullet is after t ticks (as the server moves it), plus the muzzle offset fading out
        Vec3 at(double t) {
            Vec3 p = start.add(v.x * t, v.y * t - 0.5 * g * t * t, v.z * t);
            double k = Math.max(0.0, 1.0 - speed * t / JOIN);
            return k > 0 ? p.add(muzzleOffset.scale(k)) : p;
        }
    }

    // rect = the area of the face the hole may cover, {uMin, uMax, vMin, vMax} on the face's two axes
    // mask = which 1/16-block cells of the face show the block's pixels (null = all): cells mask.w x mask.h from
    // cell (u0, v0) on the face's two axes, in 1/16 blocks
    private record Hole(Vec3 pos, Direction face, int turn, long born, int light, int kind, float size, double[] rect, Mask mask, double plane, boolean late, boolean cull,
            float[] mesh, boolean front, BlockPos block, BlockState state, boolean drawn, Follow follow) {
    }

    // A hole on a part a renderer moves (a door's leaf, a press head): kept in the part's own space and put where the
    // part is each frame, cut where NTM cuts the part (a leaf sliding into its frame). model = as Hole.mesh, but
    // positions in the part's space; mesh, pos, light = where it is now (mesh null: cut away whole)
    private static final class Follow {
        final BlockPos be;
        final RenderedMesh.PartKey key;
        final float[] model;
        final Vector3f center;
        Vec3 pos;
        float[] mesh;
        int light;
        private org.joml.Matrix4f placed;
        private float[][] cut;

        Follow(RenderedMesh.Anchor a, Vector3d world, float[] mesh, int light) {
            be = a.be();
            key = a.key();
            org.joml.Matrix4f inv = new org.joml.Matrix4f(a.pose()).invert();
            Vector3f c = new Vector3f((float) (world.x - be.getX()), (float) (world.y - be.getY()), (float) (world.z - be.getZ()));
            center = inv.transformPosition(c, new Vector3f());
            model = mesh.clone();
            Vector3f n = inv.transformDirection(new Vector3f(mesh[0], mesh[1], mesh[2]), new Vector3f()).normalize();
            model[0] = n.x;
            model[1] = n.y;
            model[2] = n.z;
            for (int k = 3; k < model.length; ) {
                int count = (int) model[k++];
                for (int i = 0; i < count; i++, k += 5) {
                    Vector3f v = inv.transformPosition(c.x + mesh[k], c.y + mesh[k + 1], c.z + mesh[k + 2], new Vector3f());
                    model[k] = v.x;
                    model[k + 1] = v.y;
                    model[k + 2] = v.z;
                }
            }
            placed = new org.joml.Matrix4f(a.pose());
            pos = new Vec3(world.x, world.y, world.z);
            this.mesh = mesh;
            this.light = light;
        }

        void place(ClientLevel level, RenderedMesh.PartPose now) {
            if (now.pose().equals(placed) && java.util.Arrays.deepEquals(now.planes(), cut)) return;
            placed = new org.joml.Matrix4f(now.pose());
            cut = now.planes() == null ? null : copy(now.planes());
            Vector3f c = placed.transformPosition(center, new Vector3f());
            Vec3 at = new Vec3(be.getX() + c.x, be.getY() + c.y, be.getZ() + c.z);
            Vector3f n = placed.transformDirection(new Vector3f(model[0], model[1], model[2]), new Vector3f()).normalize();
            List<float[]> polys = new ArrayList<>();
            for (int k = 3; k < model.length; ) {
                int count = (int) model[k++];
                List<float[]> poly = new ArrayList<>(count);
                for (int i = 0; i < count; i++, k += 5) poly.add(new float[] {model[k], model[k + 1], model[k + 2], model[k + 3], model[k + 4]});
                if (cut != null) for (float[] plane : cut) poly = keep(poly, plane);
                if (poly.size() < 3) continue;
                float[] out = new float[poly.size() * 5];
                for (int i = 0; i < poly.size(); i++) {
                    float[] v = poly.get(i);
                    Vector3f w = placed.transformPosition(v[0], v[1], v[2], new Vector3f());
                    out[i * 5] = w.x - c.x;
                    out[i * 5 + 1] = w.y - c.y;
                    out[i * 5 + 2] = w.z - c.z;
                    out[i * 5 + 3] = v[3];
                    out[i * 5 + 4] = v[4];
                }
                polys.add(out);
            }
            if (polys.isEmpty()) {
                mesh = null;
            } else {
                int size = 3;
                for (float[] pl : polys) size += 1 + pl.length;
                float[] m = new float[size];
                m[0] = n.x;
                m[1] = n.y;
                m[2] = n.z;
                int k = 3;
                for (float[] pl : polys) {
                    m[k++] = pl.length / 5;
                    System.arraycopy(pl, 0, m, k, pl.length);
                    k += pl.length;
                }
                mesh = m;
            }
            if (at.distanceToSqr(pos) > 1e-4) light = LightCoordsUtil.getLightCoords(level, BlockPos.containing(at.x + n.x * 0.05, at.y + n.y * 0.05, at.z + n.z * 0.05));
            pos = at;
        }

        private static float[][] copy(float[][] a) {
            float[][] o = new float[a.length][];
            for (int i = 0; i < a.length; i++) o[i] = a[i].clone();
            return o;
        }

        // the polygon's part on the kept side of the plane (n . p - d >= 0), texture coordinates carried along
        private static List<float[]> keep(List<float[]> poly, float[] plane) {
            List<float[]> out = new ArrayList<>(poly.size() + 2);
            for (int i = 0; i < poly.size(); i++) {
                float[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
                float da = a[0] * plane[0] + a[1] * plane[1] + a[2] * plane[2] - plane[3];
                float db = b[0] * plane[0] + b[1] * plane[1] + b[2] * plane[2] - plane[3];
                if (da >= 0) out.add(a);
                if ((da >= 0) != (db >= 0)) {
                    float t = da / (da - db);
                    float[] m = new float[5];
                    for (int j = 0; j < 5; j++) m[j] = a[j] + (b[j] - a[j]) * t;
                    out.add(m);
                }
            }
            return out;
        }
    }

    // holes on moving parts put where their part is this frame; gone with the part (the block entity gone, the part
    // no longer drawn)
    private static void follow(ClientLevel level, long now) {
        HOLES.removeIf(h -> {
            Follow f = h.follow();
            if (f == null) return false;
            var poses = RenderedMesh.poses(level, f.be, now);
            if (poses == null) return true;
            RenderedMesh.PartPose pp = poses.get(f.key);
            if (pp == null) return true;
            f.place(level, pp);
            return false;
        });
    }

    // plane = where on the face's axis the block's pixels are (NaN = the hit point's)
    private record Mask(int u0, int v0, int w, int h, boolean[] solid, double plane) {
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static final ArrayDeque<Hole> HOLES = new ArrayDeque<>();
    private static boolean serverAnnounces;
    private static long lastFrame;

    // NTM tracer colours per bullet type, {dark, light}; NONE = not a plain tracer (grenade, rocket...): NTM draws it
    private static final Map<BulletConfig, int[]> COLORS = new HashMap<>();
    private static final int[] NONE = new int[0];
    private static boolean probing;
    private static int[] probed;

    // muzzle of the gun in the player's hands, in view space, as last drawn (MixinItemRenderWeaponBase)
    private static final Vector3f MUZZLE = new Vector3f();
    private static long muzzleTime;

    // NTM draws the gun with pose = (model view)^-1 * gun, so the pose alone is in world axes; times the model view
    // set while the hands are drawn it is in view space, where the hands' projection applies. The hands are drawn
    // with their own field of view (70, or NTM's model FOV), the world with the player's (zoomed while aiming): the
    // muzzle is moved to where it shows on screen in the world's view.
    public static void muzzle(PoseStack pose) {
        Vector3f p = pose.last().pose().transformPosition(0F, 0F, 0F, new Vector3f());
        Matrix4f view = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack());
        view.transformPosition(p);
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        float hud = hudFov(camera);
        if (hud > 1F && hud < 179F && camera.getFov() > 1F && camera.getFov() < 179F) {
            float k = (float) (Math.tan(Math.toRadians(camera.getFov() * 0.5)) / Math.tan(Math.toRadians(hud * 0.5)));
            p.x *= k;
            p.y *= k;
        }
        MUZZLE.set(p);
        muzzleTime = System.nanoTime();
    }

    private static java.lang.reflect.Field hudFovField;
    private static boolean hudFovMissing;

    private static float hudFov(Camera camera) {
        if (hudFovMissing) return 70F;
        try {
            if (hudFovField == null) {
                hudFovField = Camera.class.getDeclaredField("hudFov");
                hudFovField.setAccessible(true);
            }
            return hudFovField.getFloat(camera);
        } catch (ReflectiveOperationException | RuntimeException e) {
            hudFovMissing = true;
            GunFeel.LOGGER.warn("No Camera.hudFov, tracers assume 70", e);
            return 70F;
        }
    }

    public static boolean probing() {
        return probing;
    }

    public static void probed(int dark, int light) {
        probed = new int[] {dark, light};
    }

    // true when this bullet type's tracer is drawn here instead of by NTM
    public static boolean replaces(BulletConfig config) {
        return serverAnnounces && Config.tracers && config != null && config.velocity >= 5F && colors(config) != NONE;
    }

    @SuppressWarnings("unchecked")
    private static int[] colors(BulletConfig config) {
        int[] c = COLORS.get(config);
        if (c != null) return c;
        c = NONE;
        try {
            Field f = RenderBulletMK4.class.getDeclaredField("RENDERERS");
            f.setAccessible(true);
            RenderBulletMK4.Tracer tracer = ((Map<BulletConfig, RenderBulletMK4.Tracer>) f.get(null)).get(config);
            if (tracer != null) {
                RenderBulletMK4.State state = new RenderBulletMK4.State();
                state.config = config;
                state.length = 1.0;
                probed = null;
                probing = true;
                try {
                    tracer.draw(state, new PoseStack(), null);
                } finally {
                    probing = false;
                }
                if (probed != null) c = probed;
            }
        } catch (Throwable e) {
            // a tracer that draws something else: leave it to NTM
        }
        COLORS.put(config, c);
        return c;
    }

    public static void handle(ShotPayload p, IPayloadContext context) {
        serverAnnounces = true;
        if (!Config.tracers && !Config.bulletHoles) return;
        if (p.config() < 0 || p.config() >= BulletConfig.configs.size()) return;
        BulletConfig config = BulletConfig.configs.get(p.config());
        if (config.velocity < 5F) return;
        int[] c = colors(config);
        if (c == NONE) return;
        Shot s = new Shot(p, config, c);
        Minecraft mc = Minecraft.getInstance();
        // the player's own shot leaves the muzzle drawn in their hands
        if (mc.player != null && p.owner() == mc.player.getId() && System.nanoTime() - muzzleTime < 500_000_000L && mc.options.getCameraType().isFirstPerson()) {
            Camera camera = mc.gameRenderer.mainCamera();
            Vector3f w = camera.rotation().transform(new Vector3f(MUZZLE));
            Vec3 muzzle = camera.position().add(w.x, w.y, w.z);
            s.muzzleOffset = muzzle.subtract(s.start);
            if (s.muzzleOffset.lengthSqr() > 9.0) s.muzzleOffset = Vec3.ZERO;
            if (GunFeel.LOGGER.isDebugEnabled()) {
                // both points in view space (x right, y up, -z forward), for debug.log
                Vector3f st = new Vector3f((float) (s.start.x - camera.position().x), (float) (s.start.y - camera.position().y), (float) (s.start.z - camera.position().z));
                camera.rotation().conjugate(new org.joml.Quaternionf()).transform(st);
                GunFeel.LOGGER.debug("tracer muzzle view {} start view {} fov {} hud {} offset {}", MUZZLE, st, camera.getFov(), hudFov(camera), s.muzzleOffset);
            }
        }
        // fired from a gun (not spawned somewhere else): the path is tested from the shooter's eyes, as on the
        // server, so a wall right in front of the muzzle still gets its hole
        Entity shooter = mc.level == null || p.owner() < 0 ? null : mc.level.getEntity(p.owner());
        if (shooter != null && shooter.getEyePosition().distanceToSqr(s.start) < 9.0) s.eye = shooter.getEyePosition();
        SHOTS.add(s);
        if (SHOTS.size() > 512) SHOTS.remove(0);
    }

    public static void clear() {
        SHOTS.clear();
        HOLES.clear();
        RenderedMesh.clear();
        serverAnnounces = false;
    }

    public static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        long now = System.nanoTime();
        PoseStack pose = event.getPoseStack();
        // a frame's worth of flight, so a fast bullet's tracer does not break into dashes
        double frameTicks = lastFrame == 0L ? 0.05 : Math.clamp((now - lastFrame) / 50_000_000.0, 0.01, 0.25);
        lastFrame = now;
        // one screen pixel at one block away, for the tracer's least width
        Minecraft m = Minecraft.getInstance();
        float pixel = (float) (2.0 * Math.tan(Math.toRadians(m.options.fov().get()) * 0.5) / Math.max(1, m.getWindow().getHeight()));
        List<float[]> streaks = new ArrayList<>();
        Iterator<Shot> it = SHOTS.iterator();
        while (it.hasNext()) {
            Shot s = it.next();
            double t = (now - s.born) / 50_000_000.0;
            fly(s, level, Math.min(t, s.lifeTicks));
            double head = Double.isNaN(s.end) ? Math.min(t, s.lifeTicks) : Math.min(t, s.end);
            double tail = Math.max(0.0, t - Math.max(Config.tracerLength / s.speed, frameTicks * 1.5));
            if (tail >= head) {
                it.remove();
                continue;
            }
            if (!Config.tracers) continue;
            Vec3 h = s.at(head).subtract(cam), b = s.at(tail).subtract(cam);
            streaks.add(new float[] {(float) h.x, (float) h.y, (float) h.z, (float) b.x, (float) b.y, (float) b.z,
                    Float.intBitsToFloat(s.light), Float.intBitsToFloat(s.dark), pixel});
        }
        if (!streaks.isEmpty()) {
            event.getSubmitNodeCollector().submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, vc) -> {
                for (float[] k : streaks) streak(p.pose(), vc, k);
            });
        }
        holes(event, cam, now);
    }

    // test the bullet's path up to t ticks against blocks and entities; stops it at the first thing it meets
    private static void fly(Shot s, ClientLevel level, double t) {
        if (!Double.isNaN(s.end) || t <= s.checked) return;
        // the real path, without the muzzle offset, decides what is hit
        Vec3 ra = s.checked == 0.0 && s.eye != null ? s.eye
                : s.start.add(s.v.x * s.checked, s.v.y * s.checked - 0.5 * s.g * s.checked * s.checked, s.v.z * s.checked);
        Vec3 rb = s.start.add(s.v.x * t, s.v.y * t - 0.5 * s.g * t * t, s.v.z * t);
        BlockHitResult block = PassableClip.clip(level, ra, rb);
        double reach = block.getType() == HitResult.Type.MISS ? rb.distanceTo(ra) : block.getLocation().distanceTo(ra);
        Vec3 dir = rb.subtract(ra);
        double len = dir.length();
        double hitEntity = Double.NaN;
        if (len > 1e-6) {
            AABB area = new AABB(ra, rb).inflate(1.0);
            for (Entity e : level.getEntities((Entity) null, area, e -> e.isPickable() && e.isAlive() && e.getId() != s.owner && !(e instanceof net.minecraft.world.entity.projectile.Projectile))) {
                var hit = e.getBoundingBox().inflate(0.3).clip(ra, rb);
                if (hit.isPresent()) {
                    double d = hit.get().distanceTo(ra);
                    if (d < reach && (Double.isNaN(hitEntity) || d < hitEntity)) hitEntity = d;
                }
            }
        }
        if (!Double.isNaN(hitEntity)) {
            s.end = s.checked + (t - s.checked) * hitEntity / len;
        } else if (block.getType() != HitResult.Type.MISS) {
            s.end = s.checked + (t - s.checked) * (len > 1e-6 ? reach / len : 0.0);
            if (Config.bulletHoles && len > 1e-6) addHole(level, block, s.config, dir.scale(1.0 / len));
        }
        s.checked = t;
    }

    private static void addHole(ClientLevel level, BlockHitResult hit, BulletConfig config, Vec3 dir) {
        if (PassableClip.shape(level.getBlockState(hit.getBlockPos())) != null) {
            meshHole(level, hit, config, dir);
            return;
        }
        if (drawnHole(level, hit, config, dir)) return;
        // where the ray met the hitbox, on the face's axis
        double boxFace = comp(hit.getLocation(), hit.getDirection().getAxis().ordinal());
        if (!hitboxOnly(level.getBlockState(hit.getBlockPos()))) {
            Vec3 onPixels = alongRay(level, hit, dir, new HashMap<>());
            if (onPixels == null) return;
            hit = new BlockHitResult(onPixels, hit.getDirection(), hit.getBlockPos(), false);
        }
        BlockPos lightAt = hit.getBlockPos().relative(hit.getDirection());
        int light = LightCoordsUtil.getLightCoords(level, lightAt);
        boolean scorch = config.onImpact != null;
        int kind = scorch ? SCORCH : (int) (Math.random() * HOLE_KINDS);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * (0.8F + 0.4F * (float) Math.random());
        double[] rect = faceRect(level, hit.getBlockPos(), hit.getDirection());
        Mask mask = null;
        double plane = Double.NaN;
        if (!hitboxOnly(level.getBlockState(hit.getBlockPos()))) {
            mask = mask(level, hit, size * 0.5F, rect);
            if (mask == NO_PIXELS) return;
            plane = mask.plane();
            boolean[] solid = mask.solid();
            boolean all = true;
            for (boolean b : solid) all &= b;
            if (all) mask = null;
        }
        // many marks on one spot (a burst into the same place) cost a lot to draw up close: keep the newest few
        Vec3 at = hit.getLocation();
        Hole oldest = null;
        int stacked = 0;
        for (Hole o : HOLES) {
            if (o.face() != hit.getDirection() || o.pos().distanceToSqr(at) > Math.pow(0.5 * Math.min(size, o.size()), 2)) continue;
            stacked++;
            if (oldest == null) oldest = o;
        }
        if (stacked >= 3 && oldest != null) HOLES.remove(oldest);
        // one-sided (seen only from outside, like the face itself) when the pixels lie on the hitbox's face: a closed
        // box such as a full cube (copper grate) or an iron door, whose far inner face shows through its see-through
        // pixels without its holes. Pixels inside the hitbox (iron bars: a plane in the middle) stay two-sided.
        double onAxis = Double.isNaN(plane) ? comp(hit.getLocation(), hit.getDirection().getAxis().ordinal()) : plane;
        boolean cull = level.getBlockState(hit.getBlockPos()).isCollisionShapeFullBlock(level, hit.getBlockPos()) || Math.abs(onAxis - boxFace) < 1e-3;
        HOLES.addLast(new Hole(hit.getLocation(), hit.getDirection(), (int) (Math.random() * 8), System.nanoTime(), light, kind, size, rect, mask, plane, translucent(level.getBlockState(hit.getBlockPos())),
                cull, null, false, hit.getBlockPos().immutable(), level.getBlockState(hit.getBlockPos()), false, null));
        while (HOLES.size() > Math.max(1, Config.holeMax)) HOLES.removeFirst();
    }

    // A hole on a block with a 3D model inside its hitbox (steel scaffold, poles): where the bullet's line meets the
    // model's first shown pixel, lying in that face's plane at whatever angle, cut to the faces of that plane (in this
    // block and its neighbours) and to their pixels. mesh = {normal x, y, z, then per polygon: vertex count, then
    // x, y, z (from the hole's pos), u, v per vertex}.
    private static void meshHole(ClientLevel level, BlockHitResult hit, BulletConfig config, Vec3 dir) {
        BlockPos pos = hit.getBlockPos();
        Vector3d d = new Vector3d(dir.x, dir.y, dir.z);
        Vector3d o = new Vector3d(hit.getLocation().x - pos.getX(), hit.getLocation().y - pos.getY(), hit.getLocation().z - pos.getZ()).sub(new Vector3d(d).mul(1.5));
        double best = Double.POSITIVE_INFINITY;
        Vector3d at = null, normal = null;
        // a shell (door, trapdoor) stopped the bullet where it came in: the hole is on that face, never further in
        // (its far inner face, seen through the window)
        double reach = PassableClip.shell(level.getBlockState(pos)) ? 1.5 + 0.1 : 2.0;
        for (BakedQuad q : MeshShapes.quads(level.getBlockState(pos))) {
            Vector3d n = normalOf(q);
            if (n == null) continue;
            double den = n.dot(d);
            if (Math.abs(den) < 1e-6) continue;
            double t = n.dot(new Vector3d(q.position0().x(), q.position0().y(), q.position0().z()).sub(o)) / den;
            if (t < 0 || t > reach || t >= best) continue;
            Vector3d pt = new Vector3d(d).mul(t).add(o);
            if (pt.x < -1e-3 || pt.x > 1.001 || pt.y < -1e-3 || pt.y > 1.001 || pt.z < -1e-3 || pt.z > 1.001) continue;
            float[] uv = onQuad(q, pt, 0, 0, 0);
            if (uv == null || !MeshShapes.opaque(q.materialInfo().sprite(), uv[0], uv[1])) continue;
            best = t;
            at = pt;
            normal = n;
        }
        if (at == null) return;
        if (normal.dot(d) > 0) normal.negate();
        boolean scorch = config.onImpact != null;
        int kind = scorch ? SCORCH : (int) (Math.random() * HOLE_KINDS);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * (0.8F + 0.4F * (float) Math.random());
        int turn = (int) (Math.random() * 8);
        double half = size * 0.5;
        // the hole's two axes in the face's plane
        Vector3d ref = Math.abs(normal.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
        Vector3d t1 = new Vector3d(normal).cross(ref).normalize(), t2 = new Vector3d(normal).cross(t1).normalize();
        // the faces in the same plane, here and around
        List<double[]> polys = new ArrayList<>();
        List<Object[]> same = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            BlockPos np = pos.offset(dx, dy, dz);
            BlockState st = level.getBlockState(np);
            if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            for (BakedQuad q : MeshShapes.quads(st)) {
                Vector3d n = normalOf(q);
                if (n == null || Math.abs(n.dot(normal)) < 0.999) continue;
                Vector3d p0 = new Vector3d(q.position0().x() + dx, q.position0().y() + dy, q.position0().z() + dz);
                if (Math.abs(new Vector3d(p0).sub(at).dot(normal)) > 2e-3) continue;
                same.add(new Object[] {q, new int[] {dx, dy, dz}});
            }
        }
        int grid = Math.max(1, (int) Math.ceil(2 * half * 32));
        double cs = 2 * half / grid;
        for (Object[] e : same) {
            BakedQuad q = (BakedQuad) e[0];
            int[] off = (int[]) e[1];
            List<Vector3d> outline = outline(q, off);
            if (seeThrough(q.materialInfo().sprite())) {
                // cell by cell, where the face's pixel is shown
                for (int i = 0; i < grid; i++) for (int j = 0; j < grid; j++) {
                    double cx = -half + (i + 0.5) * cs, cy = -half + (j + 0.5) * cs;
                    Vector3d c = new Vector3d(at).fma(cx, t1).fma(cy, t2);
                    float[] uv = onQuad(q, c, off[0], off[1], off[2]);
                    if (uv == null || !MeshShapes.opaque(q.materialInfo().sprite(), uv[0], uv[1])) continue;
                    addPoly(polys, clip(square(at, t1, t2, cx - cs / 2, cx + cs / 2, cy - cs / 2, cy + cs / 2), outline, normal), at, t1, t2, half, turn);
                }
            } else {
                addPoly(polys, clip(square(at, t1, t2, -half, half, -half, half), outline, normal), at, t1, t2, half, turn);
            }
        }
        if (polys.isEmpty()) return;
        int count = 3;
        for (double[] pl : polys) count += pl.length + 1;
        float[] mesh = new float[count];
        mesh[0] = (float) normal.x;
        mesh[1] = (float) normal.y;
        mesh[2] = (float) normal.z;
        int k = 3;
        for (double[] pl : polys) {
            mesh[k++] = pl.length / 5;
            for (double v : pl) mesh[k++] = (float) v;
        }
        Vec3 world = new Vec3(pos.getX() + at.x, pos.getY() + at.y, pos.getZ() + at.z);
        int light = LightCoordsUtil.getLightCoords(level, pos);
        HOLES.addLast(new Hole(world, hit.getDirection(), turn, System.nanoTime(), light, kind, size, null, null, Double.NaN, false, false, mesh,
                onBoxFace(level, pos, at, normal), pos.immutable(), level.getBlockState(pos), false, null));
        while (HOLES.size() > Math.max(1, Config.holeMax)) HOLES.removeFirst();
    }

    private static long drawnChecked;

    private static boolean stillDrawn(ClientLevel level, Hole h) {
        float[] m = h.mesh();
        Vector3d n = new Vector3d(m[0], m[1], m[2]);
        Vector3d at = new Vector3d(h.pos().x, h.pos().y, h.pos().z);
        for (RenderedMesh.Tri t : RenderedMesh.within(level, new AABB(h.pos(), h.pos()).inflate(0.01))) {
            Vector3d tn = t.normal();
            if (tn == null || Math.abs(tn.dot(n)) < 0.99) continue;
            if (Math.abs(new Vector3d(at).sub(t.corner(0)).dot(tn)) > 3e-3) continue;
            if (t.weights(at, 1e-3) != null) return true;
        }
        return false;
    }

    // a cell of a hole at (cx, cy) on the plane: shown where the triangle under its centre shows a pixel; a cell whose
    // centre is off them all goes by a point of it that is on one
    private static boolean shownAt(List<RenderedMesh.Tri> same, Vector3d world, Vector3d t1, Vector3d t2, double cx, double cy, double cs) {
        double[][] at = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {0.3, 0.3}, {-0.3, 0.3}};
        for (double[] k : at) {
            Vector3d c = new Vector3d(world).fma(cx + k[0] * cs, t1).fma(cy + k[1] * cs, t2);
            for (RenderedMesh.Tri t : same) {
                double[] w = t.weights(c, 1e-6);
                if (w != null) return t.opaqueAt(w);
            }
        }
        return false;
    }

    // A hole on a block a renderer draws (NTM's machines and doors: OBJ models, the block itself invisible or only a
    // part): on the first drawn pixel the bullet's line meets, from a block before the hit to a block past it, lying
    // in that triangle's plane, cut to the drawn triangles in the same plane and to their pixels. false = no renderer
    // draws on this block: the usual holes.
    private static boolean drawnHole(ClientLevel level, BlockHitResult hit, BulletConfig config, Vec3 dir) {
        BlockPos pos = hit.getBlockPos();
        AABB cell = new AABB(pos);
        AABB around = cell.inflate(1.0);
        List<RenderedMesh.Tri> tris = new ArrayList<>(RenderedMesh.within(level, around));
        boolean drawn = false;
        for (RenderedMesh.Tri t : tris) if (t.meets(cell)) {
            drawn = true;
            break;
        }
        // block models too: this block's own, and big ones of blocks around reaching in (a machine's OBJ body on its
        // core block)
        boolean[] reaches = {false};
        java.util.Set<RenderedMesh.Tri> plain = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        tris.addAll(RenderedMesh.models(level, around, pos, cell, reaches, plain));
        if (!drawn && !reaches[0]) return false;
        Vector3d d = new Vector3d(dir.x, dir.y, dir.z);
        Vector3d o = new Vector3d(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z).sub(new Vector3d(d));
        double best = Double.POSITIVE_INFINITY;
        RenderedMesh.Tri met = null;
        for (RenderedMesh.Tri t : tris) {
            double k = t.cross(o, d);
            if (Double.isNaN(k) || k < 0 || k > 2.0 || k >= best) continue;
            Vector3d pt = new Vector3d(d).mul(k).add(o);
            double[] w = t.weights(pt, 1e-4);
            if (w == null || !t.opaqueAt(w)) continue;
            best = k;
            met = t;
        }
        if (met == null) return true;
        // the bullet stops on the hit block's own ordinary model (a wall next to a door whose frame only touches
        // it): the usual hole, carried over onto the neighbours
        if (plain.contains(met)) return false;
        Vector3d normal = met.normal();
        if (normal == null) return true;
        if (normal.dot(d) > 0) normal.negate();
        Vector3d world = new Vector3d(d).mul(best).add(o);
        // from here on relative to the block, as meshHole
        Vector3d at = new Vector3d(world).sub(pos.getX(), pos.getY(), pos.getZ());
        boolean scorch = config.onImpact != null;
        int kind = scorch ? SCORCH : (int) (Math.random() * HOLE_KINDS);
        float size = (scorch ? Config.scorchSize : Config.holeSize) * (0.8F + 0.4F * (float) Math.random());
        int turn = (int) (Math.random() * 8);
        double half = size * 0.5;
        Vector3d ref = Math.abs(normal.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
        Vector3d t1 = new Vector3d(normal).cross(ref).normalize(), t2 = new Vector3d(normal).cross(t1).normalize();
        AABB reach = new AABB(world.x, world.y, world.z, world.x, world.y, world.z).inflate(half * 1.5);
        List<double[]> polys = new ArrayList<>();
        int grid = Math.max(1, (int) Math.ceil(2 * half * 32));
        double cs = 2 * half / grid;
        Vector3d origin = new Vector3d(pos.getX(), pos.getY(), pos.getZ());
        // the drawn triangles in the hole's plane
        List<RenderedMesh.Tri> same = new ArrayList<>();
        boolean clear = false;
        for (RenderedMesh.Tri t : tris) {
            if (!t.meets(reach)) continue;
            Vector3d tn = t.normal();
            if (tn == null || Math.abs(tn.dot(normal)) < 0.999) continue;
            if (Math.abs(t.corner(0).sub(world).dot(normal)) > 2e-3) continue;
            same.add(t);
            clear |= t.tex().seeThrough();
        }
        if (!clear) {
            for (RenderedMesh.Tri t : same) {
                List<Vector3d> outline = List.of(t.corner(0).sub(origin), t.corner(1).sub(origin), t.corner(2).sub(origin));
                addPoly(polys, clip(square(at, t1, t2, -half, half, -half, half), outline, normal), at, t1, t2, half, turn);
            }
        } else {
            // cell by cell: shown or not is decided once for the whole plane, by the triangle under the cell (not
            // per triangle: a cell on the seam of two would be lost from both, a jagged edge along the diagonal),
            // then the cell is cut to each triangle it lies on
            for (int i = 0; i < grid; i++) for (int j = 0; j < grid; j++) {
                double cx = -half + (i + 0.5) * cs, cy = -half + (j + 0.5) * cs;
                if (!shownAt(same, world, t1, t2, cx, cy, cs)) continue;
                List<Vector3d> sq = square(at, t1, t2, cx - cs / 2, cx + cs / 2, cy - cs / 2, cy + cs / 2);
                for (RenderedMesh.Tri t : same) {
                    List<Vector3d> outline = List.of(t.corner(0).sub(origin), t.corner(1).sub(origin), t.corner(2).sub(origin));
                    addPoly(polys, clip(sq, outline, normal), at, t1, t2, half, turn);
                }
            }
        }
        if (polys.isEmpty()) return true;
        int count = 3;
        for (double[] pl : polys) count += pl.length + 1;
        float[] mesh = new float[count];
        mesh[0] = (float) normal.x;
        mesh[1] = (float) normal.y;
        mesh[2] = (float) normal.z;
        int k = 3;
        for (double[] pl : polys) {
            mesh[k++] = pl.length / 5;
            for (double v : pl) mesh[k++] = (float) v;
        }
        BlockPos lightAt = BlockPos.containing(world.x + normal.x * 0.05, world.y + normal.y * 0.05, world.z + normal.z * 0.05);
        int light = LightCoordsUtil.getLightCoords(level, lightAt);
        Follow follow = met.anchor() == null ? null : new Follow(met.anchor(), world, mesh, light);
        HOLES.addLast(new Hole(new Vec3(world.x, world.y, world.z), hit.getDirection(), turn, System.nanoTime(), light, kind, size, null, null, Double.NaN, false, false, mesh,
                met.oneSided(), pos.immutable(), level.getBlockState(pos), met.moving(), follow));
        while (HOLES.size() > Math.max(1, Config.holeMax)) HOLES.removeFirst();
        return true;
    }

    // The hole's face lies on the outside of the block's hitbox (a door, a trapdoor, a lantern): seen only from in
    // front, like the face itself, since its far inner face shows through the see-through pixels without the hole.
    // Faces inside the hitbox (scaffold poles, a ladder's rungs) are seen from both sides.
    private static boolean onBoxFace(ClientLevel level, BlockPos pos, Vector3d at, Vector3d normal) {
        int ax = Math.abs(normal.x) > 0.999 ? 0 : Math.abs(normal.y) > 0.999 ? 1 : Math.abs(normal.z) > 0.999 ? 2 : -1;
        if (ax < 0) return false;
        double[] p = {at.x, at.y, at.z};
        double out = ax == 0 ? normal.x : ax == 1 ? normal.y : normal.z;
        for (var b : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
            double[] lo = {b.minX, b.minY, b.minZ}, hi = {b.maxX, b.maxY, b.maxZ};
            if (Math.abs(p[ax] - (out > 0 ? hi[ax] : lo[ax])) > 2e-3) continue;
            boolean inside = true;
            for (int i = 0; i < 3; i++) if (i != ax && (p[i] < lo[i] - 1e-3 || p[i] > hi[i] + 1e-3)) inside = false;
            if (inside) return true;
        }
        return false;
    }

    private static Vector3d normalOf(BakedQuad q) {
        Vector3d p0 = v(q.position0()), p1 = v(q.position1()), p2 = v(q.position2()), p3 = v(q.position3());
        Vector3d n = new Vector3d(p1).sub(p0).cross(new Vector3d(p2).sub(p0));
        if (n.lengthSquared() < 1e-12) n = new Vector3d(p2).sub(p0).cross(new Vector3d(p3).sub(p0));
        if (n.lengthSquared() < 1e-12) return null;
        return n.normalize();
    }

    private static Vector3d v(org.joml.Vector3fc p) {
        return new Vector3d(p.x(), p.y(), p.z());
    }

    // atlas (u, v) of the quad at the point in its plane (block offset off), null = not on it
    private static float[] onQuad(BakedQuad q, Vector3d pt, int ox, int oy, int oz) {
        for (int t = 0; t < 2; t++) {
            int a = 0, b = t == 0 ? 1 : 2, c = t == 0 ? 2 : 3;
            Vector3d pa = v(q.position(a)).add(ox, oy, oz), pb = v(q.position(b)).add(ox, oy, oz), pc = v(q.position(c)).add(ox, oy, oz);
            Vector3d e0 = new Vector3d(pb).sub(pa), e1 = new Vector3d(pc).sub(pa), e2 = new Vector3d(pt).sub(pa);
            double d00 = e0.dot(e0), d01 = e0.dot(e1), d11 = e1.dot(e1), d20 = e2.dot(e0), d21 = e2.dot(e1);
            double den = d00 * d11 - d01 * d01;
            if (Math.abs(den) < 1e-14) continue;
            double wb = (d11 * d20 - d01 * d21) / den, wc = (d00 * d21 - d01 * d20) / den, wa = 1 - wb - wc;
            if (wa < -1e-4 || wb < -1e-4 || wc < -1e-4) continue;
            float u = (float) (wa * UVPair.unpackU(q.packedUV(a)) + wb * UVPair.unpackU(q.packedUV(b)) + wc * UVPair.unpackU(q.packedUV(c)));
            float vv = (float) (wa * UVPair.unpackV(q.packedUV(a)) + wb * UVPair.unpackV(q.packedUV(b)) + wc * UVPair.unpackV(q.packedUV(c)));
            return new float[] {u, vv};
        }
        return null;
    }

    private static final Map<TextureAtlasSprite, Boolean> SEE_THROUGH = new HashMap<>();

    private static boolean seeThrough(TextureAtlasSprite sprite) {
        return SEE_THROUGH.computeIfAbsent(sprite, sp -> {
            var c = sp.contents();
            for (int y = 0; y < c.height(); y++) for (int x = 0; x < c.width(); x++) if (c.isTransparent(0, x, y)) return true;
            return false;
        });
    }

    private static List<Vector3d> outline(BakedQuad q, int[] off) {
        List<Vector3d> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Vector3d p = v(q.position(i)).add(off[0], off[1], off[2]);
            if (out.isEmpty() || out.get(out.size() - 1).distanceSquared(p) > 1e-12) out.add(p);
        }
        if (out.size() > 1 && out.get(0).distanceSquared(out.get(out.size() - 1)) < 1e-12) out.remove(out.size() - 1);
        return out;
    }

    private static List<Vector3d> square(Vector3d at, Vector3d t1, Vector3d t2, double x0, double x1, double y0, double y1) {
        List<Vector3d> out = new ArrayList<>();
        double[][] c = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (double[] k : c) out.add(new Vector3d(at).fma(k[0], t1).fma(k[1], t2));
        return out;
    }

    // the polygon cut to the inside of a convex outline in the same plane
    private static List<Vector3d> clip(List<Vector3d> poly, List<Vector3d> outline, Vector3d normal) {
        if (outline.size() < 3) return List.of();
        Vector3d centre = new Vector3d();
        for (Vector3d p : outline) centre.add(p);
        centre.div(outline.size());
        for (int i = 0; i < outline.size() && !poly.isEmpty(); i++) {
            Vector3d a = outline.get(i), b = outline.get((i + 1) % outline.size());
            Vector3d edge = new Vector3d(b).sub(a);
            Vector3d in = new Vector3d(normal).cross(edge);
            if (in.dot(new Vector3d(centre).sub(a)) < 0) in.negate();
            List<Vector3d> next = new ArrayList<>();
            for (int j = 0; j < poly.size(); j++) {
                Vector3d p = poly.get(j), r = poly.get((j + 1) % poly.size());
                double sp = in.dot(new Vector3d(p).sub(a)), sr = in.dot(new Vector3d(r).sub(a));
                if (sp >= 0) next.add(p);
                if ((sp >= 0) != (sr >= 0)) next.add(new Vector3d(p).lerp(r, sp / (sp - sr)));
            }
            poly = next;
        }
        return poly;
    }

    // a cut polygon as x, y, z from the hole's centre and the hole texture's u, v (turned / mirrored like quad())
    private static void addPoly(List<double[]> polys, List<Vector3d> poly, Vector3d at, Vector3d t1, Vector3d t2, double half, int turn) {
        if (poly.size() < 3) return;
        double[] out = new double[poly.size() * 5];
        int k = 0;
        for (Vector3d p : poly) {
            Vector3d r = new Vector3d(p).sub(at);
            float a = (float) ((r.dot(t1) + half) / (2 * half)), b = (float) ((r.dot(t2) + half) / (2 * half));
            if ((turn & 4) != 0) a = 1F - a;
            float tu = a, tv = b;
            switch (turn & 3) {
                case 1 -> { tu = b; tv = 1F - a; }
                case 2 -> { tu = 1F - a; tv = 1F - b; }
                case 3 -> { tu = 1F - b; tv = a; }
                default -> { }
            }
            out[k++] = r.x;
            out[k++] = r.y;
            out[k++] = r.z;
            out[k++] = tu;
            out[k++] = 1F - tv;
        }
        polys.add(out);
    }

    private static void meshQuads(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 cam, int alpha) {
        Follow f = h.follow();
        float[] m = f != null ? f.mesh : h.mesh();
        if (m == null) return;
        Vec3 pos = f != null ? f.pos : h.pos();
        int light = f != null ? f.light : h.light();
        float nx = m[0], ny = m[1], nz = m[2];
        // towards the camera, as in quad(); a one-sided hole seen from behind is not drawn
        if ((cam.x - pos.x) * nx + (cam.y - pos.y) * ny + (cam.z - pos.z) * nz < 0) {
            if (h.front()) return;
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float bx = (float) (pos.x - cam.x) + nx * 0.002F, by = (float) (pos.y - cam.y) + ny * 0.002F, bz = (float) (pos.z - cam.z) + nz * 0.002F;
        int color = (alpha << 24) | 0xFFFFFF;
        int k = 3;
        while (k < m.length) {
            int n = (int) m[k++];
            int base = k;
            // a fan of quads (the last vertex twice where a triangle is left)
            for (int i = 1; i + 1 < n; i += 2) {
                int[] idx = {0, i, i + 1, Math.min(i + 2, n - 1)};
                for (int j : idx) {
                    int o = base + j * 5;
                    vc.addVertex(p, bx + m[o], by + m[o + 1], bz + m[o + 2]).setColor(color).setUv(m[o + 3], m[o + 4])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
                }
            }
            k = base + n * 5;
        }
    }

    // a block drawn with the translucent terrain (glass): its holes are drawn after it
    private static boolean translucent(BlockState state) {
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return false;
        var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
        for (var part : parts) {
            for (Direction d : Direction.values())
                for (BakedQuad q : part.getQuads(d)) if (q.materialInfo().layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT) return true;
            for (BakedQuad q : part.getQuads(null)) if (q.materialInfo().layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT) return true;
        }
        return false;
    }

    private static final Mask NO_PIXELS = new Mask(0, 0, 0, 0, new boolean[0], Double.NaN);
    private static String hitboxListOf;
    private static java.util.regex.Pattern hitboxList;

    // blocks in holeHitboxBlocks: the hole follows only the hitbox
    private static boolean hitboxOnly(BlockState state) {
        if (!Config.holeHitboxBlocks.equals(hitboxListOf)) {
            hitboxListOf = Config.holeHitboxBlocks;
            StringBuilder re = new StringBuilder();
            for (String id : hitboxListOf.split(",")) {
                id = id.trim();
                if (id.isEmpty()) continue;
                if (re.length() > 0) re.append('|');
                for (String piece : id.split("\\*", -1)) re.append(java.util.regex.Pattern.quote(piece)).append(".*");
                re.setLength(re.length() - 2);
            }
            hitboxList = re.length() == 0 ? null : java.util.regex.Pattern.compile(re.toString());
        }
        if (hitboxList == null) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return hitboxList.matcher(id).matches();
    }

    // Which cells of the hole's square show the block's own pixels: the block model's faces along the hit face's axis
    // (any depth inside the block, so the flat pane of iron bars counts) and their textures' alpha. A block drawn
    // without a block model (an NTM machine: its model does not follow the hitbox) shows none.
    private static Mask mask(ClientLevel level, BlockHitResult hit, float half, double[] rect) {
        Direction face = hit.getDirection();
        int[] ax = axes(face);
        int normal = face.getAxis().ordinal();
        double pu = comp(hit.getLocation(), ax[0]), pv = comp(hit.getLocation(), ax[1]);
        double u0 = Math.max(pu - half, rect[0]), u1 = Math.min(pu + half, rect[1]);
        double v0 = Math.max(pv - half, rect[2]), v1 = Math.min(pv + half, rect[3]);
        if (u0 >= u1 || v0 >= v1) return NO_PIXELS;
        int i0 = (int) Math.floor(u0 * 16), i1 = (int) Math.ceil(u1 * 16), j0 = (int) Math.floor(v0 * 16), j1 = (int) Math.ceil(v1 * 16);
        int w = i1 - i0, h = j1 - j0;
        boolean[] solid = new boolean[w * h];
        boolean any = false;
        // the hole lies on the shown pixels nearest to the side it was hit from
        double plane = Double.NaN;
        int step = face.getAxisDirection().getStep();
        Map<BlockPos, List<BakedQuad>> quads = new HashMap<>();
        int depth = normal == 0 ? hit.getBlockPos().getX() : normal == 1 ? hit.getBlockPos().getY() : hit.getBlockPos().getZ();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double cu = (i0 + i + 0.5) / 16.0, cv = (j0 + j + 0.5) / 16.0;
                int[] at = new int[3];
                at[ax[0]] = (int) Math.floor(cu);
                at[ax[1]] = (int) Math.floor(cv);
                at[normal] = depth;
                BlockPos bp = new BlockPos(at[0], at[1], at[2]);
                BlockState st = level.getBlockState(bp);
                boolean s;
                if (hitboxOnly(st)) {
                    s = true;
                } else {
                    double d = shows(level, bp, quads, face, ax, cu - at[ax[0]], cv - at[ax[1]]);
                    s = !Double.isNaN(d);
                    if (s && (Double.isNaN(plane) || (d + depth - plane) * step > 0)) plane = d + depth;
                }
                solid[j * w + i] = s;
                any |= s;
            }
        }
        if (!any) return NO_PIXELS;
        // never in front of the hit point (the hitbox's face)
        if (!Double.isNaN(plane) && (plane - comp(hit.getLocation(), normal)) * step > 0) plane = comp(hit.getLocation(), normal);
        return new Mask(i0, j0, w, h, solid, plane);
    }

    // Where along the face's axis the block's model shows a pixel at (lu, lv) of the block (0..1 inside the block),
    // NaN = nowhere. Only the model's face nearest to the hit side counts: through a see-through pixel of a cube's
    // front (copper grate) the back face shows, but the hole is not on it.
    private static double shows(ClientLevel level, BlockPos pos, Map<BlockPos, List<BakedQuad>> cache, Direction face, int[] ax, double lu, double lv) {
        int normal = face.getAxis().ordinal();
        int step = face.getAxisDirection().getStep();
        double near = Double.NaN;
        boolean shown = false;
        for (BakedQuad q : quadsOf(level, pos, cache, face.getAxis())) {
            int px = sample(q, ax, lu, lv);
            if (px < 0) continue;
            double depth = q.position0().get(normal);
            // behind the nearest face found so far: hidden by it
            if (!Double.isNaN(near) && (depth - near) * step < -1e-4) continue;
            if (Double.isNaN(near) || (depth - near) * step > 1e-4) {
                near = depth;
                shown = false;
            }
            if (px == 1) shown = true;
        }
        return shown ? near : Double.NaN;
    }

    // The block model's faces along the axis that are drawn (a face hidden by its neighbour, such as the end of
    // iron bars joined to more bars, is left out); none for a block drawn without a block model.
    private static List<BakedQuad> quadsOf(ClientLevel level, BlockPos pos, Map<BlockPos, List<BakedQuad>> cache, Direction.Axis axis) {
        return cache.computeIfAbsent(pos.immutable(), at -> {
            List<BakedQuad> out = new ArrayList<>();
            BlockState st = level.getBlockState(at);
            if (st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return out;
            var model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(st);
            List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
            for (var part : parts) {
                for (Direction d : Direction.values()) {
                    if (!net.minecraft.world.level.block.Block.shouldRenderFace(st, level.getBlockState(at.relative(d)), d)) continue;
                    for (BakedQuad q : part.getQuads(d)) if (q.direction().getAxis() == axis) out.add(q);
                }
                for (BakedQuad q : part.getQuads(null)) if (q.direction().getAxis() == axis) out.add(q);
            }
            return out;
        });
    }

    // the quad's pixel at (lu, lv) of the block on the face's two axes: -1 the quad is not there, 0 see-through, 1 shown
    private static int sample(BakedQuad q, int[] ax, double lu, double lv) {
        org.joml.Vector3fc p0 = q.position0(), p1 = q.position1(), p3 = q.position3();
        double ax0 = p0.get(ax[0]), ay0 = p0.get(ax[1]);
        double e1x = p1.get(ax[0]) - ax0, e1y = p1.get(ax[1]) - ay0, e2x = p3.get(ax[0]) - ax0, e2y = p3.get(ax[1]) - ay0;
        double det = e1x * e2y - e1y * e2x;
        if (Math.abs(det) < 1e-9) return -1;
        double dx = lu - ax0, dy = lv - ay0;
        double a = (dx * e2y - dy * e2x) / det, b = (e1x * dy - e1y * dx) / det;
        if (a < -1e-4 || a > 1 + 1e-4 || b < -1e-4 || b > 1 + 1e-4) return -1;
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        var contents = sprite.contents();
        float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
        if (du == 0F || dv == 0F) return 1;
        long uv0 = q.packedUV0(), uv1 = q.packedUV1(), uv3 = q.packedUV3();
        float tu = UVPair.unpackU(uv0), tv = UVPair.unpackV(uv0);
        float u = (float) (tu + a * (UVPair.unpackU(uv1) - tu) + b * (UVPair.unpackU(uv3) - tu));
        float v = (float) (tv + a * (UVPair.unpackV(uv1) - tv) + b * (UVPair.unpackV(uv3) - tv));
        int px = Math.clamp((int) ((u - sprite.getU0()) / du * contents.width()), 0, contents.width() - 1);
        int py = Math.clamp((int) ((v - sprite.getV0()) / dv * contents.height()), 0, contents.height() - 1);
        return contents.isTransparent(0, px, py) ? 0 : 1;
    }

    // Where the bullet's line, entering the block's hitbox at the hit, meets the model's first face inside the block
    // (iron bars: the thin pane behind a thicker hitbox; hit at a slant the two are far apart). null = it meets none,
    // or that face is see-through there.
    private static Vec3 alongRay(ClientLevel level, BlockHitResult hit, Vec3 dir, Map<BlockPos, List<BakedQuad>> cache) {
        Direction face = hit.getDirection();
        int[] ax = axes(face);
        int normal = face.getAxis().ordinal();
        int step = face.getAxisDirection().getStep();
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        double dn = comp(dir, normal);
        if (Math.abs(dn) < 1e-6) return at;
        double base = normal == 0 ? pos.getX() : normal == 1 ? pos.getY() : pos.getZ();
        double hp = comp(at, normal) - base;
        List<BakedQuad> quads = quadsOf(level, pos, cache, face.getAxis());
        // the model's planes from the hit side inward
        java.util.TreeSet<Double> planes = new java.util.TreeSet<>((x, y) -> Double.compare((hp - x) * step, (hp - y) * step));
        for (BakedQuad q : quads) {
            double d = q.position0().get(normal);
            if ((hp - d) * step >= -1e-4) planes.add(d);
        }
        for (double d : planes) {
            Vec3 p = at.add(dir.scale((d - hp) / dn));
            double lu = comp(p, ax[0]) - (ax[0] == 0 ? pos.getX() : ax[0] == 1 ? pos.getY() : pos.getZ());
            double lv = comp(p, ax[1]) - (ax[1] == 0 ? pos.getX() : ax[1] == 1 ? pos.getY() : pos.getZ());
            if (lu < -1e-4 || lu > 1 + 1e-4 || lv < -1e-4 || lv > 1 + 1e-4) return null;
            // the first of the model's faces on the line decides: its pixel shown = the hole is here; see-through =
            // none (the bullet was stopped on the edge of a pixel; a hole on the back face, seen through the front, looks wrong)
            boolean covered = false;
            for (BakedQuad q : quads) {
                if (Math.abs(q.position0().get(normal) - d) > 1e-4) continue;
                int px = sample(q, ax, lu, lv);
                if (px == 1) return p;
                covered |= px == 0;
            }
            if (covered) return null;
        }
        return null;
    }

    // a thin ribbon from tail to head, turned toward the camera; bright at the head, fading to nothing at the tail
    private static void streak(Matrix4f m, VertexConsumer vc, float[] k) {
        Vector3f head = new Vector3f(k[0], k[1], k[2]), tail = new Vector3f(k[3], k[4], k[5]);
        Vector3f along = new Vector3f(head).sub(tail);
        if (along.lengthSquared() < 1e-8F) return;
        Vector3f side = new Vector3f(along).cross(head);
        if (side.lengthSquared() < 1e-10F) return;
        side.normalize();
        // never thinner than about 1.5 screen pixels, however far
        float least = 1.0F * k[8];
        float wh = Math.max(Config.tracerWidth, least * head.length()), wt = Math.max(Config.tracerWidth * 0.5F, least * tail.length());
        int lightColor = Float.floatToRawIntBits(k[6]) | 0xFF000000, darkColor = (Float.floatToRawIntBits(k[7]) & 0x00FFFFFF) | 0x30000000;
        Vector3f hs = new Vector3f(side).mul(wh), ts = new Vector3f(side).mul(wt);
        Vector3f[] q = {new Vector3f(head).add(hs), new Vector3f(head).sub(hs), new Vector3f(tail).sub(ts), new Vector3f(tail).add(ts)};
        int[] c = {lightColor, lightColor, darkColor, darkColor};
        // both windings: seen from either side
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < 4; j++) {
                int i = pass == 0 ? j : 3 - j;
                vc.addVertex(m, q[i].x, q[i].y, q[i].z).setColor(c[i]);
            }
        }
    }

    private static void holes(SubmitCustomGeometryEvent event, Vec3 cam, long now) {
        if (HOLES.isEmpty()) return;
        long life = (long) (Math.max(0.1F, Config.holeLife) * 1e9);
        while (!HOLES.isEmpty() && now - HOLES.peekFirst().born() > life) HOLES.removeFirst();
        // the block it was made on was broken, replaced or changed its shape (a door opened): gone with it. Not on
        // a change that leaves the block where it was (a door powered, a block waterlogged)
        var lvl = Minecraft.getInstance().level;
        if (lvl != null) HOLES.removeIf(h -> {
            BlockState st = lvl.getBlockState(h.block());
            if (st == h.state()) return false;
            if (st.getBlock() != h.state().getBlock()) return true;
            // on a moving part: it goes with the part, not with the block's shape (a door's open state)
            if (h.follow() != null) return false;
            return net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(st.getShape(lvl, h.block()), h.state().getShape(lvl, h.block()),
                    net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
        });
        // holes on what a renderer draws (a door's leaf): gone when it is no longer drawn there (the door opened)
        if (lvl != null && now - drawnChecked > 300_000_000L) {
            drawnChecked = now;
            HOLES.removeIf(h -> h.drawn() && h.follow() == null && !stillDrawn(lvl, h));
        }
        if (lvl != null) follow(lvl, now);
        if (HOLES.isEmpty() || !Config.bulletHoles) return;
        for (int group = 0; group < 4 * (SCORCH + 1); group++) {
            int kind = group % (SCORCH + 1);
            boolean late = (group / (SCORCH + 1) & 1) != 0, cull = (group / (SCORCH + 1) & 2) != 0;
            List<Hole> list = new ArrayList<>();
            for (Hole h : HOLES) if (h.kind() == kind && h.late() == late && h.cull() == cull) list.add(h);
            if (list.isEmpty()) continue;
            RenderType type = HoleRender.type(TEXTURES[kind], late, cull);
            // holes on glass go after the glass but before the translucent particles drawn in the same phase, which
            // write depth and would hide them (smoke over a hole): an order before 0 runs first
            net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer draw = (p, vc) -> {
                for (Hole h : list) {
                    double age = (now - h.born()) / 1e9;
                    double fadeFrom = Math.max(0.0, life / 1e9 - 3.0);
                    int alpha = (int) (255 * Math.clamp(1.0 - (age - fadeFrom) / 3.0, 0.0, 1.0));
                    if (alpha <= 0) continue;
                    float half = h.size() * 0.5F;
                    if (h.mesh() != null) {
                        meshQuads(p, vc, h, cam, alpha);
                        continue;
                    }
                    Mask m = h.mask();
                    if (m == null) {
                        quad(p, vc, h, cam, half, alpha, h.rect());
                        continue;
                    }
                    // runs of shown cells, row by row
                    for (int j = 0; j < m.h(); j++) {
                        for (int i = 0; i < m.w(); i++) {
                            if (!m.solid()[j * m.w() + i]) continue;
                            int e = i;
                            while (e + 1 < m.w() && m.solid()[j * m.w() + e + 1]) e++;
                            double[] r = h.rect();
                            double[] cell = {Math.max(r[0], (m.u0() + i) / 16.0), Math.min(r[1], (m.u0() + e + 1) / 16.0),
                                    Math.max(r[2], (m.v0() + j) / 16.0), Math.min(r[3], (m.v0() + j + 1) / 16.0)};
                            quad(p, vc, h, cam, half, alpha, cell);
                            i = e;
                        }
                    }
                }
            };
            if (late && event.getSubmitNodeCollector() instanceof net.minecraft.client.renderer.SubmitNodeStorage storage)
                storage.order(-1).submitCustomGeometry(event.getPoseStack(), type, draw);
            else
                event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type, draw);
        }
    }

    // the face's two axes: u, v as world axis indices (0 x, 1 y, 2 z)
    private static int[] axes(Direction face) {
        return switch (face.getAxis()) {
            case X -> new int[] {2, 1};
            case Y -> new int[] {0, 2};
            default -> new int[] {0, 1};
        };
    }

    private static double comp(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    // The part of the face a hole may cover: the hit block's hitbox face, carried over onto a neighbour in the same
    // wall (its hitbox has a face in the same plane, not covered by a block in front), so a mark on the seam of two
    // blocks is not cut there: a wall of full blocks, a row of iron bars, scaffolding.
    private static double[] faceRect(ClientLevel level, BlockPos pos, Direction face) {
        int[] ax = axes(face);
        int normal = face.getAxis().ordinal();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        AABB box = bounds(level, pos);
        double plane = positive ? comp(box.getMaxPosition(), normal) : comp(box.getMinPosition(), normal);
        double[] r = {comp(box.getMinPosition(), ax[0]), comp(box.getMaxPosition(), ax[0]), comp(box.getMinPosition(), ax[1]), comp(box.getMaxPosition(), ax[1])};
        for (Direction d : Direction.values()) {
            if (d.getAxis() == face.getAxis()) continue;
            int axis = d.getAxis().ordinal();
            boolean up = d.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            int slot = (axis == ax[0] ? 0 : 2) + (up ? 1 : 0);
            // only where this block's face reaches its side of the block
            double edge = up ? Math.ceil(r[slot] - 1e-4) : Math.floor(r[slot] + 1e-4);
            if (Math.abs(r[slot] - edge) > 1e-4) continue;
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue;
            AABB nb = bounds(level, n);
            double np = positive ? comp(nb.getMaxPosition(), normal) : comp(nb.getMinPosition(), normal);
            if (Math.abs(np - plane) > 1e-4) continue;
            BlockPos front = n.relative(face);
            if (level.getBlockState(front).isCollisionShapeFullBlock(level, front)) continue;
            r[slot] = up ? comp(nb.getMaxPosition(), axis) : comp(nb.getMinPosition(), axis);
        }
        return r;
    }

    private static AABB bounds(ClientLevel level, BlockPos pos) {
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }

    // the hole as a square on the face, cut to its rect; turned and mirrored by h.turn (0..7)
    private static void quad(PoseStack.Pose p, VertexConsumer vc, Hole h, Vec3 cam, float size, int alpha, double[] clip) {
        Direction f = h.face();
        int[] ax = axes(f);
        double pu = comp(h.pos(), ax[0]), pv = comp(h.pos(), ax[1]);
        double u0 = Math.max(pu - size, clip[0]), u1 = Math.min(pu + size, clip[1]);
        double v0 = Math.max(pv - size, clip[2]), v1 = Math.min(pv + size, clip[3]);
        if (u0 >= u1 || v0 >= v1) return;
        int normalAxis = f.getAxis().ordinal();
        double base = Double.isNaN(h.plane()) ? comp(h.pos(), normalAxis) : h.plane();
        // lifted off the face towards the camera: a two-sided hole (iron bars) seen from behind would otherwise lie
        // behind the bars' own pixels and show only where the depth bias wins, cut off along a straight line
        int side = f.getAxisDirection().getStep();
        if (!h.cull()) {
            double d = comp(cam, normalAxis) - base;
            if (d != 0) side = d > 0 ? 1 : -1;
        }
        double plane = base + 0.002 * side;
        Vector3f n = new Vector3f(f.getStepX(), f.getStepY(), f.getStepZ());
        if (side != f.getAxisDirection().getStep()) n.negate();
        int color = (alpha << 24) | 0xFFFFFF;
        double[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        // counter-clockwise seen from outside the face (its front for a one-sided hole): the face's u, v axes turn
        // the other way on east / west and up / down faces
        boolean flip = (f.getAxis() == Direction.Axis.Z ? 1 : -1) * f.getAxisDirection().getStep() < 0;
        {
            for (int j = 0; j < 4; j++) {
                double[] c = corners[flip ? 3 - j : j];
                // where this corner falls on the hole's texture, 0..1, then turned / mirrored
                float a = (float) ((c[0] - (pu - size)) / (2 * size)), b = (float) ((c[1] - (pv - size)) / (2 * size));
                if ((h.turn() & 4) != 0) a = 1F - a;
                float tu = a, tv = b;
                switch (h.turn() & 3) {
                    case 1 -> { tu = b; tv = 1F - a; }
                    case 2 -> { tu = 1F - a; tv = 1F - b; }
                    case 3 -> { tu = 1F - b; tv = a; }
                    default -> { }
                }
                double[] w = new double[3];
                w[ax[0]] = c[0];
                w[ax[1]] = c[1];
                w[normalAxis] = plane;
                vc.addVertex(p, (float) (w[0] - cam.x), (float) (w[1] - cam.y), (float) (w[2] - cam.z)).setColor(color).setUv(tu, 1F - tv)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(h.light()).setNormal(p, n.x, n.y, n.z);
            }
        }
    }
}

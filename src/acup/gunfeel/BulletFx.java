package acup.gunfeel;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// Client side of BulletRules, from the bullets Tracers flies: what a block hit gives off, a bullet whizzing past the
// player, and the sound a bullet makes in flight.
public final class BulletFx {
    private BulletFx() {
    }

    private static @Nullable SoundEvent event(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return null;
        return BuiltInRegistries.SOUND_EVENT.getOptional(key).orElseGet(() -> SoundEvent.createVariableRangeEvent(key));
    }

    public static void blockHit(ClientLevel level, BlockHitResult hit, Vec3 dir) {
        if (!Config.bulletFx) return;
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (state.isAir()) return;
        BulletRules.Effect fx = BulletRules.hit(state);
        if (fx.empty()) return;
        Vec3 at = hit.getLocation();
        Direction face = hit.getDirection();
        Vec3 hitAt = at;
        // a hit inside the block's collision box (a bar of a grate or scaffold, inside its full cube): the crumbs start
        // where the bullet came into the box, else they are stuck inside it
        // (or of the grate in front of it the bullet came through): back along its line until out of every one
        Vec3 back = dir.normalize().scale(-0.04);
        Vec3 probe = at.add(new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(0.02));
        boolean walked = false;
        for (int i = 0; i < 80 && insideCollision(level, probe); i++) {
            probe = probe.add(back);
            walked = true;
        }
        if (walked) {
            at = probe;
            face = Direction.getApproximateNearest(-dir.x, -dir.y, -dir.z);
        }
        Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        // the bullet's direction bounced off the face, so the bits fly back towards the shooter
        Vec3 out = dir.subtract(n.scale(2 * dir.dot(n))).normalize();
        RandomSource r = level.getRandom();
        Vec3 p0 = at.add(n.scale(0.02));
        Crumbs drawn = null;
        boolean looked = false;
        // a block drawn by its renderer (NTM's machines) has no texture for crumbs (the purple and black "missing"
        // squares) or an odd one (its item's icon): crumbs of the colours its model has where the bullet hit
        boolean byRenderer = noParticleTexture(state) || state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL;
        if (HIT_NOTED.merge(state.getBlock(), 1, Integer::sum) <= 5)
            GunFeel.LOGGER.info("Bullet hit effects on {}: render shape {}, particle texture {}, {} particle kinds, sound {}",
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), state.getRenderShape(),
                    noParticleTexture(state) ? "missing" : "ok", fx.particles().size(), fx.sound() == null ? "none" : fx.sound().id());
        // only full blocks give off particles: on grates, scaffold, slabs and the like they got stuck inside the
        // model; the sound stays
        boolean full = state.isCollisionShapeFullBlock(level, hit.getBlockPos());
        for (BulletRules.Particle part : full ? fx.particles() : java.util.List.<BulletRules.Particle>of()) {
            if (part.id().equalsIgnoreCase("block") && byRenderer) {
                if (!looked) {
                    looked = true;
                    drawn = Crumbs.at(level, hit.getBlockPos(), hitAt, dir);
                }
                if (drawn == null) {
                    // no drawn model there: the block's own crumbs after all (no texture for them: steel-grey ones)
                    ParticleOptions o = BulletServer.particle("block", noParticleTexture(state)
                            ? net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState() : state);
                    for (int i = 0; o != null && i < part.count(); i++) {
                        double sp = 0.05 + r.nextDouble() * 0.15;
                        level.addParticle(o, p0.x, p0.y, p0.z, (out.x + r.nextGaussian() * 0.35) * sp, (out.y + r.nextGaussian() * 0.35) * sp + 0.03,
                                (out.z + r.nextGaussian() * 0.35) * sp);
                    }
                }
                if (drawn != null) {
                    for (int i = 0; i < part.count(); i++) {
                        double sp = 0.05 + r.nextDouble() * 0.15;
                        drawn.spawn(level, p0, (out.x + r.nextGaussian() * 0.35) * sp, (out.y + r.nextGaussian() * 0.35) * sp + 0.03,
                                (out.z + r.nextGaussian() * 0.35) * sp);
                    }
                }
                continue;
            }
            ParticleOptions o = BulletServer.particle(part.id(), state);
            if (o == null) continue;
            for (int i = 0; i < part.count(); i++) {
                double sp = 0.05 + r.nextDouble() * 0.15;
                level.addParticle(o, p0.x, p0.y, p0.z, (out.x + r.nextGaussian() * 0.35) * sp, (out.y + r.nextGaussian() * 0.35) * sp + 0.03,
                        (out.z + r.nextGaussian() * 0.35) * sp);
            }
        }
        BulletRules.Sound s = fx.sound();
        if (s == null) return;
        SoundEvent e = s.id().equalsIgnoreCase("block") ? state.getSoundType().getHitSound() : event(s.id());
        if (e == null) return;
        level.playLocalSound(at.x, at.y, at.z, e, SoundSource.PLAYERS, s.volume(), s.pitch() * (0.9F + r.nextFloat() * 0.2F), false);
    }

    private static boolean insideCollision(ClientLevel level, Vec3 p) {
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(p);
        BlockState st = level.getBlockState(bp);
        if (st.isAir()) return false;
        for (var b : st.getCollisionShape(level, bp).toAabbs()) if (b.move(bp).inflate(0.01).contains(p)) return true;
        return false;
    }

    private static final java.util.Map<net.minecraft.world.level.block.Block, Integer> HIT_NOTED = new java.util.HashMap<>();
    private static final java.util.Map<BlockState, Boolean> NO_TEXTURE = new java.util.IdentityHashMap<>();

    private static boolean noParticleTexture(BlockState state) {
        return NO_TEXTURE.computeIfAbsent(state, st -> {
            try {
                var sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(st).sprite();
                return sprite == null || sprite.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
            } catch (Exception e) {
                return true;
            }
        });
    }

    // the drawn model under a hit point: its texture there, to take the crumbs' colours from
    private record Crumbs(RenderedMesh.Tri tri, float u, float v, double du, double dv) {
        // the bullet's line through the hit (the hit is on the block's cells or hitbox, up to a cell off the model):
        // the first shown pixel it meets near there; else the triangle closest to the point
        static @Nullable Crumbs at(ClientLevel level, net.minecraft.core.BlockPos pos, Vec3 at, Vec3 dir) {
            Vec3 nd0 = dir.normalize();
            // the server's hit may be on the block's hitbox, the model deeper in: up to 3 blocks along the line
            var box = new net.minecraft.world.phys.AABB(at.subtract(nd0.scale(0.5)), at.add(nd0.scale(3))).inflate(0.5);
            List<RenderedMesh.Tri> near = new java.util.ArrayList<>(RenderedMesh.within(level, box));
            int drawnBy = near.size();
            near.addAll(RenderedMesh.models(level, box, pos, new net.minecraft.world.phys.AABB(pos), new boolean[1],
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
            why = "no triangles";
            Crumbs got = find(near, at, dir);
            note(level, pos, drawnBy, near.size() - drawnBy, got);
            return got;
        }

        private static final java.util.Map<net.minecraft.world.level.block.Block, Integer> NOTED = new java.util.HashMap<>();

        // the first few hits on each kind of block: what the crumbs were made from, to the log
        private static void note(ClientLevel level, net.minecraft.core.BlockPos pos, int drawnBy, int models, @Nullable Crumbs got) {
            var block = level.getBlockState(pos).getBlock();
            int n = NOTED.merge(block, 1, Integer::sum);
            if (n > 5) return;
            String what = "none";
            if (got != null) {
                int c = got.tri().tex().color(got.u(), got.v());
                what = String.format(java.util.Locale.ROOT, "%s colour #%08x", RenderedMesh.texName(got.tri().tex()), c);
            }
            GunFeel.LOGGER.info("Bullet crumbs on {}: {} renderer triangles, {} model triangles near, {}; crumbs from {}",
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block), drawnBy, models, why, what);
        }

        private static String why = "";

        private static @Nullable Crumbs find(List<RenderedMesh.Tri> near, Vec3 at, Vec3 dir) {
            if (near.isEmpty()) return null;
            Vec3 nd = dir.normalize();
            org.joml.Vector3d o = new org.joml.Vector3d(at.x - nd.x * 0.3, at.y - nd.y * 0.3, at.z - nd.z * 0.3), dv = new org.joml.Vector3d(nd.x, nd.y, nd.z);
            Crumbs first = null;
            double firstT = 3.3;
            int crossing = 0, clear = 0, noColour = 0;
            for (RenderedMesh.Tri t : near) {
                double k = t.cross(o, dv);
                if (Double.isNaN(k) || k < 0 || k >= 3.3) continue;
                crossing++;
                org.joml.Vector3d pt0 = new org.joml.Vector3d(dv).mul(k).add(o);
                double[] w0 = t.weights(pt0, 1e-3);
                if (w0 == null || !t.opaqueAt(w0)) {
                    clear++;
                    continue;
                }
                Crumbs c = of(t, pt0);
                if (c == null) {
                    noColour++;
                    if (noColour == 1) why = "first colourless: " + RenderedMesh.texName(t.tex());
                    continue;
                }
                if (k >= firstT) continue;
                firstT = k;
                first = c;
            }
            why = String.format(java.util.Locale.ROOT, "%d crossings (%d on clear pixels, %d without colour%s), first shown at %.2f",
                    crossing, clear, noColour, noColour > 0 ? "; " + why : "", first == null ? Double.NaN : firstT - 0.3);
            if (first != null) return first;
            org.joml.Vector3d pt = new org.joml.Vector3d(at.x, at.y, at.z);
            Crumbs best = null;
            double bestD = 0.1;
            for (RenderedMesh.Tri t : near) {
                org.joml.Vector3d tn = t.normal();
                if (tn == null) continue;
                double d = Math.abs(tn.dot(new org.joml.Vector3d(pt).sub(t.corner(0))));
                if (d >= bestD) continue;
                double[] w = t.weights(pt, 1e-3);
                if (w == null || !t.opaqueAt(w)) continue;
                float u = (float) (w[0] * t.uv()[0] + w[1] * t.uv()[2] + w[2] * t.uv()[4]);
                float v = (float) (w[0] * t.uv()[1] + w[1] * t.uv()[3] + w[2] * t.uv()[5]);
                if (t.tex().color(u, v) == 0) continue;
                double[] g = t.tex().grid();
                bestD = d;
                best = new Crumbs(t, u, v, g == null ? 0.01 : g[1], g == null ? 0.01 : g[3]);
            }
            return best;
        }

        private static @Nullable Crumbs of(RenderedMesh.Tri t, org.joml.Vector3d pt) {
            double[] w = t.weights(pt, 1e-3);
            if (w == null || !t.opaqueAt(w)) return null;
            float u = (float) (w[0] * t.uv()[0] + w[1] * t.uv()[2] + w[2] * t.uv()[4]);
            float v = (float) (w[0] * t.uv()[1] + w[1] * t.uv()[3] + w[2] * t.uv()[5]);
            if (t.tex().color(u, v) == 0) return null;
            double[] g = t.tex().grid();
            return new Crumbs(t, u, v, g == null ? 0.01 : g[1], g == null ? 0.01 : g[3]);
        }

        // one crumb: a pixel near the hit (up to 2 pixels off), shaded as block crumbs are
        void spawn(ClientLevel level, Vec3 p, double vx, double vy, double vz) {
            RandomSource r = level.getRandom();
            int c = 0;
            for (int i = 0; i < 4 && (c >>> 24) < 26; i++)
                c = tri.tex().color(u + (float) ((r.nextInt(5) - 2) * du), v + (float) ((r.nextInt(5) - 2) * dv));
            if ((c >>> 24) < 26) c = tri.tex().color(u, v);
            var particle = new net.minecraft.client.particle.TerrainParticle(level, p.x, p.y, p.z, vx, vy, vz,
                    net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState());
            particle.setColor(0.6F * ((c >> 16) & 255) / 255F, 0.6F * ((c >> 8) & 255) / 255F, 0.6F * (c & 255) / 255F);
            Minecraft.getInstance().particleEngine.add(particle);
        }
    }

    // a stretch a bullet flew this frame, a..b: it whizzes when it passed within the rule's radius of the player's eyes
    // (not the player's own bullets); true when it did, so it whizzes once
    public static boolean whizz(ClientLevel level, int owner, Vec3 a, Vec3 b) {
        if (!Config.bulletFx) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || owner == mc.player.getId()) return false;
        BulletRules.Whizz w = BulletRules.whizz();
        if (w.sound() == null || w.radius() <= 0F) return false;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 ab = b.subtract(a);
        double l2 = ab.lengthSqr();
        double k = l2 < 1e-9 ? 0.0 : Math.clamp(eye.subtract(a).dot(ab) / l2, 0.0, 1.0);
        // the nearest point must be along the stretch, not its end: else it is still coming or already gone
        if (l2 >= 1e-9 && (k <= 0.0 || k >= 1.0)) return false;
        Vec3 near = a.add(ab.scale(k));
        double d = near.distanceTo(eye);
        if (d > w.radius()) return false;
        SoundEvent e = event(w.sound().id());
        if (e == null) return false;
        RandomSource r = level.getRandom();
        float vol = w.sound().volume() * (float) (1.0 - 0.6 * d / w.radius());
        level.playLocalSound(near.x, near.y, near.z, e, SoundSource.PLAYERS, vol, w.sound().pitch() * (0.85F + r.nextFloat() * 0.3F), false);
        return true;
    }

    // the in-flight sound of one bullet, following it until it stops
    public static void fly(Supplier<Vec3> where, BooleanSupplier flying) {
        if (!Config.bulletFx) return;
        BulletRules.Sound s = BulletRules.fly();
        if (s == null) return;
        SoundEvent e = event(s.id());
        if (e == null) return;
        Minecraft.getInstance().getSoundManager().play(new Flying(e, s, where, flying));
    }

    private static final class Flying extends AbstractTickableSoundInstance {
        private final Supplier<Vec3> where;
        private final BooleanSupplier flying;

        Flying(SoundEvent e, BulletRules.Sound s, Supplier<Vec3> where, BooleanSupplier flying) {
            super(e, SoundSource.PLAYERS, RandomSource.create());
            this.where = where;
            this.flying = flying;
            this.looping = true;
            this.delay = 0;
            this.volume = s.volume();
            this.pitch = s.pitch();
            move();
        }

        private void move() {
            Vec3 p = where.get();
            x = p.x;
            y = p.y;
            z = p.z;
        }

        @Override
        public void tick() {
            if (!flying.getAsBoolean()) {
                stop();
                return;
            }
            move();
        }
    }
}

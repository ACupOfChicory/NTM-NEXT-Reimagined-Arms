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

    // sound families: an event `x` may come with `x.f1`, `x.f2`… (groups of similar-sounding tracks, sounds.json).
    // Hits of the same event within FAMILY_MS share one family, so one shot's pellets sound alike; the next shot rolls again
    private static final long FAMILY_MS = 80L;
    private static final java.util.Map<String, long[]> FAMILY = new java.util.HashMap<>();

    private static String familyOf(String id, RandomSource r) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return id;
        var sm = Minecraft.getInstance().getSoundManager();
        int n = 0;
        while (n < 16 && sm.getSoundEvent(key.withSuffix(".f" + (n + 1))) != null) n++;
        if (n == 0) return id;
        long now = System.currentTimeMillis();
        long[] last = FAMILY.get(id);
        if (last == null || now - last[0] > FAMILY_MS || last[1] >= n) {
            last = new long[] {now, r.nextInt(n)};
            FAMILY.put(id, last);
        }
        return id + ".f" + (last[1] + 1);
    }

    // /gunfeel sounddebug: every hit's sound, what was picked and why it played or not, to the log ("[sounddebug]")
    public static volatile boolean debugSound;

    static void sd(String fmt, Object... args) {
        if (debugSound) GunFeel.LOGGER.info("[sounddebug] " + fmt, args);
    }

    public static void blockHit(ClientLevel level, BlockHitResult hit, Vec3 dir) {
        blockHit(level, hit, dir, false);
    }

    // explosive: a round that blows up there; its own blast is loud enough, the hit sound stays at the rule's volume
    public static void blockHit(ClientLevel level, BlockHitResult hit, Vec3 dir, boolean explosive) {
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (debugSound) sd("hit {} {} at {}", BuiltInRegistries.BLOCK.getKey(state.getBlock()), hit.getBlockPos().toShortString(), hit.getLocation());
        if (!Config.bulletFx) {
            sd("  skipped: bulletFx off in the config");
            return;
        }
        if (state.isAir()) {
            sd("  skipped: air there on the client");
            return;
        }
        // a cell of an NTM multiblock (the shared multiblock_cell_* blocks): the rules of its machine
        BlockState ruleState = state;
        if (state.getBlock() instanceof com.hbm.blocks.multiblock.BlockMultiblockCell) {
            try {
                net.minecraft.core.BlockPos core = com.hbm.blocks.multiblock.MultiblockSurface.clientCoreOf(level, hit.getBlockPos());
                if (core != null && !level.getBlockState(core).isAir()) ruleState = level.getBlockState(core);
            } catch (RuntimeException e) {
                // no core known here: the cell's own rules
            }
            if (debugSound) sd("  cell of {}", BuiltInRegistries.BLOCK.getKey(ruleState.getBlock()));
        }
        BulletRules.Effect fx = BulletRules.hit(ruleState);
        if (fx.empty()) {
            sd("  skipped: no hit rule gives it particles or a sound");
            return;
        }
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
        if (s == null) {
            sd("  no sound: its hit rule has none");
            return;
        }
        String id = s.id().equalsIgnoreCase("block") ? "block" : familyOf(s.id(), r);
        SoundEvent e = id.equals("block") ? state.getSoundType().getHitSound() : event(id);
        if (e == null) {
            sd("  no sound: bad sound id {}", s.id());
            return;
        }
        if (debugSound) sd("  rule sound {} -> {} (families {})", s.id(), e.location(), id.equals(s.id()) ? "none" : "yes");
        float volume = s.volume() * (explosive ? Math.min(1F, Config.hitVolume) : Config.hitVolume);
        if (volume <= 0F) {
            sd("  not played: hitVolume 0");
            return;
        }
        play(level, at, e, volume, s.pitch() * (0.9F + r.nextFloat() * 0.2F), fx.range());
    }

    // a sound heard over `range` blocks, fading out to it. Minecraft fades a block's sound out within 16 blocks
    // (another player's bullet hitting the ground 12 blocks away was all but silent): set nearer the listener on the
    // same line, so it comes from the same side but fades as if over `range`
    private static void play(ClientLevel level, Vec3 at, SoundEvent e, float volume, float pitch, float range) {
        Vec3 p = at;
        if (range > 16F) {
            Vec3 ear = Minecraft.getInstance().gameRenderer.mainCamera().position();
            double d = at.distanceTo(ear);
            if (d >= range) {
                if (debugSound) sd("  not played: {} blocks away, beyond its range {}", String.format("%.1f", d), range);
                return;
            }
            p = ear.add(at.subtract(ear).scale(16.0 / range));
        }
        // Minecraft plays no louder than volume 1: louder, the same track at the same pitch several times at once
        int copies = Math.min(4, Math.max(1, (int) Math.ceil(volume - 1e-3F)));
        long seed = level.getRandom().nextLong();
        var sm = Minecraft.getInstance().getSoundManager();
        net.minecraft.client.resources.sounds.SimpleSoundInstance inst = null;
        net.minecraft.client.sounds.SoundEngine.PlayResult result = null;
        for (int i = 0; i < copies; i++) {
            inst = new net.minecraft.client.resources.sounds.SimpleSoundInstance(e, SoundSource.PLAYERS, volume / copies, pitch,
                    RandomSource.create(seed), p.x, p.y, p.z);
            result = sm.play(inst);
        }
        if (debugSound) {
            Vec3 ear = Minecraft.getInstance().gameRenderer.mainCamera().position();
            var w = sm.getSoundEvent(e.location());
            sd("  played {} x{}: track {}, known {}, volume {}, pitch {}, {} blocks away (set at {}), result {}, active {}",
                    e.location(), copies, inst.getSound() == null ? "?" : inst.getSound().getLocation(), w != null,
                    String.format("%.2f", volume), String.format("%.2f", pitch), String.format("%.1f", at.distanceTo(ear)),
                    String.format("%.1f", p.distanceTo(ear)), result, sm.isActive(inst));
        }
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
            // block models only of the machine's own mod (its other blocks): the line runs on through the hitbox,
            // past the model, into the grass and stone behind it
            String ns = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace();
            for (RenderedMesh.Tri t : RenderedMesh.models(level, box, pos, new net.minecraft.world.phys.AABB(pos), new boolean[1],
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) {
                if (t.block() == null || t.block().equals(pos)
                        || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(t.block()).getBlock()).getNamespace().equals(ns))
                    near.add(t);
            }
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
            // no shown pixel on the line (the hitbox is bigger than the model): the machine's surface nearest the hit
            Crumbs best = null;
            double bestD = 1.0;
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
            Minecraft.getInstance().particleEngine.add(make(level, p, vx, vy, vz));
        }

        net.minecraft.client.particle.TerrainParticle make(ClientLevel level, Vec3 p, double vx, double vy, double vz) {
            RandomSource r = level.getRandom();
            int c = 0;
            for (int i = 0; i < 4 && (c >>> 24) < 26; i++)
                c = tri.tex().color(u + (float) ((r.nextInt(5) - 2) * du), v + (float) ((r.nextInt(5) - 2) * dv));
            if ((c >>> 24) < 26) c = tri.tex().color(u, v);
            var particle = new net.minecraft.client.particle.TerrainParticle(level, p.x, p.y, p.z, vx, vy, vz,
                    net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState());
            particle.setColor(0.6F * ((c >> 16) & 255) / 255F, 0.6F * ((c >> 8) & 255) / 255F, 0.6F * (c & 255) / 255F);
            return particle;
        }
    }

    // where a block's crumbs were looked up this tick: an explosion makes a dozen at once
    private static net.minecraft.core.BlockPos dustPos;
    private static long dustTick = Long.MIN_VALUE;
    private static @Nullable Crumbs dustCrumbs;

    // block crumbs anyone makes (NTM's explosion debris, vanilla's) of a block with no particle texture (NTM's
    // machine cells: purple and black squares): crumbs of the machine's drawn colours there instead, else steel-grey
    // ones; null for every other block, made as usual
    public static net.minecraft.client.particle.@Nullable Particle missingTextureCrumb(ClientLevel level, BlockState state,
            double x, double y, double z, double vx, double vy, double vz) {
        if (state.isAir() || !noParticleTexture(state)) return null;
        Vec3 at = new Vec3(x, y, z);
        net.minecraft.core.BlockPos c = net.minecraft.core.BlockPos.containing(x, y, z), pos = null;
        double best = Double.MAX_VALUE;
        for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1))) {
            if (level.getBlockState(q) != state) continue;
            double d = Vec3.atCenterOf(q).distanceToSqr(at);
            if (d < best) {
                best = d;
                pos = q.immutable();
            }
        }
        Crumbs crumbs = null;
        if (pos != null) {
            long tick = level.getGameTime();
            if (tick == dustTick && pos.equals(dustPos)) {
                crumbs = dustCrumbs;
            } else {
                Vec3 dir = Vec3.atCenterOf(pos).subtract(at);
                if (dir.lengthSqr() < 0.01) dir = new Vec3(0, -1, 0);
                crumbs = Crumbs.at(level, pos, at, dir);
                dustTick = tick;
                dustPos = pos;
                dustCrumbs = crumbs;
            }
        }
        if (crumbs != null) return crumbs.make(level, at, vx, vy, vz);
        return new net.minecraft.client.particle.TerrainParticle(level, x, y, z, vx, vy, vz,
                net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState());
    }

    // a stretch a bullet flew this frame, a..b: it whizzes when it passed within the rule's radius of the player's eyes
    // (not the player's own bullets); true when it did, so it whizzes once
    public static boolean whizz(ClientLevel level, int owner, Vec3 a, Vec3 b, double speed) {
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
        // closer: louder and a little higher; faster bullets (NTM's usual is 10 blocks a tick) higher too
        float close = (float) (1.0 - d / w.radius());
        float vol = w.sound().volume() * (0.4F + 0.6F * close);
        float fast = (float) Math.clamp(Math.pow(speed / 10.0, 0.25), 0.9, 1.15);
        float pitch = w.sound().pitch() * fast * (0.9F + 0.15F * close) * (0.88F + r.nextFloat() * 0.24F);
        level.playLocalSound(near.x, near.y, near.z, e, SoundSource.PLAYERS, vol, pitch, false);
        // passing right by the head: the snap of the bullet's shock wave on top
        if (w.crack() != null && d <= w.crackRadius()) {
            SoundEvent c = event(w.crack().id());
            if (c != null) {
                float cv = w.crack().volume() * (float) (0.6 + 0.4 * (1.0 - d / Math.max(0.01, w.crackRadius())));
                level.playLocalSound(near.x, near.y, near.z, c, SoundSource.PLAYERS, cv, w.crack().pitch() * (0.9F + r.nextFloat() * 0.2F), false);
            }
        }
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

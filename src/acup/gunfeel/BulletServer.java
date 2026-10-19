package acup.gunfeel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

// Server side of BulletRules: blocks bullets break, and what a creature hit or killed by one gives off.
public final class BulletServer {
    private BulletServer() {
    }

    // what bullets did to a block not broken yet: hits or damage so far, and when the last came
    // the block it was done to: once that block is broken or replaced (by hand, a piston, a command) the wear and its
    // cracks go
    private record Wear(float done, long at, BlockState state) {
    }

    private record Spot(ServerLevel level, BlockPos pos) {
    }

    private static final Map<Spot, Wear> WEAR = new HashMap<>();
    private static final long FORGET_MS = 20_000L;

    // the bullet hit this block: 0 = nothing happened, 1 = it broke and the bullet stops, 2 = it broke and the bullet
    // flies on
    public static int blockHit(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return 0;
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return 0;
        BulletRules.Break rule = BulletRules.breakRule(state);
        if (rule == null || rule.mode() == 0) return 0;
        if (!wear(level, bullet, pos, state, rule)) return 0;
        return rule.pass() ? 2 : 1;
    }

    private static final net.minecraft.sounds.SoundEvent RICOCHET_STEEL = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("ntmgunfeel", "bullet.ricochet.steel"));

    // NTM's ricochet sound (MixinRicochetSound): off a block whose hit sound is steel (bullet rules; a multiblock's
    // cell by its machine) our steel ricochet where the bullet hit instead, and true; else false (NTM's own plays)
    public static volatile boolean debugRicochet; // /gunfeel sounddebug in a single player world: its ricochets to the log

    public static boolean ricochetSound(Level level, net.minecraft.world.phys.HitResult mop) {
        if (!(mop instanceof BlockHitResult hit) || level.isClientSide()) return false;
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (state.isAir()) return false;
        BlockState rule = state;
        if (state.getBlock() instanceof com.hbm.blocks.multiblock.BlockMultiblockCell) {
            try {
                BlockPos core = com.hbm.blocks.multiblock.MultiblockSurface.coreOfAny(level, hit.getBlockPos(), state);
                if (core != null && !level.getBlockState(core).isAir()) rule = level.getBlockState(core);
            } catch (RuntimeException e) {
                // no core known: the cell's own rules
            }
        }
        BulletRules.Sound s = BulletRules.hit(rule).sound();
        boolean steel = s != null && (s.id().startsWith("ntmgunfeel:bullet.hit.steel") || s.id().startsWith("ntmgunfeel:bullet.hit.metal"));
        if (debugRicochet) acup.gunfeel.GunFeel.LOGGER.info("[sounddebug] server: ricochet off {} at {} (hit sound {}): {}",
                BuiltInRegistries.BLOCK.getKey(rule.getBlock()), hit.getBlockPos().toShortString(), s == null ? "none" : s.id(),
                steel ? "our steel ricochet" : "NTM's ricochet sound");
        if (!steel) return false;
        Vec3 at = hit.getLocation();
        level.playSound(null, at.x, at.y, at.z, RICOCHET_STEEL, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        return true;
    }

    // the bullet flew through a block it always flies through ("through"): counts as a hit on it
    public static void passed(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return;
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = level.getBlockState(pos);
        BulletRules.Break rule = BulletRules.breakRule(state);
        if (rule == null || rule.mode() == 0) return;
        wear(level, bullet, pos, state, rule);
    }

    // the bullet's path this tick through blocks, piercing those whose rule says "pierce" while it has the damage:
    // each one pierced takes its share of the damage (and counts towards breaking it). `paid` = blocks it already
    // pierced (one it ended a tick inside of is not paid for again). The first block that stops it, else a miss at `to`.
    public static BlockHitResult clip(EntityBulletBaseMK4 bullet, Level level, Vec3 from, Vec3 to, java.util.Set<BlockPos> paid) {
        java.util.function.Consumer<BlockHitResult> through = hit -> passed(bullet, hit);
        // an explosive bullet goes off on the first block it meets, never pierces
        if (bullet.config != null && bullet.config.onImpact != null) return PassableClip.clip(level, from, to, through);
        Vec3 at = from;
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = PassableClip.clip(level, at, to, through);
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return hit;
            Vec3 d = to.subtract(at);
            double len = d.length();
            if (len < 1e-6) return hit;
            Vec3 dir = d.scale(1.0 / len);
            BlockPos pos = hit.getBlockPos().immutable();
            boolean known = paid.contains(pos);
            float left = known ? bullet.damage : PassableClip.pierced(level, hit, bullet.damage);
            if (left < 0F) return hit;
            BlockHitResult out = PassableClip.exit(level, hit, dir);
            if (out == null) return hit;
            if (!known) {
                paid.add(pos);
                if (level instanceof ServerLevel server) {
                    BlockState state = level.getBlockState(pos);
                    BulletRules.Break rule = BulletRules.breakRule(state);
                    if (rule != null && rule.mode() != 0) wear(server, bullet, pos, state, rule);
                }
                bullet.damage = left;
            }
            // it comes out beyond this tick's end: it stays inside the block till the next tick
            if (out.getLocation().distanceToSqr(at) >= len * len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
            at = out.getLocation().add(dir.scale(1e-3));
        }
        return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to));
    }

    // one hit on the block by its rule: true when it broke
    private static boolean wear(ServerLevel level, EntityBulletBaseMK4 bullet, BlockPos pos, BlockState state, BulletRules.Break rule) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0F) return false; // bedrock and the like
        Entity owner = bullet.getOwner();
        if (owner instanceof ServerPlayer player) {
            if (!player.mayBuild()) return false;
            BreakBlockEvent event = new BreakBlockEvent(level, pos, state, player);
            NeoForge.EVENT_BUS.post(event);
            if (event.isCanceled()) return false;
        } else if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return false;
        }
        long now = System.currentTimeMillis();
        forget(level, now);
        if (rule.mode() == 2 || rule.mode() == 3 || rule.mode() == 4) {
            Spot spot = new Spot(level, pos);
            Wear w = WEAR.get(spot);
            if (w != null && w.state().getBlock() != state.getBlock()) w = null;
            float add = rule.mode() == 2 ? 1F : Math.max(0F, bullet.damage);
            float need = rule.mode() == 3 ? rule.amount() * hardness : rule.amount();
            float done = (w == null ? 0F : w.done()) + add;
            if (need > 0F && done < need) {
                WEAR.put(spot, new Wear(done, now, state));
                level.destroyBlockProgress(crackId(pos), pos, Math.min(9, (int) (done / need * 10F)));
                return false;
            }
            WEAR.remove(spot);
            level.destroyBlockProgress(crackId(pos), pos, -1);
        }
        level.destroyBlock(pos, rule.drop(), owner);
        if (rule.replace() != null) {
            BlockState with = parse(level, rule.replace());
            if (with != null) level.setBlock(pos, with, 3);
        }
        return true;
    }

    private static void forget(ServerLevel level, long now) {
        Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot, Wear> e = it.next();
            if (now - e.getValue().at() < FORGET_MS) continue;
            e.getKey().level().destroyBlockProgress(crackId(e.getKey().pos()), e.getKey().pos(), -1);
            it.remove();
        }
    }

    private static int ticks;

    // every few server ticks: wear on a block that is no longer there (broken, replaced, its chunk gone) goes with its cracks
    public static void tick() {
        if (WEAR.isEmpty() || ++ticks % 5 != 0) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Spot, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot, Wear> e = it.next();
            ServerLevel level = e.getKey().level();
            BlockPos pos = e.getKey().pos();
            boolean gone = !level.isLoaded(pos) || level.getBlockState(pos).getBlock() != e.getValue().state().getBlock();
            if (!gone && now - e.getValue().at() < FORGET_MS) continue;
            level.destroyBlockProgress(crackId(pos), pos, -1);
            it.remove();
        }
    }

    // a breaker id per block for its cracks, apart from the entity ids players use
    private static int crackId(BlockPos pos) {
        return -1_000_000 - (pos.hashCode() & 0x3fffffff);
    }

    private static final Map<String, BlockState> PARSED = new HashMap<>();

    private static @Nullable BlockState parse(ServerLevel level, String text) {
        if (PARSED.containsKey(text)) return PARSED.get(text);
        BlockState out = null;
        try {
            out = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text, false).blockState();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").warn("Bullet rules: bad replace block state {}", text);
        }
        PARSED.put(text, out);
        return out;
    }

    // a bullet hit a creature (alive before the hit): its "entity" or, when it died, "kill" effect
    public static void entityHit(EntityBulletBaseMK4 bullet, Entity target, Vec3 at, boolean wasAlive) {
        if (!(bullet.level() instanceof ServerLevel level) || !wasAlive) return;
        boolean dead = target instanceof LivingEntity living && !living.isAlive();
        play(level, bullet, target, at, BulletRules.entity("entity", target));
        if (dead) play(level, bullet, target, at, BulletRules.entity("kill", target));
    }

    private static void play(ServerLevel level, EntityBulletBaseMK4 bullet, Entity target, Vec3 at, BulletRules.Effect fx) {
        if (fx.empty()) return;
        Vec3 p = at == null ? target.position().add(0, target.getBbHeight() / 2, 0) : at;
        for (BulletRules.Particle part : fx.particles()) {
            ParticleOptions o = particle(part.id(), null);
            if (o != null) level.sendParticles(o, p.x, p.y, p.z, part.count(), 0.1, 0.1, 0.1, 0.05);
        }
        BulletRules.Sound s = fx.sound();
        if (s == null) return;
        Identifier id = Identifier.tryParse(s.id());
        if (id == null) return;
        Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(h -> h)
                .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
        float pitch = s.pitch() * (0.95F + level.getRandom().nextFloat() * 0.1F);
        if (fx.toAll()) {
            level.playSound(null, p.x, p.y, p.z, sound, SoundSource.PLAYERS, s.volume(), pitch);
        } else if (bullet.getOwner() instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS, p.x, p.y, p.z, s.volume(), pitch, level.getRandom().nextLong()));
        }
    }

    // a particle from its rule name: "block" (of `hit`), "block:<id>", or a plain particle id
    static @Nullable ParticleOptions particle(String name, @Nullable BlockState hit) {
        if (name.equalsIgnoreCase("block")) return hit == null ? null : new BlockParticleOption(ParticleTypes.BLOCK, hit);
        if (name.startsWith("block:")) {
            Identifier id = Identifier.tryParse(name.substring(6));
            if (id == null) return null;
            return BuiltInRegistries.BLOCK.getOptional(id).map(b -> (ParticleOptions) new BlockParticleOption(ParticleTypes.BLOCK, b.defaultBlockState())).orElse(null);
        }
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(id).filter(t -> t instanceof SimpleParticleType).map(t -> (ParticleOptions) (SimpleParticleType) t).orElse(null);
    }
}

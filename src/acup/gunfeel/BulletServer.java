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
    private record Wear(float done, long at) {
    }

    private static final Map<BlockPos, Wear> WEAR = new HashMap<>();
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

    // the bullet flew through a block it always flies through ("through"): counts as a hit on it
    public static void passed(EntityBulletBaseMK4 bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return;
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = level.getBlockState(pos);
        BulletRules.Break rule = BulletRules.breakRule(state);
        if (rule == null || rule.mode() == 0) return;
        wear(level, bullet, pos, state, rule);
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
        if (rule.mode() == 2 || rule.mode() == 3) {
            Wear w = WEAR.get(pos);
            float add = rule.mode() == 2 ? 1F : Math.max(0F, bullet.damage);
            float need = rule.mode() == 2 ? rule.amount() : rule.amount() * hardness;
            float done = (w == null ? 0F : w.done()) + add;
            if (need > 0F && done < need) {
                WEAR.put(pos, new Wear(done, now));
                level.destroyBlockProgress(crackId(pos), pos, Math.min(9, (int) (done / need * 10F)));
                return false;
            }
            WEAR.remove(pos);
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
        Iterator<Map.Entry<BlockPos, Wear>> it = WEAR.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Wear> e = it.next();
            if (now - e.getValue().at() < FORGET_MS) continue;
            level.destroyBlockProgress(crackId(e.getKey()), e.getKey(), -1);
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

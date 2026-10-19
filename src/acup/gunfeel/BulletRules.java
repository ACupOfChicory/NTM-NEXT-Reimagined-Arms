package acup.gunfeel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;

// What bullets do to the blocks and creatures they hit, besides NTM's damage: break blocks, particles, sounds.
// Rules come from ntmgunfeel-bullets.txt in the mod jar, then config/ntm-gunfeel-bullets.txt (its lines win: of the
// rules that match, the last one counts). The server uses break, entity and kill lines; the client hit, whizz and fly.
public final class BulletRules {
    private BulletRules() {
    }

    public static final String FILE = "ntm-gunfeel-bullets.txt";

    // break: how a block gives way. mode: 0 never, 1 instant, 2 after `amount` hits, 3 after `amount` x hardness damage
    // pierce: a bullet goes through it, keeping `keep` of its damage less `pierce` x hardness (0 = never pierced)
    public record Break(int mode, float amount, boolean drop, boolean pass, boolean through, @Nullable String replace, float pierce, float keep) {
        // the bullet's damage after piercing a block of this hardness, or -1 when it stops in it
        public float pierced(float damage, float hardness) {
            if (pierce <= 0F || through || hardness < 0F) return -1F;
            float left = damage * keep - pierce * hardness;
            return left >= 1F ? left : -1F;
        }
    }

    // a particle: "block" (the hit block's crack), "block:<id>" (that block's crack) or a plain particle id
    public record Particle(String id, int count) {
    }

    // a sound: "block" (the hit block's own hit sound) or a sound id
    public record Sound(String id, float volume, float pitch) {
    }

    // range: blocks the sound is heard over (a block's hit sound alone fades out within 16)
    public record Effect(List<Particle> particles, @Nullable Sound sound, boolean toAll, float range) {
        public boolean empty() {
            return particles.isEmpty() && sound == null;
        }
    }

    // crack: the supersonic snap on top of the whizz when it passes within crackRadius
    public record Whizz(@Nullable Sound sound, float radius, @Nullable Sound crack, float crackRadius) {
    }

    private record Rule(String kind, List<String> selectors, Map<String, String> opts, String[] words) {
    }

    private static final Effect NO_EFFECT = new Effect(List.of(), null, false, 16F);
    private static List<Rule> rules = List.of();
    private static long stamp = Long.MIN_VALUE, lastPoll;
    private static boolean builtinRead;
    private static List<Rule> builtin = List.of();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    private static final Map<BlockState, java.util.Optional<Break>> BREAKS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, Effect> HITS = new java.util.IdentityHashMap<>();
    private static final Map<BlockState, Boolean> RICOCHETS = new java.util.IdentityHashMap<>();

    private static synchronized List<Rule> rules() {
        long now = System.currentTimeMillis();
        if (!builtinRead) {
            builtinRead = true;
            try (var in = BulletRules.class.getResourceAsStream("/ntmgunfeel-bullets.txt")) {
                if (in != null) builtin = parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
            } catch (Exception e) {
                LOGGER.warn("Could not read the built-in bullet rules", e);
            }
            stamp = Long.MIN_VALUE + 1;
            lastPoll = 0;
        }
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            Path f = FMLPaths.CONFIGDIR.get().resolve(FILE);
            try {
                if (!Files.exists(f)) Files.writeString(f, HELP);
                long m = Files.getLastModifiedTime(f).toMillis();
                if (m != stamp) {
                    stamp = m;
                    List<Rule> all = new ArrayList<>(builtin);
                    all.addAll(parse(Files.readAllLines(f, StandardCharsets.UTF_8)));
                    rules = all;
                    BREAKS.clear();
                    HITS.clear();
                    RICOCHETS.clear();
                }
            } catch (Exception e) {
                if (rules.isEmpty()) rules = builtin;
            }
        }
        return rules;
    }

    private static List<Rule> parse(List<String> lines) {
        List<Rule> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            // a comment after a rule: " # text" (a lone #; "#minecraft:planks" is a tag)
            java.util.regex.Matcher hash = java.util.regex.Pattern.compile("\\s#(\\s|$)").matcher(line);
            if (hash.find()) line = line.substring(0, hash.start()).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] w = line.split("\\s+");
            String kind = w[0].toLowerCase(Locale.ROOT);
            List<String> sel = new ArrayList<>();
            Map<String, String> opts = new HashMap<>();
            List<String> words = new ArrayList<>();
            int i = 1;
            // whizz and fly take no selectors
            if (!kind.equals("whizz") && !kind.equals("fly") && w.length > 1) {
                for (String s : w[1].split(",")) if (!s.isEmpty()) sel.add(s);
                i = 2;
            }
            for (; i < w.length; i++) {
                int eq = w[i].indexOf('=');
                if (eq > 0) opts.put(w[i].substring(0, eq).toLowerCase(Locale.ROOT), w[i].substring(eq + 1));
                else words.add(w[i].toLowerCase(Locale.ROOT));
            }
            out.add(new Rule(kind, sel, opts, words.toArray(String[]::new)));
        }
        return out;
    }

    // ---- matching

    private static final Map<String, SoundType> SOUND_TYPES = soundTypes();

    private static Map<String, SoundType> soundTypes() {
        Map<String, SoundType> out = new HashMap<>();
        for (Field f : SoundType.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != SoundType.class) continue;
            try {
                out.put(f.getName().toLowerCase(Locale.ROOT), (SoundType) f.get(null));
            } catch (IllegalAccessException e) {
                // skip
            }
        }
        return out;
    }

    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    private static boolean idMatches(String sel, Identifier id) {
        if (sel.startsWith("~")) {
            Pattern p = PATTERNS.computeIfAbsent(sel, s -> {
                try {
                    return Pattern.compile(s.substring(1));
                } catch (RuntimeException e) {
                    return Pattern.compile("(?!)");
                }
            });
            return p.matcher(id.toString()).matches();
        }
        Identifier want = Identifier.tryParse(sel);
        return want != null && want.equals(id);
    }

    static boolean blockMatches(List<String> sel, BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                Identifier t = Identifier.tryParse(s.substring(1));
                if (t != null && state.is(TagKey.create(Registries.BLOCK, t))) return true;
            } else if (s.startsWith("@")) {
                SoundType st = SOUND_TYPES.get(s.substring(1).toLowerCase(Locale.ROOT));
                if (st != null && state.getSoundType() == st) return true;
            } else if (s.startsWith("%")) {
                if (id.getNamespace().equals(s.substring(1)) && machineOrPartial(state)) return true;
            } else if (idMatches(s, id)) return true;
        }
        return false;
    }

    private static final Map<BlockState, Boolean> MACHINE = new java.util.concurrent.ConcurrentHashMap<>();

    // %mod: a machine (a block entity, drawn by a renderer) or a block that is not a full cube
    private static boolean machineOrPartial(BlockState state) {
        return MACHINE.computeIfAbsent(state, st -> {
            if (st.hasBlockEntity() || st.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) return true;
            try {
                return !net.minecraft.world.level.block.Block.isShapeFullBlock(
                        st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO));
            } catch (RuntimeException e) {
                return true;
            }
        });
    }

    static boolean entityMatches(List<String> sel, Entity e) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        for (String s : sel) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                Identifier t = Identifier.tryParse(s.substring(1));
                if (t != null && e.is(TagKey.create(Registries.ENTITY_TYPE, t))) return true;
            } else if (idMatches(s, id)) return true;
        }
        return false;
    }

    private static @Nullable Rule last(String kind, java.util.function.Predicate<Rule> match) {
        List<Rule> all = rules();
        for (int i = all.size() - 1; i >= 0; i--) {
            Rule r = all.get(i);
            if (r.kind().equals(kind) && match.test(r)) return r;
        }
        return null;
    }

    // ---- answers

    public static synchronized @Nullable Break breakRule(BlockState state) {
        rules();
        java.util.Optional<Break> known = BREAKS.get(state);
        if (known != null) return known.orElse(null);
        Rule r = last("break", x -> blockMatches(x.selectors(), state));
        Break out = null;
        if (r != null && r.words().length > 0) {
            String[] w = r.words();
            int mode = switch (w[0]) {
                case "instant" -> 1;
                case "hits" -> 2;
                case "damage" -> 3;
                default -> 0;
            };
            float amount = 1F;
            if ((mode == 2 || mode == 3) && w.length > 1) {
                try {
                    amount = Float.parseFloat(w[1]);
                } catch (NumberFormatException e) {
                    // keep 1
                }
            }
            boolean drop = false, pass = false, through = false;
            for (String s : w) {
                if (s.equals("drop")) drop = true;
                if (s.equals("pass")) pass = true;
                if (s.equals("through")) through = true;
            }
            boolean pierce = number(r.opts().get("pierce"), 0F) > 0F;
            out = mode == 0 && !through && !pierce ? null : new Break(mode, amount, drop, pass || through, through, r.opts().get("replace"),
                    number(r.opts().get("pierce"), 0F), number(r.opts().get("keep"), 1F));
        }
        BREAKS.put(state, java.util.Optional.ofNullable(out));
        return out;
    }

    private static float number(@Nullable String text, float or) {
        if (text == null) return or;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return or;
        }
    }

    // bullets always fly through it (a "break ... through" rule); it still counts the hits and breaks by its rule
    public static boolean through(BlockState state) {
        if (!anyThrough()) return false;
        Break b = breakRule(state);
        return b != null && b.through();
    }

    private static List<Rule> throughOf;
    private static boolean anyThrough;

    // some rule says "through" (else a bullet's path needs no walk through the blocks it passes)
    public static synchronized boolean anyThrough() {
        List<Rule> all = rules();
        if (all != throughOf) {
            throughOf = all;
            anyThrough = false;
            for (Rule r : all) if (r.kind().equals("break") && java.util.Arrays.asList(r.words()).contains("through")) anyThrough = true;
        }
        return anyThrough;
    }

    // a bullet may bounce off it (NTM's ricochet at a grazing angle); "ricochet <blocks> no": it stops in it instead
    public static synchronized boolean ricochet(BlockState state) {
        rules();
        return RICOCHETS.computeIfAbsent(state, st -> {
            Rule r = last("ricochet", x -> blockMatches(x.selectors(), st));
            return r == null || !java.util.Arrays.asList(r.words()).contains("no");
        });
    }

    public static synchronized Effect hit(BlockState state) {
        rules();
        Effect got = HITS.get(state);
        if (got != null) return got;
        Rule r = last("hit", x -> blockMatches(x.selectors(), state));
        Effect out = r == null ? NO_EFFECT : effect(r);
        HITS.put(state, out);
        return out;
    }

    public static synchronized Effect entity(String kind, Entity e) {
        Rule r = last(kind, x -> entityMatches(x.selectors(), e));
        return r == null ? NO_EFFECT : effect(r);
    }

    public static synchronized Whizz whizz() {
        Rule r = last("whizz", x -> true);
        if (r == null) return new Whizz(null, 0F, null, 0F);
        return new Whizz(sound(r.opts().get("sound")), num(r.opts().get("radius"), 3F), sound(r.opts().get("crack")),
                num(r.opts().get("crackradius"), 1.2F));
    }

    public static synchronized @Nullable Sound fly() {
        Rule r = last("fly", x -> true);
        return r == null ? null : sound(r.opts().get("sound"));
    }

    private static Effect effect(Rule r) {
        List<Particle> ps = new ArrayList<>();
        String p = r.opts().get("particles");
        if (p != null && !p.equalsIgnoreCase("none")) {
            for (String s : p.split(",")) {
                if (s.isEmpty()) continue;
                int star = s.lastIndexOf('*');
                int count = 1;
                String id = s;
                if (star > 0) {
                    try {
                        count = Math.max(1, Integer.parseInt(s.substring(star + 1)));
                    } catch (NumberFormatException e) {
                        // 1
                    }
                    id = s.substring(0, star);
                }
                ps.add(new Particle(id, count));
            }
        }
        return new Effect(List.copyOf(ps), sound(r.opts().get("sound")), "all".equalsIgnoreCase(r.opts().get("to")),
                num(r.opts().get("range"), 16F));
    }

    // "id[/volume[/pitch]]", "none" = no sound
    private static @Nullable Sound sound(@Nullable String s) {
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("none")) return null;
        String[] p = s.split("/");
        return new Sound(p[0], p.length > 1 ? num(p[1], 1F) : 1F, p.length > 2 ? num(p[2], 1F) : 1F);
    }

    private static float num(@Nullable String s, float def) {
        if (s == null) return def;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static final String HELP = """
            # NTM-NEXT Reimagined Arms: what bullets do to blocks and creatures. One rule per line; these lines come
            # after the mod's own (ntmgunfeel-bullets.txt in the jar) and win over them: of the rules that match, the
            # last one counts. Changes apply within a second, no restart.
            #
            # Who: a block id (minecraft:ice), a tag (#minecraft:leaves), a pattern (~minecraft:.*_glass), a block
            # sound type (@metal, @wood, @glass, @stone, @grass ...), a mod's machines and partial blocks (%hbm: its blocks
            # with a block entity, drawn by a renderer or not a full cube), * = any; several with commas, no spaces.
            # For entity/kill lines: an entity id, #tag, ~pattern or *.
            #
            # break <blocks> never | instant | hits <n> | damage <x> [drop] [pass] [replace=<block state>] [pierce=<x> [keep=<k>]]
            #   damage <x>: breaks once the bullets' damage on it adds up to x times its hardness; hits and damage
            #   show the cracks and are forgotten 20 s after the last hit. drop = drops itself; pass = the bullet
            #   flies on through the broken block; through = bullets always fly through it (no hole, no stop), it still
            #   counts the hits and breaks by its rule ("never through": flown through, never broken). A player's bullet breaks only where that player could break
            #   blocks (claims, spawn protection). pierce=<x>: a bullet goes through the block (a hole where it goes in and
            #   where it comes out) keeping k (default 1) of its damage less x times the block's hardness; one left with
            #   less than 1 damage stops in it. Its damage still counts towards breaking the block.
            # hit <blocks> [particles=<p>*<n>,...] [sound=<id>[/volume[/pitch]]] [range=<blocks>]   (each player's own game)
            #   particle: block (the hit block's crumbs), block:<id> (that block's crumbs), a particle id
            #   (minecraft:smoke); sound: block (the hit block's own hit sound), a sound id; none = nothing;
            #   range: heard over that many blocks, fading out to it (Minecraft's own: 16)
            # ricochet <blocks> yes | no   no: a bullet meeting it at a grazing angle stops in it instead of bouncing off
            #   (NTM's ricochet); yes (the default) bounces
            # entity <who> [particles=...] [sound=...] [to=all]   a bullet hits a creature (sound heard by the shooter,
            #   to=all: by everyone near)
            # kill <who> [particles=...] [sound=...] [to=all]     a bullet kills it
            # whizz sound=<id>[/volume[/pitch]] radius=<blocks> [crack=<id>[/volume[/pitch]] crackradius=<blocks>]
            #   someone else's bullet flies past your head; crack: the snap of a fast bullet passing very close
            # fly sound=<id>[/volume[/pitch]]                    a sound that flies with every bullet
            #
            # Examples:
            # break minecraft:melon,minecraft:pumpkin damage 2 drop
            # break #minecraft:leaves never
            # hit @metal particles=block*4,minecraft:crit*3 sound=hbm:metal_impact/0.6
            # entity * particles=block:minecraft:redstone_block*6
            # kill minecraft:zombie sound=minecraft:entity.experience_orb.pickup/0.5/0.6
            # whizz sound=none
            """;
}

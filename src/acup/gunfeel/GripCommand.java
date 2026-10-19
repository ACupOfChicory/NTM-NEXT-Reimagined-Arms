package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

// /gunfeel grip right|left <x> <y> <z>   hand position for the held NTM gun (fractions of the model size)
// /gunfeel grip show | reset
// /gunfeel set|unset|get <setting> [value]   per-gun override of any setting for the held gun; /gunfeel settings
public final class GripCommand {
    private GripCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("gunfeel")
                .then(Commands.literal("set").then(Commands.argument("key", StringArgumentType.word()).suggests(GripCommand::keys)
                        .executes(GripCommand::describe)
                        .then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> {
                            Identifier id = held(c);
                            String key = StringArgumentType.getString(c, "key");
                            if (id == null || !checkKey(c, key)) return 0;
                            Grips.put(id.toString(), key, StringArgumentType.getString(c, "value").trim());
                            return settings(c);
                        }))))
                .then(Commands.literal("unset").then(Commands.argument("key", StringArgumentType.word()).suggests(GripCommand::keys)
                        .executes(c -> {
                            Identifier id = held(c);
                            String key = StringArgumentType.getString(c, "key");
                            if (id == null || !checkKey(c, key)) return 0;
                            Grips.put(id.toString(), key, null);
                            return settings(c);
                        })))
                .then(Commands.literal("get").then(Commands.argument("key", StringArgumentType.word()).suggests(GripCommand::keys)
                        .executes(c -> {
                            Identifier id = held(c);
                            String key = StringArgumentType.getString(c, "key");
                            if (id == null || !checkKey(c, key)) return 0;
                            String own = Grips.overrides(id.toString()).get(key);
                            c.getSource().sendSuccess(() -> Component.literal(key + " = "
                                    + (own != null ? own + "  (эта пушка)" : Config.globalValue(key) + "  (общее)")), false);
                            return 1;
                        })))
                .then(Commands.literal("settings").executes(GripCommand::settings))
                .then(Commands.literal("meshdump").executes(c -> RenderedMesh.dump(c.getSource())))
                .then(Commands.literal("armdebug").executes(c -> {
                    Arms.debugArms = !Arms.debugArms;
                    boolean on = Arms.debugArms;
                    c.getSource().sendSuccess(() -> Component.literal(on ? "Отладка рук включена: каждый кадр пишется в лог ([armdebug])" : "Отладка рук выключена"), false);
                    return 1;
                }))
                .then(Commands.literal("hitvolume").executes(c -> {
                    c.getSource().sendSuccess(() -> Component.literal("Громкость попаданий: " + Config.hitVolume
                            + " (1 = как в правилах, 0 = тихо, до 4; у взрывных не больше 1). /gunfeel hitvolume <число>"), false);
                    return 1;
                }).then(Commands.argument("volume", FloatArgumentType.floatArg(0F, 4F)).executes(c -> {
                    float v = FloatArgumentType.getFloat(c, "volume");
                    Config.hitVolume = v;
                    try {
                        Config.setInFile("hitVolume", Float.toString(v));
                    } catch (java.io.IOException e) {
                        c.getSource().sendFailure(Component.literal("Не записалось в конфиг: " + e.getMessage()));
                        return 0;
                    }
                    c.getSource().sendSuccess(() -> Component.literal("Громкость попаданий: " + v + (v > 1F ? " (звук накладывается " + (int) Math.ceil(v - 1e-3F) + " раза)" : "")), false);
                    return 1;
                })))
                .then(Commands.literal("sounddebug").executes(c -> {
                    BulletFx.debugSound = !BulletFx.debugSound;
                    BulletServer.debugRicochet = BulletFx.debugSound; // the built-in server of a single player world
                    boolean on = BulletFx.debugSound;
                    c.getSource().sendSuccess(() -> Component.literal(on ? "Отладка звуков включена: каждое попадание пишется в лог ([sounddebug])" : "Отладка звуков выключена"), false);
                    return 1;
                }))
                .then(Commands.literal("holedebug").executes(c -> {
                    Tracers.debugHoles = !Tracers.debugHoles;
                    PassableClip.debug = Tracers.debugHoles;
                    boolean on = Tracers.debugHoles;
                    c.getSource().sendSuccess(() -> Component.literal(on ? "Отладка дырок включена: каждое попадание пишется в лог ([holedebug])" : "Отладка дырок выключена"), false);
                    return 1;
                }))
                .then(Commands.literal("grip")
                .then(Commands.literal("show").executes(c -> show(c)))
                .then(Commands.literal("reset").executes(c -> {
                    Identifier id = held(c);
                    if (id == null) return 0;
                    Grips.reset(id + missing());
                    return show(c);
                }))
                .then(hand("right", true))
                .then(hand("left", false))
                .then(handAt("rightAt", true))
                .then(handAt("leftAt", false))));
    }

    // /gunfeel set <key> with no value: what the held gun uses now and the defaults under it
    private static int describe(CommandContext<CommandSourceStack> c) {
        Identifier id = held(c);
        String key = StringArgumentType.getString(c, "key");
        if (id == null || !checkKey(c, key)) return 0;
        String gun = id.toString();
        String own = Grips.own(gun, key), shipped = Grips.shipped(gun, key);
        String global = Config.globalValue(key), code = Config.codeValue(key);
        String now, from;
        if (own != null && !own.isEmpty()) {
            now = own;
            from = "твоя настройка этой пушки";
        } else if (shipped != null && own == null) {
            now = shipped;
            from = "по умолчанию для этой пушки в моде";
        } else {
            now = global;
            from = global != null && !global.equals(code) ? "твоя общая настройка" : "общее по умолчанию";
        }
        StringBuilder out = new StringBuilder(key).append(" (").append(gun).append(")\n");
        out.append("  сейчас: ").append(show(now)).append("  — ").append(from).append('\n');
        out.append("  по умолчанию для этой пушки: ").append(shipped != null ? shipped : "нет, берётся общее").append('\n');
        out.append("  общее по умолчанию: ").append(show(code));
        if (global != null && !global.equals(code)) out.append(" (у тебя в общем конфиге: ").append(show(global)).append(')');
        String text = out.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static String show(String v) {
        return v == null ? "?" : v.isEmpty() ? "(пусто)" : v;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> hand(String name, boolean right) {
        return Commands.literal(name).then(Commands.argument("x", FloatArgumentType.floatArg(-1F, 2F))
                .then(Commands.argument("y", FloatArgumentType.floatArg(-1F, 2F))
                        .then(Commands.argument("z", FloatArgumentType.floatArg(-1F, 2F)).executes(c -> {
                            Identifier id = held(c);
                            if (id == null) return 0;
                            Grips.set(id + missing(), right, FloatArgumentType.getFloat(c, "x"), FloatArgumentType.getFloat(c, "y"),
                                    FloatArgumentType.getFloat(c, "z"));
                            return show(c);
                        }))));
    }

    // /gunfeel grip leftAt <x> <y> <z>: the point in model units, as grip show prints it
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> handAt(String name, boolean right) {
        return Commands.literal(name).then(Commands.argument("x", FloatArgumentType.floatArg())
                .then(Commands.argument("y", FloatArgumentType.floatArg())
                        .then(Commands.argument("z", FloatArgumentType.floatArg()).executes(c -> {
                            Identifier id = held(c);
                            if (id == null) return 0;
                            Grips.setAt(id + missing(), right, FloatArgumentType.getFloat(c, "x"), FloatArgumentType.getFloat(c, "y"),
                                    FloatArgumentType.getFloat(c, "z"));
                            return show(c);
                        }))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> keys(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return net.minecraft.commands.SharedSuggestionProvider.suggest(Config.keys().stream().filter(k -> !k.equals("enabled")), b);
    }

    private static boolean checkKey(CommandContext<CommandSourceStack> c, String key) {
        if (Config.isKey(key) && !key.equals("enabled")) return true;
        c.getSource().sendFailure(Component.literal("Нет такой настройки: " + key));
        return false;
    }

    private static int settings(CommandContext<CommandSourceStack> c) {
        Identifier id = held(c);
        if (id == null) return 0;
        var own = Grips.overrides(id.toString());
        c.getSource().sendSuccess(() -> Component.literal(id + (own.isEmpty() ? ": своих настроек нет, всё из общего конфига"
                : ": " + own.toString().replaceAll("[{}]", ""))), false);
        return 1;
    }

    private static Identifier held(CommandContext<CommandSourceStack> c) {
        var player = Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof ItemGunBaseNT)) {
            c.getSource().sendFailure(Component.literal("Возьми пушку NTM в основную руку"));
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        Identifier id = held(c);
        if (id == null) return 0;
        String missing = missing();
        Grips.Grip g = Grips.get(id, missing);
        c.getSource().sendSuccess(() -> Component.literal(id + missing
                + "  right" + (g.rightAt() != null ? "At " + fmt(g.rightAt()) : " " + fmt(g.right()))
                + "  left" + (g.leftAt() != null ? "At " + fmt(g.leftAt()) : " " + fmt(g.left()))), false);
        return 1;
    }

    // model parts the held gun's variant does not draw ("-Stock-Barrel" for a sawn-off), "" = the full gun:
    // hand positions set while holding a variant go to that variant only
    private static String missing() {
        var player = Minecraft.getInstance().player;
        GunGeometry g = player == null ? null : Arms.geometry(player.getMainHandItem().getItem());
        return g == null ? "" : g.missing;
    }

    private static String fmt(float[] v) {
        return String.format("%.2f %.2f %.2f", v[0], v[1], v[2]);
    }
}

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
                .then(Commands.literal("grip")
                .then(Commands.literal("show").executes(c -> show(c)))
                .then(Commands.literal("reset").executes(c -> {
                    Identifier id = held(c);
                    if (id == null) return 0;
                    Grips.reset(id);
                    return show(c);
                }))
                .then(hand("right", true))
                .then(hand("left", false))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> hand(String name, boolean right) {
        return Commands.literal(name).then(Commands.argument("x", FloatArgumentType.floatArg(-1F, 2F))
                .then(Commands.argument("y", FloatArgumentType.floatArg(-1F, 2F))
                        .then(Commands.argument("z", FloatArgumentType.floatArg(-1F, 2F)).executes(c -> {
                            Identifier id = held(c);
                            if (id == null) return 0;
                            Grips.set(id, right, FloatArgumentType.getFloat(c, "x"), FloatArgumentType.getFloat(c, "y"),
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
        Grips.Grip g = Grips.get(id);
        c.getSource().sendSuccess(() -> Component.literal(id + "  right " + (g.rightAt() != null ? "в точке модели " + fmt(g.rightAt()) : fmt(g.right()))
                + "  left " + (g.leftAt() != null ? "в точке модели " + fmt(g.leftAt()) : fmt(g.left()))), false);
        return 1;
    }

    private static String fmt(float[] v) {
        return String.format("%.2f %.2f %.2f", v[0], v[1], v[2]);
    }
}

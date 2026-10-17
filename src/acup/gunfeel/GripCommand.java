package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

// /gunfeel grip right|left <x> <y> <z>   hand position for the held NTM gun (fractions of the model size)
// /gunfeel grip show | reset
public final class GripCommand {
    private GripCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("gunfeel").then(Commands.literal("grip")
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
        c.getSource().sendSuccess(() -> Component.literal(id + "  right " + fmt(g.right()) + "  left " + fmt(g.left())), false);
        return 1;
    }

    private static String fmt(float[] v) {
        return String.format("%.2f %.2f %.2f", v[0], v[1], v[2]);
    }
}

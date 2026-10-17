package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

// /gunaim                       how early the held aim comes back on the gun in hand
// /gunaim reload|inspect <sec>  set it (seconds before the reload / inspect ends)
public final class ReaimCommand {
    private ReaimCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        var root = Commands.literal("gunaim").executes(ReaimCommand::show);
        for (String kind : new String[] {"reload", "inspect"}) {
            root.then(Commands.literal(kind).then(Commands.argument("seconds", FloatArgumentType.floatArg(0F, 5F))
                    .executes(c -> set(c, kind, FloatArgumentType.getFloat(c, "seconds")))));
        }
        d.register(root);
    }

    private static String gun(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT)) {
            c.getSource().sendFailure(Component.literal("Возьми пушку NTM в руку"));
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int show(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String gun = gun(c);
        if (gun == null) return 0;
        c.getSource().sendSuccess(() -> Component.literal(gun + ": прицел возвращается за " + ReaimTimes.seconds(gun, "reload")
                + " с до конца перезарядки, за " + ReaimTimes.seconds(gun, "inspect") + " с до конца осмотра"), false);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> c, String kind, float seconds) throws CommandSyntaxException {
        String gun = gun(c);
        if (gun == null) return 0;
        ReaimTimes.set(gun, kind, seconds);
        c.getSource().sendSuccess(() -> Component.literal(gun + ": " + (kind.equals("reload") ? "перезарядка" : "осмотр") + " — прицел за " + seconds + " с до конца"), false);
        return 1;
    }
}

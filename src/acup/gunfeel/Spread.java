package acup.gunfeel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// Bullet spread by the shooter's state, server settings in ntm-gunfeel-server.properties (all 1 = NTM as it is):
// spreadStand, spreadMove, spreadSneak, spreadCrawl, spreadAim; a gun can have its own as <gun id>.spreadAim and so on.
// spreadStack = 1: every state the shooter is in counts (sneaking and moving and aiming multiply together);
// spreadStack = 0: only the body state that weighs most (crawl, then sneak, then move, else stand), times aim.
public final class Spread {
    private Spread() {
    }

    public static float mult(LivingEntity e, ItemStack stack, boolean aim) {
        String gun = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        boolean crawl = e.isVisuallyCrawling();
        boolean sneak = !crawl && e.isCrouching();
        var mv = e.getKnownMovement();
        boolean move = mv.x * mv.x + mv.z * mv.z > 1.0E-4;
        float out;
        if (get(gun, "spreadStack", 1F) >= 0.5F) {
            out = 1F;
            if (crawl) out *= get(gun, "spreadCrawl", 1F);
            if (sneak) out *= get(gun, "spreadSneak", 1F);
            if (move) out *= get(gun, "spreadMove", 1F);
            if (!crawl && !sneak && !move) out *= get(gun, "spreadStand", 1F);
        } else {
            out = get(gun, crawl ? "spreadCrawl" : sneak ? "spreadSneak" : move ? "spreadMove" : "spreadStand", 1F);
        }
        if (aim) out *= get(gun, "spreadAim", 1F);
        return Math.max(0F, out);
    }

    private static float get(String gun, String key, float def) {
        return ReaimTimes.number(gun + "." + key, ReaimTimes.number(key, def));
    }
}

package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client: the ammo lock key (Controls, "NTM-NEXT Reimagined Arms"; Y by default)
public final class AmmoLockKey {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("ntmgunfeel", "main"));
    public static final KeyMapping KEY = new KeyMapping("key.ntmgunfeel.ammo_lock", 89 /* GLFW_KEY_Y */, CATEGORY);

    private AmmoLockKey() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.registerCategory(CATEGORY);
            e.register(KEY);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.ClientTickEvent.Post.class, e -> {
            while (KEY.consumeClick()) {
                var player = Minecraft.getInstance().player;
                if (player == null || !(player.getMainHandItem().getItem() instanceof ItemGunBaseNT)) continue;
                try {
                    net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(AmmoLockPayload.INSTANCE);
                } catch (RuntimeException ex) {
                    // a server without this mod
                }
            }
        });
    }
}

package acup.gunfeel;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client: the ammo key (Controls, "NTM-NEXT Reimagined Arms"; Y by default). A tap locks or unlocks the held gun's
// ammo type; held, it opens the radial ammo menu (not on a locked gun), let go it takes the hovered ammo.
public final class AmmoLockKey {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("ntmgunfeel", "main"));
    public static final KeyMapping KEY = new KeyMapping("key.ntmgunfeel.ammo_lock", 89 /* GLFW_KEY_Y */, CATEGORY);
    private static final long HOLD_MS = 250;

    private static boolean wasDown, held;
    private static long downAt;

    private AmmoLockKey() {
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.registerCategory(CATEGORY);
            e.register(KEY);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, e -> RadialMenu.render(e.getGuiGraphics()));
    }

    private static void tick() {
        while (KEY.consumeClick()) {
        }
        var mc = Minecraft.getInstance();
        var player = mc.player;
        ItemStack stack = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        boolean gun = stack.getItem() instanceof ItemGunBaseNT;
        boolean down = KEY.isDown() && mc.gui.screen() == null;
        long now = System.currentTimeMillis();

        if (RadialMenu.isOpen() && (!gun || stack.getItem() != RadialMenu.gun().getItem() || mc.gui.screen() != null)) RadialMenu.close();

        if (down && !wasDown) {
            downAt = now;
            held = false;
        }
        if (down && !held && now - downAt >= HOLD_MS) {
            held = true;
            if (gun && !AmmoLock.locked(stack)) RadialMenu.open(stack);
        }
        if (!down && wasDown) {
            if (RadialMenu.isOpen()) {
                BulletConfig c = RadialMenu.hovered();
                RadialMenu.close();
                if (c != null) send(new AmmoSelectPayload(c.id));
            } else if (!held && gun) {
                send(AmmoLockPayload.INSTANCE);
            }
        }
        wasDown = down;
    }

    private static void send(CustomPacketPayload p) {
        try {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(p);
        } catch (RuntimeException ex) {
            // a server without this mod
        }
    }
}

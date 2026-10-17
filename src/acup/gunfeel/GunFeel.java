package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

// Client-side state and hooks; loaded only on the client (see GunFeelMod).
public final class GunFeel {
    public static final Logger LOGGER = LoggerFactory.getLogger("ntm-gunfeel");

    // Renderer drawing the first-person gun right now (null outside of it).
    public static ItemRenderWeaponBase current;
    public static boolean currentAkimbo;
    // Recoil NTM asked for since the last detected shot, used as the shot's strength.
    public static float pendingRecoil;
    private static final Map<Item, Long> SEEN_SHOT = new IdentityHashMap<>();

    static void init() {
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, e -> {
            Config.poll();
            if (Config.enabled) CameraRecoil.frame();
        });
        NeoForge.EVENT_BUS.addListener(RenderHandEvent.class, GunFeel::onRenderHand);
        NeoForge.EVENT_BUS.addListener(ViewportEvent.ComputeCameraAngles.class, e -> {
            if (Config.enabled && CameraRecoil.roll != 0F) e.setRoll(e.getRoll() + CameraRecoil.roll);
        });
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> GripCommand.register(e.getDispatcher()));
    }

    // The gun is held with both hands, so the off-hand item is not drawn (it stays in the slot).
    private static void onRenderHand(RenderHandEvent event) {
        if (!Config.enabled || !Config.hideOffhand || event.getHand() != InteractionHand.OFF_HAND) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (ItemRenderWeaponBase.get(player.getMainHandItem()) == null) return;
        if (ItemRenderWeaponBase.get(event.getItemStack()) != null) return; // dual wielding guns
        event.setCanceled(true);
    }

    // NTM stamps gun.lastShot[i] when the shot animation arrives; a newer stamp is a new shot.
    public static void checkShot(ItemStack stack) {
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return;
        long newest = 0;
        for (long t : gun.lastShot) newest = Math.max(newest, t);
        Long seen = SEEN_SHOT.put(gun, newest);
        if (seen == null || newest <= seen) return;
        float recoil = pendingRecoil;
        pendingRecoil = 0F;
        ModelRecoil.shot(ModelRecoil.strength(recoil, 0F), ItemGunBaseNT.getIsAiming(stack));
    }
}

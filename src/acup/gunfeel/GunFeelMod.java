package acup.gunfeel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

// Client: recoil, gun position, HUD. Server (and single player): hold-to-aim.
@Mod("ntmgunfeel")
public final class GunFeelMod {
    public GunFeelMod(Dist dist) {
        if (dist.isClient()) GunFeel.init();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class,
                e -> ReaimCommand.register(e.getDispatcher()));
    }
}

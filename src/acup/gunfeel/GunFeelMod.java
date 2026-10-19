package acup.gunfeel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// Client: recoil, gun position, HUD, arms, tracers. Server (and single player): hold-to-aim, bullet speed and hits.
@Mod("ntmgunfeel")
public final class GunFeelMod {
    public GunFeelMod(IEventBus modBus, Dist dist) {
        if (dist.isClient()) {
            GunFeel.init();
            HoleRender.register(modBus);
            AmmoLockKey.register(modBus);
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.RegisterCommandsEvent.class,
                e -> ReaimCommand.register(e.getDispatcher()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                e -> BulletServer.tick());
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            // optional: a client or server without this mod still connects
            PayloadRegistrar r = e.registrar("1").optional();
            if (dist.isClient()) r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC, Tracers::handle);
            else r.playToClient(ShotPayload.TYPE, ShotPayload.CODEC);
            r.playToServer(AmmoSelectPayload.TYPE, AmmoSelectPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) AmmoLock.select(sp, p.config());
            });
            r.playToServer(AmmoLockPayload.TYPE, AmmoLockPayload.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) AmmoLock.toggle(sp);
            });
        });
    }
}

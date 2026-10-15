package com.motvard.customdiscs.compat.sophisticated;

import com.motvard.customdiscs.CustomDiscsMod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry;

import java.util.List;

/** Hooks custom discs into the Sophisticated Backpacks / Storage jukebox upgrade. */
public final class SophisticatedCompat {

    private SophisticatedCompat() {}

    public static void init() {
        CustomDiscHandler handler = new CustomDiscHandler();
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, handler::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> handler.clear());
        // By server start every mod has registered its disc handlers: wrap the others so that a
        // storage switching to one of their discs drops our record of its custom disc.
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> wrapOtherHandlers(handler));
        // Handlers are matched in order and the built-in vanilla one, registered first, also
        // accepts our disc (it has the jukebox_playable component). Ours must come before it.
        List<IDiscHandler<?>> handlers = DiscHandlerRegistry.getHandlers();
        try {
            handlers.add(0, handler);
        } catch (UnsupportedOperationException e) {
            DiscHandlerRegistry.registerHandler(handler);
            CustomDiscsMod.LOGGER.warn("[Compat] Sophisticated disc handler list is read-only; custom discs may play silently in storage jukeboxes");
            return;
        }
        CustomDiscsMod.LOGGER.info("[Compat] Custom discs enabled in Sophisticated storage jukeboxes");
    }

    private static void wrapOtherHandlers(CustomDiscHandler handler) {
        try {
            DiscHandlerRegistry.getHandlers().replaceAll(h ->
                    h == handler || h instanceof ForgettingDiscHandler<?> ? h : wrap(h, handler));
        } catch (UnsupportedOperationException e) {
            CustomDiscsMod.LOGGER.warn("[Compat] Could not wrap Sophisticated disc handlers: list is read-only");
        }
    }

    private static <I> IDiscHandler<I> wrap(IDiscHandler<I> inner, CustomDiscHandler handler) {
        return new ForgettingDiscHandler<>(inner, handler::forget);
    }
}

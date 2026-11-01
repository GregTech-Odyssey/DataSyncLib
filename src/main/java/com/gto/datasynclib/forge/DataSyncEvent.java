package com.gto.datasynclib.forge;

import com.gto.datasynclib.util.AsyncExecutor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Session-scoped listeners for the library's own background executors: the server executor runs
 * between {@link ServerAboutToStartEvent} and {@link ServerStoppedEvent}, the client one between a
 * login and the matching logout.
 *
 * <p>The client listeners live in {@link ClientHooks} on purpose: that nested class is the only
 * place naming {@code net.minecraftforge.client.event.ClientPlayerNetworkEvent}, so a dedicated
 * server never resolves a client-only event class just because this common class was loaded.</p>
 *
 * <p>Listeners are registered with the explicit event class overload of
 * {@link net.minecraftforge.eventbus.api.IEventBus#addListener(EventPriority, boolean, Class,
 * java.util.function.Consumer)}: the generic form would have to infer the event type from the
 * method reference, which is exactly the case the event bus cannot resolve.</p>
 */
public final class DataSyncEvent {

    public static final AsyncExecutor SERVER_ASYNC_TICK_EXECUTOR = new AsyncExecutor(50);
    public static final AsyncExecutor CLIENT_ASYNC_TICK_EXECUTOR = new AsyncExecutor(50);

    private static boolean initialized;

    /**
     * Registers the listeners once; called from the mod constructor.
     */
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerAboutToStartEvent.class,
                (ServerAboutToStartEvent event) -> SERVER_ASYNC_TICK_EXECUTOR.activate());
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class,
                (ServerStoppedEvent event) -> SERVER_ASYNC_TICK_EXECUTOR.cancel());
        if (FMLLoader.getDist().isClient()) {
            ClientHooks.init();
        }
    }

    /**
     * Client-only listeners, kept in their own class so the dedicated server never loads it.
     */
    private static final class ClientHooks {

        private static void init() {
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn.class,
                    (net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) ->
                            CLIENT_ASYNC_TICK_EXECUTOR.activate());
            MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false,
                    net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut.class,
                    (net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) ->
                            CLIENT_ASYNC_TICK_EXECUTOR.cancel());
        }
    }

}

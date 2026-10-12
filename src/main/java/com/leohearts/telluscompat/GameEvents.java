package com.leohearts.telluscompat;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Listeners are registered through the annotation. Listeners added with
 * {@code NeoForge.EVENT_BUS.addListener} during mod construction never fire, so let
 * NeoForge register them at the right time instead.
 */
@EventBusSubscriber(modid = TellusAeronauticsCompat.MOD_ID)
public final class GameEvents {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TellusAeronauticsCompat.applyCurves(event.getServer());
    }

    /** Sable drops runtime-registered physics and reloads them from the datapacks on sync. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        MinecraftServer server = event.getPlayerList().getServer();
        TellusAeronauticsCompat.applyCurves(server);
    }
}

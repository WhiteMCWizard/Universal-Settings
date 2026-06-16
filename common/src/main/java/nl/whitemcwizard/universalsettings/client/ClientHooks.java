package nl.whitemcwizard.universalsettings.client;

import net.minecraft.client.Minecraft;
import nl.whitemcwizard.universalsettings.sync.SyncManager;
import nl.whitemcwizard.universalsettings.ui.Toasts;

/**
 * Loader-agnostic client lifecycle entry points. "Client started" is derived from
 * the first tick because NeoForge has no direct client-started event.
 */
public final class ClientHooks {

    private static boolean started = false;

    private ClientHooks() {
    }

    public static void onEndTick(Minecraft mc) {
        if (!started) {
            if (!Toasts.languageLoaded()) {
                return;
            }
            started = true;
            SyncManager.get().onClientStarted(mc);
        }
        SyncManager.get().onEndTick(mc);
    }

    public static void onClientStopping() {
        SyncManager.get().onClientStopping();
    }
}

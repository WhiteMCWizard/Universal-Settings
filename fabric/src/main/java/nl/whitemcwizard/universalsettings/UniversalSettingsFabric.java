package nl.whitemcwizard.universalsettings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import nl.whitemcwizard.universalsettings.client.ClientHooks;

public class UniversalSettingsFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        UniversalSettings.init();
        ClientTickEvents.END_CLIENT_TICK.register(ClientHooks::onEndTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientHooks.onClientStopping());
    }
}

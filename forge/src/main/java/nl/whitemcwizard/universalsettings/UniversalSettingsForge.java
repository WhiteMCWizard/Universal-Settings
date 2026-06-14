package nl.whitemcwizard.universalsettings;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import nl.whitemcwizard.universalsettings.client.ClientHooks;
import nl.whitemcwizard.universalsettings.ui.ProfilesScreen;

@Mod(Constants.MOD_ID)
public class UniversalSettingsForge {

    public UniversalSettingsForge() {
        UniversalSettings.init();
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) {
                ClientHooks.onEndTick(Minecraft.getInstance());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> ClientHooks.onClientStopping());
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new ProfilesScreen(parent)));
    }
}

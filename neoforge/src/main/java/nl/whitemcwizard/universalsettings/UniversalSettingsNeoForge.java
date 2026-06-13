package nl.whitemcwizard.universalsettings;

import net.minecraft.client.Minecraft;
//? if >=1.21 {
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
//?} else {
/*import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
*///?}
import nl.whitemcwizard.universalsettings.client.ClientHooks;
import nl.whitemcwizard.universalsettings.ui.ProfilesScreen;

// On 1.20.1 NeoForge is still a Forge fork with a different event/extension API.
//? if >=1.21 {
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class UniversalSettingsNeoForge {

    public UniversalSettingsNeoForge(ModContainer container) {
        UniversalSettings.init();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ClientHooks.onEndTick(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> ClientHooks.onClientStopping());
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (modContainer, parent) -> new ProfilesScreen(parent));
    }
}
//?} else {
/*@Mod(Constants.MOD_ID)
public class UniversalSettingsNeoForge {

    public UniversalSettingsNeoForge() {
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
*///?}

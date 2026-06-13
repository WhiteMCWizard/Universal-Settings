package nl.whitemcwizard.universalsettings;

import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.platform.Services;
import nl.whitemcwizard.universalsettings.sync.SyncManager;

public class UniversalSettings {

    public static void init() {
        Constants.LOG.info("Initializing {} on {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
        ModConfig.load();
        SyncManager.get();
    }
}

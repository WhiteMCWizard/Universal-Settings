package nl.whitemcwizard.universalsettings.platform.services;

import java.nio.file.Path;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    /** This mod's version string (from the loader metadata), or "unknown". */
    String getModVersion();

    boolean isDevelopmentEnvironment();

    Path getConfigDir();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}

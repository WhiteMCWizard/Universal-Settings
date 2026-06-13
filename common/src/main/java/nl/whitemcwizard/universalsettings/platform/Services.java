package nl.whitemcwizard.universalsettings.platform;

import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/** Loads loader-specific service implementations declared in META-INF/services. */
public class Services {

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}

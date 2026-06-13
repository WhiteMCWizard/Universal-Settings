package nl.whitemcwizard.universalsettings.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "universalsettings.json";

    // Overwriting the DataFixer version marker from another game version would
    // corrupt options upgrading, so it can never be synced. startedCleanly
    // (1.21.9+) is the crash-detection marker the game flips to false in the
    // Minecraft constructor and back on load-finish; syncing it would carry one
    // machine's crash state to another, and the false-during-startup value made
    // the first-run check think a fresh instance had customized settings.
    private static final Set<String> FORCE_EXCLUDED = Set.of("version", "startedCleanly");

    // Truly machine-bound settings that stay local by default.
    private static final List<String> DEFAULT_EXCLUDED = List.of(
            "fullscreen", "fullscreenResolution", "overrideWidth", "overrideHeight",
            "soundDevice"
    );

    private static ModConfig instance;

    public String serverUrl = defaultServerUrl();
    public boolean enabled = true;
    public String activeProfile = null;
    public boolean syncServers = true;
    public boolean firstRunDone = false;
    public String lastSyncedHash = "";
    public long lastSyncedAt = 0;
    public List<String> excludedKeys = new ArrayList<>(DEFAULT_EXCLUDED);
    // Server-side exclusion lists, cached locally so offline sessions filter the
    // same way; profileExclusions tracks the active profile.
    public List<String> profileExclusions = new ArrayList<>();
    public List<String> globalExclusions = new ArrayList<>();
    // Full content of the active profile as last seen on the server, including
    // keys this game version doesn't know about (vanilla drops unknown keys when
    // rewriting options.txt). Lets change detection see the whole profile.
    public Map<String, String> profileOptionsCache = new LinkedHashMap<>();

    public static synchronized ModConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                instance = GSON.fromJson(Files.readString(path), ModConfig.class);
            } catch (Exception e) {
                Constants.LOG.error("Failed to read {}, using defaults", path, e);
            }
        }
        if (instance == null) {
            instance = new ModConfig();
        }
        // Write defaults on first launch so users can discover and edit the file.
        instance.save();
    }

    public synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            Constants.LOG.error("Failed to write {}", path, e);
        }
    }

    /** Excluded at any level: forced, instance, active profile, or account-wide. */
    public boolean isExcluded(String key) {
        return FORCE_EXCLUDED.contains(key) || excludedKeys.contains(key)
                || profileExclusions.contains(key) || globalExclusions.contains(key);
    }

    public static boolean isForceExcluded(String key) {
        return FORCE_EXCLUDED.contains(key);
    }

    private static Path configPath() {
        return Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
    }

    /** Dev run dirs talk to a local dev server, not production. */
    private static String defaultServerUrl() {
        return Services.PLATFORM.isDevelopmentEnvironment()
                ? "http://localhost:8080"
                : Constants.DEFAULT_SERVER_URL;
    }
}

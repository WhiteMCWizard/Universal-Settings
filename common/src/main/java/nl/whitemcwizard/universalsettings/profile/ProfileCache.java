package nl.whitemcwizard.universalsettings.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Local copy of the player's profiles as last seen on the sync server, so profile
 * browsing and switching keep working while the server is unreachable. Persisted
 * next to the mod config.
 */
public final class ProfileCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "universalsettings-profiles.json";

    private static ProfileCache instance;

    private List<Entry> profiles = new ArrayList<>();

    public static synchronized ProfileCache get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public synchronized void replaceAll(List<Entry> entries) {
        profiles = new ArrayList<>(entries);
        save();
    }

    /**
     * Upserts one profile after a pull or push. A null {@code isDefault} keeps the
     * cached flag; an existing entry's exclusion list is likewise preserved, since
     * pushes carry neither.
     */
    public synchronized void update(ProfileData data, Boolean isDefault) {
        Entry entry = profiles.stream()
                .filter(p -> p.name.equals(data.name()))
                .findFirst().orElse(null);
        if (entry == null) {
            entry = new Entry();
            entry.name = data.name();
            entry.excludedKeys = new ArrayList<>(data.excludedKeys());
            profiles.add(entry);
        }
        entry.options = data.options();
        entry.serversDat = data.serversDat() == null
                ? null : Base64.getEncoder().encodeToString(data.serversDat());
        entry.gameVersion = data.gameVersion();
        entry.updatedAt = data.updatedAt();
        if (isDefault != null) {
            if (isDefault) {
                profiles.forEach(p -> p.isDefault = false);
            }
            entry.isDefault = isDefault;
        }
        save();
    }

    public synchronized Optional<ProfileData> find(String name) {
        return profiles.stream()
                .filter(p -> name == null ? p.isDefault : p.name.equals(name))
                .findFirst()
                .map(Entry::toData);
    }

    public synchronized List<ProfileSummary> summaries() {
        List<ProfileSummary> result = new ArrayList<>(profiles.size());
        for (Entry entry : profiles) {
            result.add(new ProfileSummary(entry.name, entry.updatedAt, entry.gameVersion, entry.isDefault));
        }
        return result;
    }

    public synchronized boolean isEmpty() {
        return profiles.isEmpty();
    }

    private static ProfileCache load() {
        Path path = cachePath();
        if (Files.exists(path)) {
            try {
                ProfileCache cache = GSON.fromJson(Files.readString(path), ProfileCache.class);
                if (cache != null && cache.profiles != null) {
                    return cache;
                }
            } catch (Exception e) {
                Constants.LOG.warn("Failed to read {}, starting with an empty profile cache", path, e);
            }
        }
        return new ProfileCache();
    }

    private synchronized void save() {
        Path path = cachePath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            Constants.LOG.error("Failed to write {}", path, e);
        }
    }

    private static Path cachePath() {
        return Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
    }

    public static class Entry {
        String name;
        Map<String, String> options;
        String serversDat; // base64 (binary NBT)
        String gameVersion;
        long updatedAt;
        boolean isDefault;
        List<String> excludedKeys;

        public static Entry of(ProfileData data, boolean isDefault) {
            Entry entry = new Entry();
            entry.name = data.name();
            entry.options = data.options();
            entry.serversDat = data.serversDat() == null
                    ? null : Base64.getEncoder().encodeToString(data.serversDat());
            entry.gameVersion = data.gameVersion();
            entry.updatedAt = data.updatedAt();
            entry.isDefault = isDefault;
            entry.excludedKeys = new ArrayList<>(data.excludedKeys());
            return entry;
        }

        ProfileData toData() {
            return new ProfileData(name, options,
                    serversDat == null ? null : Base64.getDecoder().decode(serversDat),
                    gameVersion, updatedAt,
                    excludedKeys == null ? List.of() : excludedKeys);
        }
    }
}

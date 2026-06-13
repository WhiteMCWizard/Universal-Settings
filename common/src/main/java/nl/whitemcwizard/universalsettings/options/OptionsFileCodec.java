package nl.whitemcwizard.universalsettings.options;

import nl.whitemcwizard.universalsettings.config.ModConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads and writes options.txt as an ordered key-value map. Values may themselves
 * contain {@code :}, so lines are split on the first colon only.
 */
public final class OptionsFileCodec {

    private OptionsFileCodec() {
    }

    public static LinkedHashMap<String, String> parse(Path file) throws IOException {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return entries;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int sep = line.indexOf(':');
            if (sep < 0) {
                continue;
            }
            entries.put(line.substring(0, sep), line.substring(sep + 1));
        }
        return entries;
    }

    public static void write(Path file, Map<String, String> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Drops only the force-excluded keys, ignoring the player's exclusion lists.
     * For profile content that must be complete regardless of what the current
     * profile/instance happens to ignore.
     */
    public static LinkedHashMap<String, String> withoutForcedExclusions(Map<String, String> entries) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(entries);
        result.keySet().removeIf(ModConfig::isForceExcluded);
        return result;
    }

    public static LinkedHashMap<String, String> filterSyncable(Map<String, String> entries) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        ModConfig config = ModConfig.get();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!config.isExcluded(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Canonical hash of the synced state, used to decide whether anything actually
     * changed since the last push/pull.
     */
    public static String syncHash(Map<String, String> syncableEntries, byte[] serversDat) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : new TreeMap<>(syncableEntries).entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            if (serversDat != null) {
                digest.update(serversDat);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

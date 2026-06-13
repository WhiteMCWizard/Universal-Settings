package nl.whitemcwizard.universalsettings.options;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.config.ModConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether the local settings are still vanilla defaults, which lets the
 * first-run flow apply the synced profile silently instead of prompting.
 */
public final class DefaultOptionsDetector {

    // Keys the game or the mod loader rewrites without the player touching a
    // setting (tutorial bookkeeping, loader-injected resource packs); they must
    // not turn a default install into a first-run prompt.
    private static final Set<String> ENVIRONMENT_MANAGED = Set.of(
            "onboardAccessibility",
            "tutorialStep",
            "joinedFirstServer",
            "lastServer",
            "resourcePacks",
            "incompatibleResourcePacks"
    );

    // Since 1.21.9 the Minecraft constructor applies the default graphics preset
    // (FANCY) right after renderer init, overwriting these options' raw
    // OptionInstance defaults (e.g. renderDistance 12 -> 16). A fresh install's
    // options.txt holds the raw defaults until the post-load re-save and the
    // preset values afterwards, so both must count as "untouched". Values mirror
    // GraphicsPreset.FANCY in 1.21.9-26.1.2; revisit when Mojang changes the preset.
    private static final Map<String, String> DEFAULT_PRESET_VALUES =
            //? if >=1.21.9 {
            Map.ofEntries(
                    Map.entry("biomeBlendRadius", "2"),
                    Map.entry("renderDistance", "16"),
                    Map.entry("prioritizeChunkUpdates", "1"),
                    Map.entry("simulationDistance", "12"),
                    Map.entry("ao", "true"),
                    Map.entry("renderClouds", "\"true\""),
                    Map.entry("particles", "0"),
                    Map.entry("mipmapLevels", "4"),
                    Map.entry("entityShadows", "true"),
                    Map.entry("entityDistanceScaling", "1.0"),
                    Map.entry("menuBackgroundBlurriness", "5"),
                    Map.entry("cloudRange", "64"),
                    Map.entry("cutoutLeaves", "true"),
                    Map.entry("improvedTransparency", "false"),
                    Map.entry("weatherRadius", "10"),
                    Map.entry("maxAnisotropyBit", "1"),
                    Map.entry("textureFiltering", "1")
            );
            //?} else {
            /*Map.of();
            *///?}

    private DefaultOptionsDetector() {
    }

    /**
     * True when every synced option matches its vanilla default. Must run on the
     * render thread. Detection failures err on "differs" so the user gets a
     * prompt instead of a silent overwrite.
     */
    public static boolean isLocalVanillaDefault(Minecraft mc, boolean optionsFileExisted) {
        if (!optionsFileExisted) {
            return true;
        }
        try {
            // KeyMapping instances are global singletons, so the throwaway Options
            // below would save the *current* binds. Check binds directly instead.
            for (KeyMapping mapping : mc.options.keyMappings) {
                if (!mapping.isDefault()) {
                    Constants.LOG.info("First-run check: keybind '{}' is not at its default", mapping.getName());
                    return false;
                }
            }
            Map<String, String> defaults = vanillaDefaults(mc);
            Map<String, String> local = OptionsFileCodec.parse(mc.options.getFile().toPath());
            ModConfig config = ModConfig.get();
            for (Map.Entry<String, String> entry : local.entrySet()) {
                String key = entry.getKey();
                if (config.isExcluded(key) || key.startsWith("key_") || ENVIRONMENT_MANAGED.contains(key)) {
                    continue;
                }
                String defaultValue = defaults.get(key);
                if (defaultValue != null && !defaultValue.equals(entry.getValue())
                        && !entry.getValue().equals(DEFAULT_PRESET_VALUES.get(key))) {
                    Constants.LOG.info("First-run check: option '{}' is '{}' but the default is '{}'",
                            key, entry.getValue(), defaultValue);
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Constants.LOG.warn("Could not determine whether local settings are default, assuming they differ", e);
            return false;
        }
    }

    /**
     * What a brand-new installation's options would be, including default
     * keybinds. Must run on the render thread.
     */
    public static Map<String, String> fullVanillaDefaults(Minecraft mc) throws IOException {
        Map<String, String> defaults = vanillaDefaults(mc);
        for (KeyMapping mapping : mc.options.keyMappings) {
            defaults.put("key_" + mapping.getName(), mapping.getDefaultKey().getName());
        }
        return defaults;
    }

    /**
     * Writes a fresh Options instance to a temp dir and parses the result, yielding
     * the exact per-version default value for every option key.
     */
    private static Map<String, String> vanillaDefaults(Minecraft mc) throws IOException {
        Path tempDir = Files.createTempDirectory("universalsettings-defaults");
        try {
            Options throwaway = new Options(mc, tempDir.toFile());
            throwaway.save();
            LinkedHashMap<String, String> defaults = OptionsFileCodec.parse(tempDir.resolve("options.txt"));
            defaults.keySet().removeIf(key -> key.startsWith("key_"));
            return defaults;
        } finally {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
            }
        }
    }
}

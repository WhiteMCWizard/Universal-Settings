package nl.whitemcwizard.universalsettings.sync;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.options.OptionsFileCodec;
import nl.whitemcwizard.universalsettings.profile.ProfileData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a remote profile to the running game. Must be called on the render
 * thread: it rewrites options.txt, makes vanilla re-read it, and refreshes the
 * keybind lookup so new binds work without a restart.
 */
public final class OptionsApplier {

    private OptionsApplier() {
    }

    public static void apply(Minecraft mc, ProfileData profile) throws IOException {
        ModConfig config = ModConfig.get();
        Path optionsFile = mc.options.getFile().toPath();

        // A fresh instance may not have written options.txt yet. Profiles never
        // contain the force-excluded DataFixer "version" marker, so merging into
        // an empty base would write a file without it — load() then treats the
        // file as pre-1.13 and the LWJGL3 key fix crashes on modern key names,
        // resetting all options to defaults. Save first to get vanilla's marker.
        if (!Files.exists(optionsFile)) {
            mc.options.save();
        }

        // Remote non-excluded keys overwrite local ones; local-only keys (a newer
        // game version's settings, other mods' entries) are kept.
        LinkedHashMap<String, String> merged = OptionsFileCodec.parse(optionsFile);
        for (Map.Entry<String, String> entry : profile.options().entrySet()) {
            if (!config.isExcluded(entry.getKey())) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        OptionsFileCodec.write(optionsFile, merged);
        mc.options.load();
        KeyMapping.resetMapping();
        // Re-layout the GUI so a synced guiScale takes effect immediately.
        //? if >=26.1 {
        mc.resizeGui();
        //?} else {
        /*mc.resizeDisplay();
        *///?}

        // The per-profile server list is only applied in PROFILE mode; ACCOUNT mode
        // applies the account-level list separately (see SyncManager).
        if (config.serversMode.equals(ModConfig.SERVERS_PROFILE)) {
            applyServersDat(mc, profile.serversDat());
        }
        Constants.LOG.info("Applied profile '{}' ({} options)", profile.name(), profile.options().size());
    }

    /**
     * Writes {@code serversDat} to the game's servers.dat when it differs from
     * what's on disk. No-op when {@code serversDat} is null. The multiplayer screen
     * reads servers.dat fresh whenever it opens, so replacing the bytes is enough.
     */
    public static void applyServersDat(Minecraft mc, byte[] serversDat) throws IOException {
        if (serversDat == null) {
            return;
        }
        Path serversFile = mc.gameDirectory.toPath().resolve("servers.dat");
        byte[] local = Files.exists(serversFile) ? Files.readAllBytes(serversFile) : null;
        if (!Arrays.equals(local, serversDat)) {
            Files.write(serversFile, serversDat);
        }
    }
}

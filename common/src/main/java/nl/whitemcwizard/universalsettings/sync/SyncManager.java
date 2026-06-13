package nl.whitemcwizard.universalsettings.sync;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.net.ApiClient;
import nl.whitemcwizard.universalsettings.options.DefaultOptionsDetector;
import nl.whitemcwizard.universalsettings.options.OptionsFileCodec;
import nl.whitemcwizard.universalsettings.profile.ProfileCache;
import nl.whitemcwizard.universalsettings.profile.ProfileData;
import nl.whitemcwizard.universalsettings.profile.ProfileSummary;
import nl.whitemcwizard.universalsettings.ui.FirstRunScreen;
import nl.whitemcwizard.universalsettings.ui.Toasts;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Orchestrates the sync lifecycle: startup pull (or first-run flow), debounced
 * pushes on change, and a final flush on quit. All network work runs on a single
 * background daemon thread; anything touching game state hops to the render thread.
 */
public class SyncManager {

    private static SyncManager instance;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "universalsettings-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final ApiClient api = new ApiClient();
    private final DebouncedPusher pusher = new DebouncedPusher(executor, this::push);

    private volatile boolean started;
    private volatile boolean stopping;
    private volatile boolean applying;
    private volatile int startupAttempts;
    private volatile boolean optionsFileExisted;
    private volatile boolean authFailedToastShown;
    private volatile ProfileData pendingPrompt;
    private volatile boolean offline;
    private volatile boolean reconcileScheduled;

    public static synchronized SyncManager get() {
        if (instance == null) {
            instance = new SyncManager();
        }
        return instance;
    }

    public void onClientStarted(Minecraft mc) {
        if (!ModConfig.get().enabled) {
            return;
        }
        optionsFileExisted = mc.options.getFile().exists();
        started = true;
        executor.execute(() -> startupSync(mc));
    }

    public void onEndTick(Minecraft mc) {
        ProfileData prompt = pendingPrompt;
        if (prompt != null && mc.screen instanceof TitleScreen) {
            pendingPrompt = null;
            mc.setScreen(new FirstRunScreen(mc.screen, prompt));
        }
    }

    public void onClientStopping() {
        if (!started) {
            return;
        }
        stopping = true;
        pusher.cancel();
        try {
            // Get the latest in-memory options onto disk before the final push.
            Minecraft.getInstance().options.save();
        } catch (Exception e) {
            Constants.LOG.debug("Could not save options during shutdown", e);
        }
        try {
            // The hard cap keeps a dead server from hanging game exit.
            executor.submit(this::push).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Constants.LOG.debug("Final sync push did not complete before shutdown");
        }
        executor.shutdown();
    }

    public void onOptionsSaved() {
        if (started && !stopping && !applying && ModConfig.get().firstRunDone) {
            pusher.schedule();
        }
    }

    public void onServersSaved() {
        if (started && !stopping && !applying && ModConfig.get().firstRunDone && ModConfig.get().syncServers) {
            pusher.schedule();
        }
    }

    public void acceptRemoteProfile(ProfileData profile) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        cacheProfileState(config, profile);
        applying = true;
        try {
            OptionsApplier.apply(mc, profile);
        } catch (IOException e) {
            Constants.LOG.error("Failed to apply synced profile", e);
            return;
        } finally {
            applying = false;
        }
        config.activeProfile = profile.name();
        config.firstRunDone = true;
        Toasts.show("universalsettings.toast.synced");
        executor.execute(() -> {
            recordSyncedState(mc, config, profile.updatedAt());
            config.save();
        });
    }

    public void keepLocalAsNewProfile() {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        String name = deviceProfileName();
        config.activeProfile = name;
        config.firstRunDone = true;
        config.save();
        executor.execute(() -> {
            try {
                doPush(mc, config, name);
                Toasts.showLater(mc, "universalsettings.toast.uploaded");
            } catch (Exception e) {
                Constants.LOG.warn("Failed to upload local settings as profile '{}': {}", name, e.toString());
                if (noteAuthFailed(mc)) {
                    return;
                }
                pusher.scheduleRetry();
            }
        });
    }

    public void fetchProfileList(Consumer<List<ProfileSummary>> onSuccess, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        executor.execute(() -> {
            try {
                List<ProfileSummary> list = api.listProfiles(playerUuid(mc));
                noteOnline(mc);
                mc.execute(() -> onSuccess.accept(list));
                refreshProfileCache(mc, list);
            } catch (Exception e) {
                if (isConnectivityError(e) && !ProfileCache.get().isEmpty()) {
                    List<ProfileSummary> cached = ProfileCache.get().summaries();
                    noteOffline(mc, e);
                    scheduleReconcile(mc, 60);
                    mc.execute(() -> onSuccess.accept(cached));
                } else {
                    reportError(mc, e, onError);
                }
            }
        });
    }

    public void switchToProfile(String name, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        executor.execute(() -> {
            try {
                // Debounced changes belong to the profile being left; upload them
                // first or they would be silently lost.
                flushPendingChanges(mc, config);
            } catch (Exception e) {
                reportError(mc, e, onError);
                return;
            }
            try {
                Optional<ProfileData> remote = api.fetchProfile(playerUuid(mc), name, gameVersion());
                if (remote.isEmpty()) {
                    mc.execute(() -> onError.accept("profile not found"));
                    return;
                }
                noteOnline(mc);
                ProfileCache.get().update(remote.get(), null);
                cacheProfileState(config, remote.get());
                applyOnRenderThread(mc, remote.get());
                config.activeProfile = name;
                recordSyncedState(mc, config, remote.get().updatedAt());
                config.save();
                mc.execute(onDone);
            } catch (Exception e) {
                // During an outage, switch to the locally cached copy instead.
                Optional<ProfileData> cached = isConnectivityError(e)
                        ? ProfileCache.get().find(name) : Optional.empty();
                if (cached.isEmpty()) {
                    reportError(mc, e, onError);
                    return;
                }
                noteOffline(mc, e);
                scheduleReconcile(mc, 60);
                cacheProfileState(config, cached.get());
                applyOnRenderThread(mc, cached.get());
                config.activeProfile = name;
                recordSyncedState(mc, config, cached.get().updatedAt());
                config.save();
                mc.execute(onDone);
            }
        });
    }

    public void saveCurrentAsProfile(String name, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        executor.execute(() -> {
            try {
                saveOptionsToDisk(mc);
                doPush(mc, config, name);
                config.activeProfile = name;
                config.save();
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    private void flushPendingChanges(Minecraft mc, ModConfig config) throws IOException, InterruptedException {
        saveOptionsToDisk(mc);
        pusher.cancel();
        if (config.firstRunDone && config.activeProfile != null
                && !currentHash(mc, config).equals(config.lastSyncedHash)) {
            doPush(mc, config, config.activeProfile);
        }
    }

    /** Option edits live in memory until vanilla saves; force that now. */
    private void saveOptionsToDisk(Minecraft mc) {
        mc.submit(() -> {
            applying = true;
            try {
                mc.options.save();
            } finally {
                applying = false;
            }
        }).join();
    }

    public void createProfileFromDefaults(String name, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        executor.execute(() -> {
            try {
                Map<String, String> defaults = mc.submit(() -> {
                    try {
                        return DefaultOptionsDetector.fullVanillaDefaults(mc);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).join();
                // Exclusions of the currently active profile must not leak into a new one.
                api.putProfile(playerUuid(mc), name,
                        OptionsFileCodec.withoutForcedExclusions(defaults), null, gameVersion());
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    public void duplicateProfile(String sourceName, String newName, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        executor.execute(() -> {
            try {
                Optional<ProfileData> source = api.fetchProfile(playerUuid(mc), sourceName, gameVersion());
                if (source.isEmpty()) {
                    mc.execute(() -> onError.accept("profile not found"));
                    return;
                }
                api.putProfile(playerUuid(mc), newName,
                        source.get().options(), source.get().serversDat(), source.get().gameVersion(),
                        source.get().excludedKeys());
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    public void renameProfile(String name, String newName, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        executor.execute(() -> {
            try {
                api.renameProfile(playerUuid(mc), name, newName);
                if (name.equals(config.activeProfile)) {
                    config.activeProfile = newName;
                    config.save();
                }
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    public void setDefaultProfile(String name, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        executor.execute(() -> {
            try {
                api.setDefaultProfile(playerUuid(mc), name);
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    /**
     * Deleting the default profile recreates it with vanilla default settings, so
     * there is always a default. If it was also active, the fresh defaults are
     * applied locally too — otherwise normal sync would immediately push the
     * current settings back over them.
     */
    public void deleteProfile(String name, boolean wasDefault, Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        executor.execute(() -> {
            try {
                boolean wasActive = name.equals(config.activeProfile);
                if (wasActive) {
                    pusher.cancel();
                }
                api.deleteProfile(playerUuid(mc), name);
                if (wasDefault) {
                    Map<String, String> defaults = mc.submit(() -> {
                        try {
                            return OptionsFileCodec.withoutForcedExclusions(DefaultOptionsDetector.fullVanillaDefaults(mc));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).join();
                    // With no default left, this PUT auto-becomes the default server-side.
                    long updatedAt = api.putProfile(playerUuid(mc), name,
                            defaults, null, gameVersion());
                    if (wasActive) {
                        ProfileData fresh = new ProfileData(name, defaults, null,
                                gameVersion(), updatedAt, List.of());
                        cacheProfileState(config, fresh);
                        applyOnRenderThread(mc, fresh);
                        recordSyncedState(mc, config, updatedAt);
                        config.save();
                    }
                }
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    /**
     * Persists exclusion changes: the instance list is local, the profile and
     * account-wide lists go to the server when dirty.
     */
    public void applyExclusionChanges(List<String> instanceList,
                                      List<String> profileList, boolean profileDirty,
                                      List<String> globalList, boolean globalDirty,
                                      Runnable onDone, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        executor.execute(() -> {
            config.excludedKeys = new java.util.ArrayList<>(instanceList);
            config.save();
            try {
                if (profileDirty && config.activeProfile != null) {
                    api.putProfileExclusions(playerUuid(mc), config.activeProfile, profileList);
                    config.profileExclusions = new java.util.ArrayList<>(profileList);
                }
                if (globalDirty) {
                    api.putGlobalExclusions(playerUuid(mc), globalList);
                    config.globalExclusions = new java.util.ArrayList<>(globalList);
                }
                config.save();
                pusher.schedule();
                mc.execute(onDone);
            } catch (Exception e) {
                reportError(mc, e, onError);
            }
        });
    }

    /**
     * Records what the server holds for this profile. The content cache remembers
     * keys this game version doesn't understand and would otherwise lose when
     * vanilla rewrites options.txt.
     */
    private static void cacheProfileState(ModConfig config, ProfileData profile) {
        config.profileExclusions = new java.util.ArrayList<>(profile.excludedKeys());
        config.profileOptionsCache = OptionsFileCodec.withoutForcedExclusions(profile.options());
    }

    private void startupSync(Minecraft mc) {
        ModConfig config = ModConfig.get();
        try {
            config.globalExclusions = new java.util.ArrayList<>(
                    api.fetchGlobalExclusions(playerUuid(mc)));
        } catch (Exception e) {
            Constants.LOG.debug("Could not refresh account-wide exclusions, using cached copy");
        }
        try {
            if (!config.firstRunDone) {
                firstRun(mc, config);
            } else {
                pullAndApply(mc, config);
            }
            noteOnline(mc);
            refreshProfileCache(mc, null);
        } catch (Exception e) {
            // Outages can outlast any fixed retry budget; keep retrying with a
            // backoff capped at five minutes until the server comes back.
            if (stopping) {
                return;
            }
            if (noteAuthFailed(mc)) {
                // The handshake failure latches for the session, so retrying is
                // pointless; the next launch authenticates again.
                Constants.LOG.warn("Not authenticated with the sync server, sync is disabled this session");
                return;
            }
            startupAttempts++;
            long delaySeconds = Math.min(300, 60L << Math.min(startupAttempts - 1, 2));
            Constants.LOG.warn("Sync server unreachable, retrying in {}s: {}", delaySeconds, e.toString());
            noteOffline(mc, e);
            scheduleReconcile(mc, delaySeconds);
        }
    }

    /** Network-level failures mean "server outage"; HTTP 4xx/5xx replies do not. */
    private static boolean isConnectivityError(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketException
                    || t instanceof java.io.EOFException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private void noteOffline(Minecraft mc, Exception e) {
        if (!isConnectivityError(e) || stopping) {
            return;
        }
        if (!offline) {
            offline = true;
            Toasts.showLater(mc, "universalsettings.toast.offline");
        }
    }

    private void noteOnline(Minecraft mc) {
        if (offline) {
            offline = false;
            startupAttempts = 0;
            Toasts.showLater(mc, "universalsettings.toast.reconnected");
        }
    }

    /**
     * Schedules one re-run of the startup sync — the recovery path that ends an
     * outage. No-op when one is already waiting, so retry chains don't stack.
     */
    private void scheduleReconcile(Minecraft mc, long delaySeconds) {
        if (stopping || reconcileScheduled) {
            return;
        }
        reconcileScheduled = true;
        try {
            executor.schedule(() -> {
                reconcileScheduled = false;
                startupSync(mc);
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // Executor already shut down; the next launch syncs instead.
            reconcileScheduled = false;
        }
    }

    private void reportError(Minecraft mc, Exception e, Consumer<String> onError) {
        noteAuthFailed(mc);
        noteOffline(mc, e);
        if (isConnectivityError(e)) {
            scheduleReconcile(mc, 60);
        }
        mc.execute(() -> onError.accept(describeError(e)));
    }

    /**
     * Shows the auth-failure toast once per session. Returns whether the session
     * is in the failed-auth state, which latches until the next launch.
     */
    private boolean noteAuthFailed(Minecraft mc) {
        if (!api.auth().isAuthFailed()) {
            return false;
        }
        if (!authFailedToastShown) {
            authFailedToastShown = true;
            Toasts.showLater(mc, "universalsettings.toast.authFailed");
        }
        return true;
    }

    /** Mirrors every profile into the local cache so it keeps working during an outage. */
    private void refreshProfileCache(Minecraft mc, List<ProfileSummary> knownList) {
        try {
            List<ProfileSummary> list = knownList != null ? knownList : api.listProfiles(playerUuid(mc));
            List<ProfileCache.Entry> entries = new ArrayList<>(list.size());
            for (ProfileSummary summary : list) {
                api.fetchProfile(playerUuid(mc), summary.name(), gameVersion())
                        .ifPresent(data -> entries.add(ProfileCache.Entry.of(data, summary.isDefault())));
            }
            ProfileCache.get().replaceAll(entries);
        } catch (Exception e) {
            Constants.LOG.debug("Could not refresh the local profile cache: {}", e.toString());
        }
    }

    private void firstRun(Minecraft mc, ModConfig config) throws IOException, InterruptedException {
        Optional<ProfileData> remote = api.fetchProfile(playerUuid(mc), null, gameVersion());
        if (remote.isEmpty()) {
            // Nothing on the sync server yet: silently seed it with the local settings.
            // An auth failure throws before this, so firstRunDone stays unset and the
            // first-run flow retries next launch.
            doPush(mc, config, "default");
            config.activeProfile = "default";
            config.firstRunDone = true;
            config.save();
            Toasts.showLater(mc, "universalsettings.toast.uploaded");
            return;
        }
        ProfileCache.get().update(remote.get(), true);
        boolean localIsDefault = mc.submit(
                () -> DefaultOptionsDetector.isLocalVanillaDefault(mc, optionsFileExisted)).join();
        if (localIsDefault) {
            cacheProfileState(config, remote.get());
            applyOnRenderThread(mc, remote.get());
            config.activeProfile = remote.get().name();
            config.firstRunDone = true;
            recordSyncedState(mc, config, remote.get().updatedAt());
            config.save();
            Toasts.showLater(mc, "universalsettings.toast.synced");
        } else {
            // Local settings differ from vanilla defaults: let the player choose
            // once the title screen is up (see onEndTick).
            pendingPrompt = remote.get();
        }
    }

    private void pullAndApply(Minecraft mc, ModConfig config) throws IOException, InterruptedException {
        Optional<ProfileData> remote = api.fetchProfile(playerUuid(mc), config.activeProfile, gameVersion());
        if (remote.isEmpty()) {
            // Nothing on the sync server (e.g. all profiles were deleted): re-seed
            // from the current settings, like first run does.
            String name = config.activeProfile != null ? config.activeProfile : "default";
            doPush(mc, config, name);
            config.activeProfile = name;
            config.save();
            Toasts.showLater(mc, "universalsettings.toast.uploaded");
            return;
        }
        ProfileData profile = remote.get();
        if (config.activeProfile == null) {
            config.activeProfile = profile.name();
        }
        String previousSyncedHash = config.lastSyncedHash;
        cacheProfileState(config, profile);
        ProfileCache.get().update(profile, null);
        String remoteHash = OptionsFileCodec.syncHash(
                OptionsFileCodec.withoutForcedExclusions(profile.options()),
                config.syncServers ? profile.serversDat() : null);
        if (remoteHash.equals(currentHash(mc, config))) {
            recordSyncedState(mc, config, profile.updatedAt());
            config.save();
            return;
        }
        if (remoteHash.equals(previousSyncedHash)) {
            // The server still holds exactly what we last synced, so the difference
            // is local-only (e.g. edits made during an outage). Push instead of
            // reverting them with the stale remote copy.
            doPush(mc, config, config.activeProfile);
            return;
        }
        applyOnRenderThread(mc, profile);
        recordSyncedState(mc, config, profile.updatedAt());
        config.save();
        if (!remoteHash.equals(config.lastSyncedHash)) {
            // Local-only keys (a newer game version's settings, other mods' entries)
            // aren't in the remote profile yet; upload the merge.
            doPush(mc, config, config.activeProfile);
        }
    }

    private void push() {
        Minecraft mc = Minecraft.getInstance();
        ModConfig config = ModConfig.get();
        if (!config.enabled || !config.firstRunDone || config.activeProfile == null) {
            return;
        }
        try {
            if (currentHash(mc, config).equals(config.lastSyncedHash)) {
                return;
            }
            doPush(mc, config, config.activeProfile);
        } catch (Exception e) {
            if (noteAuthFailed(mc)) {
                // Auth failures latch for the session; retrying cannot succeed.
                return;
            }
            Constants.LOG.warn("Sync push failed, will retry: {}", e.toString());
            noteOffline(mc, e);
            pusher.scheduleRetry();
        }
    }

    /**
     * Uploads the local syncable options to {@code profileName}. The server merges
     * them per-key into the stored profile, so keys only other game versions know
     * about survive the push. The local cache and hash are set to the predicted
     * merge result; if another device pushed in the meantime the next pull reconciles.
     */
    private void doPush(Minecraft mc, ModConfig config, String profileName)
            throws IOException, InterruptedException {
        Map<String, String> syncable = OptionsFileCodec.filterSyncable(
                OptionsFileCodec.parse(mc.options.getFile().toPath()));
        // A push to a non-active profile (save-as flows) merges into content the
        // local cache doesn't track; fetch it as the merge base instead.
        LinkedHashMap<String, String> base;
        if (profileName.equals(config.activeProfile)) {
            base = OptionsFileCodec.withoutForcedExclusions(config.profileOptionsCache);
        } else {
            base = api.fetchProfile(playerUuid(mc), profileName, gameVersion())
                    .map(p -> OptionsFileCodec.withoutForcedExclusions(p.options()))
                    .orElseGet(LinkedHashMap::new);
        }
        byte[] serversDat = config.syncServers ? readServersDat(mc) : null;
        long updatedAt = api.putProfile(
                playerUuid(mc), profileName, syncable, serversDat, gameVersion());
        base.putAll(syncable);
        // Every caller makes profileName the active profile, so the cache tracks
        // the profile just written.
        config.profileOptionsCache = base;
        config.lastSyncedHash = OptionsFileCodec.syncHash(base, serversDat);
        config.lastSyncedAt = updatedAt;
        config.save();
        ProfileCache.get().update(new ProfileData(profileName, base, serversDat,
                gameVersion(), updatedAt, List.of()), null);
        noteOnline(mc);
    }

    /** Turns request failures into something readable on a GUI status line. */
    private static String describeError(Exception e) {
        Constants.LOG.warn("Profile operation failed", e);
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.http.HttpConnectTimeoutException
                    || t instanceof java.net.UnknownHostException) {
                return "Can't reach the sync server";
            }
            if (t instanceof java.net.http.HttpTimeoutException) {
                return "The sync server took too long to respond";
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private void applyOnRenderThread(Minecraft mc, ProfileData profile) {
        mc.submit(() -> {
            applying = true;
            try {
                OptionsApplier.apply(mc, profile);
            } catch (IOException e) {
                Constants.LOG.error("Failed to apply remote profile", e);
            } finally {
                applying = false;
            }
        }).join();
    }

    /**
     * Hash of what the active profile would contain after pushing the current disk
     * state. Using the cached profile content as the base keeps keys from other
     * game versions in the comparison even though vanilla drops them from disk.
     */
    private String currentHash(Minecraft mc, ModConfig config) throws IOException {
        LinkedHashMap<String, String> local = OptionsFileCodec.parse(mc.options.getFile().toPath());
        LinkedHashMap<String, String> union = OptionsFileCodec.withoutForcedExclusions(config.profileOptionsCache);
        union.putAll(OptionsFileCodec.filterSyncable(local));
        return OptionsFileCodec.syncHash(union,
                config.syncServers ? readServersDat(mc) : null);
    }

    private void recordSyncedState(Minecraft mc, ModConfig config, long updatedAt) {
        try {
            config.lastSyncedHash = currentHash(mc, config);
            config.lastSyncedAt = updatedAt;
        } catch (IOException e) {
            Constants.LOG.warn("Could not record synced state", e);
        }
    }

    private static byte[] readServersDat(Minecraft mc) throws IOException {
        Path file = mc.gameDirectory.toPath().resolve("servers.dat");
        return Files.exists(file) ? Files.readAllBytes(file) : null;
    }

    private static UUID playerUuid(Minecraft mc) {
        return mc.getUser().getProfileId();
    }

    private static String gameVersion() {
        // WorldVersion became a record with name() in the 1.21.9 version-info rework.
        //? if >=1.21.9 {
        return SharedConstants.getCurrentVersion().name();
        //?} else {
        /*return SharedConstants.getCurrentVersion().getName();
        *///?}
    }

    private static String deviceProfileName() {
        String name = "device";
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        name = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (name.isEmpty()) {
            name = "device";
        }
        return name.length() > 32 ? name.substring(0, 32) : name;
    }
}

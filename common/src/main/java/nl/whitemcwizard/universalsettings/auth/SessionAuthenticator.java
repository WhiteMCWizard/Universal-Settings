package nl.whitemcwizard.universalsettings.auth;

import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.platform.Services;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Silent authentication via the Mojang session-server handshake: ask the sync
 * server for a challenge, "join" that challenge id as if it were a multiplayer
 * server (using the launcher-provided access token), and the sync server verifies
 * the join with Mojang's hasJoined endpoint before issuing a short-lived JWT.
 *
 * <p>Every sync endpoint requires the JWT. If the handshake fails (offline mode,
 * expired launcher token), sync is disabled for the rest of the session.
 */
public class SessionAuthenticator {

    private final HttpClient http;
    private final Gson gson = new Gson();

    private String token;
    private long expiresAt;
    private boolean authFailed;

    public SessionAuthenticator(HttpClient http) {
        this.http = http;
    }

    /** Returns a valid JWT, handshaking if needed, or null once auth has failed. */
    public synchronized String getToken() {
        if (authFailed) {
            return null;
        }
        if (token != null && System.currentTimeMillis() < expiresAt - 60_000) {
            return token;
        }
        try {
            handshake();
            return token;
        } catch (Exception e) {
            Constants.LOG.warn("Session handshake failed, sync is disabled this session: {}", e.toString());
            authFailed = true;
            return null;
        }
    }

    /** Drops the cached token so the next getToken() re-authenticates. */
    public synchronized void invalidate() {
        token = null;
    }

    public synchronized boolean isAuthFailed() {
        return authFailed;
    }

    private void handshake() throws Exception {
        User user = Minecraft.getInstance().getUser();
        String uuid = user.getProfileId().toString().replace("-", "").toLowerCase();

        String challengeBody = post("/auth/challenge",
                gson.toJson(Map.of("uuid", uuid, "username", user.getName())));
        ChallengeResponse challenge = gson.fromJson(challengeBody, ChallengeResponse.class);

        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            // runClient has no real launcher token; pair with an AUTH_DISABLED dev server.
            Constants.LOG.info("Dev environment: skipping Mojang joinServer call");
        } else {
            // Blocking authlib call; only ever runs on the mod's sync executor.
            //? if >=1.21.9 {
            Minecraft.getInstance().services().sessionService()
                    .joinServer(user.getProfileId(), user.getAccessToken(), challenge.serverId);
            //?} else if >=1.20.2 {
            /*Minecraft.getInstance().getMinecraftSessionService()
                    .joinServer(user.getProfileId(), user.getAccessToken(), challenge.serverId);
            *///?} else {
            /*Minecraft.getInstance().getMinecraftSessionService()
                    .joinServer(user.getGameProfile(), user.getAccessToken(), challenge.serverId);
            *///?}
        }

        String verifyBody = post("/auth/verify",
                gson.toJson(Map.of("challengeId", challenge.challengeId)));
        VerifyResponse verify = gson.fromJson(verifyBody, VerifyResponse.class);
        this.token = verify.token;
        this.expiresAt = verify.expiresAt;
        Constants.LOG.info("Authenticated with sync server as {}", user.getName());
    }

    private String post(String path, String json) throws Exception {
        String base = ModConfig.get().serverUrl.replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("POST " + path + " returned " + response.statusCode());
        }
        return response.body();
    }

    private static class ChallengeResponse {
        String challengeId;
        String serverId;
    }

    private static class VerifyResponse {
        String token;
        long expiresAt;
    }
}

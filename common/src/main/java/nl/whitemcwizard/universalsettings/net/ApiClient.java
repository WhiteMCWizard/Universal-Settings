package nl.whitemcwizard.universalsettings.net;

import com.google.gson.Gson;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.auth.SessionAuthenticator;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.profile.AccountServers;
import nl.whitemcwizard.universalsettings.profile.ProfileData;
import nl.whitemcwizard.universalsettings.profile.ProfileSummary;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking HTTP client for the sync server; call from the sync executor, never
 * the render thread. Every request carries the JWT from the session handshake;
 * a failed handshake surfaces as IOException and disables sync this session.
 */
public class ApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson gson = new Gson();
    private final SessionAuthenticator auth = new SessionAuthenticator(http);

    public SessionAuthenticator auth() {
        return auth;
    }

    /**
     * Fetches a profile; {@code name == null} fetches the default. The game
     * version lets the server translate option keys to this version's dialect.
     */
    public Optional<ProfileData> fetchProfile(UUID uuid, String name, String gameVersion)
            throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles/"
                + (name == null ? "default" : encodeName(name))
                + "?gameVersion=" + URLEncoder.encode(gameVersion, StandardCharsets.UTF_8);
        HttpResponse<String> response = sendAuthed(token -> builder(path)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        expectOk(response, "GET " + path);
        ProfileDto dto = gson.fromJson(response.body(), ProfileDto.class);
        byte[] serversDat = dto.serversDat == null ? null : Base64.getDecoder().decode(dto.serversDat);
        return Optional.of(new ProfileData(dto.name, dto.options, serversDat, dto.gameVersion, dto.updatedAt,
                dto.excludedKeys == null ? List.of() : dto.excludedKeys));
    }

    public List<ProfileSummary> listProfiles(UUID uuid) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles";
        HttpResponse<String> response = sendAuthed(token -> builder(path)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        expectOk(response, "GET " + path);
        SummaryDto[] dtos = gson.fromJson(response.body(), SummaryDto[].class);
        List<ProfileSummary> result = new ArrayList<>(dtos.length);
        for (SummaryDto dto : dtos) {
            result.add(new ProfileSummary(dto.name, dto.updatedAt, dto.gameVersion, dto.isDefault));
        }
        return result;
    }

    /** Creates or updates a profile; returns the server-stamped updatedAt. */
    public long putProfile(UUID uuid, String name, Map<String, String> options,
                           byte[] serversDat, String gameVersion) throws IOException, InterruptedException {
        return putProfile(uuid, name, options, serversDat, gameVersion, null);
    }

    /** A null {@code excludedKeys} leaves the stored exclusion list untouched. */
    public long putProfile(UUID uuid, String name, Map<String, String> options, byte[] serversDat,
                           String gameVersion, List<String> excludedKeys)
            throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles/" + encodeName(name);
        PutRequest body = new PutRequest();
        body.options = options;
        body.serversDat = serversDat == null ? null : Base64.getEncoder().encodeToString(serversDat);
        body.gameVersion = gameVersion;
        body.excludedKeys = excludedKeys;
        String json = gson.toJson(body);

        HttpResponse<String> response = sendAuthed(token -> jsonBuilder(path)
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build());
        expectOk(response, "PUT " + path);
        return gson.fromJson(response.body(), PutResponse.class).updatedAt;
    }

    public void setDefaultProfile(UUID uuid, String name) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/default";
        String json = gson.toJson(Map.of("name", name));
        HttpResponse<String> response = sendAuthed(token -> jsonBuilder(path)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
        expectOk(response, "POST " + path);
    }

    public void renameProfile(UUID uuid, String name, String newName) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles/"
                + encodeName(name) + "/rename";
        String json = gson.toJson(Map.of("newName", newName));
        HttpResponse<String> response = sendAuthed(token -> jsonBuilder(path)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
        expectOk(response, "POST " + path);
    }

    /**
     * Fetches the account-level server list and its sync scope. Returns empty when
     * the server predates the account endpoint (HTTP 404), so a newer mod keeps
     * working against an older server instead of failing its whole sync.
     */
    public Optional<AccountServers> fetchAccountServers(UUID uuid) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/servers";
        HttpResponse<String> response = sendAuthed(token -> builder(path)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        expectOk(response, "GET " + path);
        ServersDto dto = gson.fromJson(response.body(), ServersDto.class);
        byte[] serversDat = dto.serversDat == null ? null : Base64.getDecoder().decode(dto.serversDat);
        // The wire format is lowercase ('account'/'profile'/'off'); ModConfig uses
        // the uppercase SERVERS_* constants.
        String scope = dto.scope == null ? null : dto.scope.toUpperCase(Locale.ROOT);
        return Optional.of(new AccountServers(scope, serversDat));
    }

    /**
     * Updates the account-level server list and/or scope. A null {@code serversDat}
     * leaves the stored list untouched (scope-only update). Returns false when the
     * server predates the account endpoint (HTTP 404).
     */
    public boolean putAccountServers(UUID uuid, String scope, byte[] serversDat)
            throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/servers";
        ServersDto body = new ServersDto();
        body.scope = scope == null ? null : scope.toLowerCase(Locale.ROOT);
        body.serversDat = serversDat == null ? null : Base64.getEncoder().encodeToString(serversDat);
        String json = gson.toJson(body);
        HttpResponse<String> response = sendAuthed(token -> jsonBuilder(path)
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build());
        if (response.statusCode() == 404) {
            return false;
        }
        expectOk(response, "PUT " + path);
        return true;
    }

    public List<String> fetchGlobalExclusions(UUID uuid) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/exclusions";
        HttpResponse<String> response = sendAuthed(token -> builder(path)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
        expectOk(response, "GET " + path);
        ExclusionsDto dto = gson.fromJson(response.body(), ExclusionsDto.class);
        return dto.excludedKeys == null ? List.of() : dto.excludedKeys;
    }

    public void putGlobalExclusions(UUID uuid, List<String> excludedKeys) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/exclusions";
        putExclusions(path, excludedKeys);
    }

    public void putProfileExclusions(UUID uuid, String name, List<String> excludedKeys)
            throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles/" + encodeName(name) + "/exclusions";
        putExclusions(path, excludedKeys);
    }

    private void putExclusions(String path, List<String> excludedKeys) throws IOException, InterruptedException {
        String json = gson.toJson(Map.of("excludedKeys", excludedKeys));
        HttpResponse<String> response = sendAuthed(token -> jsonBuilder(path)
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build());
        expectOk(response, "PUT " + path);
    }

    public void deleteProfile(UUID uuid, String name) throws IOException, InterruptedException {
        String path = "/players/" + undashed(uuid) + "/profiles/" + encodeName(name);
        HttpResponse<String> response = sendAuthed(token -> builder(path)
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build());
        expectOk(response, "DELETE " + path);
    }

    /**
     * Sends an authenticated request, re-authenticating once on 401 (token expiry).
     * Throws when no session token can be obtained.
     */
    private HttpResponse<String> sendAuthed(java.util.function.Function<String, HttpRequest> requestFactory)
            throws IOException, InterruptedException {
        String token = requireToken();
        HttpResponse<String> response = http.send(requestFactory.apply(token), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) {
            auth.invalidate();
            token = requireToken();
            response = http.send(requestFactory.apply(token), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private String requireToken() throws IOException {
        String token = auth.getToken();
        if (token == null) {
            throw new IOException("not authenticated with the sync server");
        }
        return token;
    }

    private HttpRequest.Builder builder(String path) {
        String base = ModConfig.get().serverUrl.replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10));
    }

    /** Only for requests with a body — Fastify rejects an empty application/json body. */
    private HttpRequest.Builder jsonBuilder(String path) {
        return builder(path).header("Content-Type", "application/json");
    }

    /** Throws on non-2xx, carrying the server's own error message when it sent one. */
    private static void expectOk(HttpResponse<String> response, String what) throws IOException {
        if (response.statusCode() / 100 == 2) {
            return;
        }
        String detail = null;
        try {
            ErrorDto error = new Gson().fromJson(response.body(), ErrorDto.class);
            if (error != null && error.error != null && !error.error.isBlank()) {
                detail = error.error;
            }
        } catch (Exception ignored) {
        }
        throw new IOException(detail != null ? detail : what + " returned " + response.statusCode());
    }

    private static class ErrorDto {
        String error;
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "").toLowerCase();
    }

    /**
     * Path-encodes a profile name. URLEncoder produces form encoding, where a
     * space becomes '+' — which servers do not decode in URL paths.
     */
    private static String encodeName(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static class SummaryDto {
        String name;
        long updatedAt;
        String gameVersion;
        boolean isDefault;
    }

    private static class ProfileDto {
        String name;
        Map<String, String> options;
        String serversDat;
        String gameVersion;
        long updatedAt;
        List<String> excludedKeys;
    }

    private static class PutRequest {
        Map<String, String> options;
        String serversDat;
        String gameVersion;
        List<String> excludedKeys;
    }

    private static class ExclusionsDto {
        List<String> excludedKeys;
    }

    private static class ServersDto {
        String scope;
        String serversDat;
    }

    private static class PutResponse {
        long updatedAt;
    }
}

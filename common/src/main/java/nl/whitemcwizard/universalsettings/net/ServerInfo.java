package nl.whitemcwizard.universalsettings.net;

/**
 * The sync server's self-reported identity from {@code GET /version}. A server
 * predating that endpoint is treated as {@code protocol == 1}.
 */
public record ServerInfo(String version, int protocol) {
}

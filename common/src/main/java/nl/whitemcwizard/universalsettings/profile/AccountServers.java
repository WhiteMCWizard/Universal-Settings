package nl.whitemcwizard.universalsettings.profile;

/**
 * The account-level multiplayer server list and its sync scope as stored on the
 * sync server. {@code scope} is one of ModConfig.SERVERS_ACCOUNT/PROFILE/OFF;
 * {@code serversDat} is null when the account has no shared list yet.
 */
public record AccountServers(String scope, byte[] serversDat) {
}

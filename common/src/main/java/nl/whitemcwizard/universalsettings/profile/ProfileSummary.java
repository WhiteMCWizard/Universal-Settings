package nl.whitemcwizard.universalsettings.profile;

/** Profile listing entry, without the settings payload. */
public record ProfileSummary(String name, long updatedAt, String gameVersion, boolean isDefault) {
}

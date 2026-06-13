package nl.whitemcwizard.universalsettings.profile;

import java.util.List;
import java.util.Map;

/** A full settings profile as stored on the sync server. */
public record ProfileData(String name, Map<String, String> options, byte[] serversDat,
                          String gameVersion, long updatedAt, List<String> excludedKeys) {
}

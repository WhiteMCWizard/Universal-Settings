package nl.whitemcwizard.universalsettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "universalsettings";
	public static final String MOD_NAME = "UniversalSettings";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

	/** The central sync server operated by the mod author; overridable via config. */
	public static final String DEFAULT_SERVER_URL = "https://universalsettings.whitemcwizard.nl";
}

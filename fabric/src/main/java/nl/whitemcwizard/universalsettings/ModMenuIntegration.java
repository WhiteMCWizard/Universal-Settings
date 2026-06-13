package nl.whitemcwizard.universalsettings;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import nl.whitemcwizard.universalsettings.ui.ProfilesScreen;

/** Only loaded when ModMenu is installed and queries the "modmenu" entrypoint. */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ProfilesScreen::new;
    }
}

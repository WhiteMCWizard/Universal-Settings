package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Screen accessors that moved off {@link Minecraft} onto its {@code gui} field in 26.2:
 * {@code setScreen} became {@code setScreenAndShow}, and the {@code screen} field is now
 * {@code gui.screen()}. Wrapped here so call sites stay version-agnostic.
 */
public final class Screens {

    private Screens() {
    }

    /** Opens and initializes the given screen (the old {@code Minecraft#setScreen}). */
    public static void set(Minecraft mc, Screen screen) {
        //? if >=26.2 {
        mc.setScreenAndShow(screen);
        //?} else {
        /*mc.setScreen(screen);
        *///?}
    }

    /** The screen currently displayed, or {@code null} if none. */
    public static Screen current(Minecraft mc) {
        //? if >=26.2 {
        return mc.gui.screen();
        //?} else {
        /*return mc.screen;
        *///?}
    }
}

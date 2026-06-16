package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public final class Toasts {

    // Toast ids were a fixed enum before 1.20.2; reuse a low-traffic vanilla slot there.
    //? if >=1.20.2 {
    private static final SystemToast.SystemToastId SYNC_TOAST = new SystemToast.SystemToastId();
    //?} else {
    /*private static final SystemToast.SystemToastIds SYNC_TOAST = SystemToast.SystemToastIds.PERIODIC_NOTIFICATION;
    *///?}

    private Toasts() {
    }

    /** Shows a toast; must be called on the render thread. */
    public static void show(String messageKey) {
        Minecraft mc = Minecraft.getInstance();
        //? if >=26.2 {
        SystemToast.add(mc.gui.toastManager(), SYNC_TOAST,
        //?} else if >=1.21.2 {
        /*SystemToast.add(mc.getToastManager(), SYNC_TOAST,
        *///?} else {
        /*SystemToast.add(mc.getToasts(), SYNC_TOAST,
        *///?}
                Component.translatable("universalsettings.toast.title"),
                Component.translatable(messageKey));
    }

    /** Shows a toast from any thread by hopping to the render thread. */
    public static void showLater(Minecraft mc, String messageKey) {
        mc.execute(() -> show(messageKey));
    }

    /**
     * Whether the language pack has loaded, so toast text resolves to real strings.
     * 26.2's {@link SystemToast} bakes its lines when the toast is constructed, so a
     * toast built before the initial resource reload finishes would freeze in the raw
     * translation key; callers gate on this. Older versions resolve lazily and are
     * unaffected, but the check is cheap and harmless there.
     */
    public static boolean languageLoaded() {
        String key = "universalsettings.toast.title";
        return !Component.translatable(key).getString().equals(key);
    }
}

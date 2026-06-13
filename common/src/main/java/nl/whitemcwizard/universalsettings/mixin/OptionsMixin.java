package nl.whitemcwizard.universalsettings.mixin;

import net.minecraft.client.Options;
import nl.whitemcwizard.universalsettings.sync.SyncManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla calls Options.save() whenever an options or keybind screen closes,
 * which makes it the single change-detection point for all synced settings.
 */
@Mixin(Options.class)
public class OptionsMixin {

    @Inject(method = "save", at = @At("TAIL"))
    private void universalsettings$onSave(CallbackInfo ci) {
        SyncManager.get().onOptionsSaved();
    }
}

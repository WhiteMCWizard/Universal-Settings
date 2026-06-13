package nl.whitemcwizard.universalsettings.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21 {
import net.minecraft.client.gui.screens.options.OptionsScreen;
//?} else {
/*import net.minecraft.client.gui.screens.OptionsScreen;
*///?}
import net.minecraft.network.chat.Component;
import nl.whitemcwizard.universalsettings.ui.ProfilesScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a small "Profiles" button to the top-right of the vanilla Options screen. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    @Unique
    private Button universalsettings$profilesButton;

    protected OptionsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void universalsettings$addProfilesButton(CallbackInfo ci) {
        universalsettings$profilesButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("universalsettings.profiles.button"),
                        button -> this.minecraft.setScreen(new ProfilesScreen(this)))
                .bounds(this.width - 65, 6, 60, 20)
                .build());
    }

    // OptionsScreen only overrides repositionElements from 1.20.3 onwards; before
    // that a window resize rebuilds the widgets, re-running init.
    //? if >=1.20.3 {
    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void universalsettings$repositionProfilesButton(CallbackInfo ci) {
        if (universalsettings$profilesButton != null) {
            universalsettings$profilesButton.setPosition(this.width - 65, 6);
        }
    }
    //?}
}

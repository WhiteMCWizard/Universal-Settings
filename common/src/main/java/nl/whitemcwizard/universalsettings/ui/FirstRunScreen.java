package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
//? if >=1.20.3 {
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
//?} else {
/*import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
*///?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nl.whitemcwizard.universalsettings.profile.ProfileData;
import nl.whitemcwizard.universalsettings.sync.SyncManager;

/**
 * One-time choice shown over the title screen when the player already has a
 * synced profile but this install's local settings are not vanilla defaults.
 */
public class FirstRunScreen extends Screen {

    private final Screen parent;
    private final ProfileData remoteProfile;
    //? if >=1.20.3 {
    private final LinearLayout layout = LinearLayout.vertical().spacing(8);
    //?} else {
    /*private final GridLayout layout = new GridLayout().rowSpacing(8);
    *///?}

    public FirstRunScreen(Screen parent, ProfileData remoteProfile) {
        super(Component.translatable("universalsettings.firstrun.title"));
        this.parent = parent;
        this.remoteProfile = remoteProfile;
    }

    @Override
    protected void init() {
        super.init();
        this.layout.defaultCellSetting().alignHorizontallyCenter();
        //? if >=1.20.3 {
        var rows = this.layout;
        //?} else {
        /*GridLayout.RowHelper rows = this.layout.createRowHelper(1);
        *///?}
        rows.addChild(new StringWidget(this.title, this.font));
        rows.addChild(new MultiLineTextWidget(
                Component.translatable("universalsettings.firstrun.message"), this.font)
                .setMaxWidth(this.width - 50)
                .setCentered(true));
        rows.addChild(Button.builder(
                Component.translatable("universalsettings.firstrun.useSynced"),
                button -> {
                    SyncManager.get().acceptRemoteProfile(remoteProfile);
                    onClose();
                }).width(250).build());
        rows.addChild(Button.builder(
                Component.translatable("universalsettings.firstrun.keep"),
                button -> Screens.set(this.minecraft,new NameProfileScreen(this,
                        Component.translatable("universalsettings.firstrun.keepTitle"),
                        SyncManager.deviceProfileName(),
                        name -> {
                            SyncManager.get().keepLocalAsNewProfile(name);
                            onClose();
                        }))).width(250).build());
        rows.addChild(Button.builder(
                Component.translatable("universalsettings.firstrun.later"),
                button -> onClose()).width(250).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());
    }

    @Override
    public void onClose() {
        Screens.set(this.minecraft,parent);
    }
}

package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
//? if >=1.20.3 {
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
//?} else {
/*import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
*///?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Prompt for a profile name, used by the new/rename/duplicate actions. */
public class NameProfileScreen extends Screen {

    static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9 _-]{1,32}");

    private final Screen parent;
    private final String initialValue;
    private final Consumer<String> onConfirm;
    private EditBox nameBox;
    private StringWidget errorWidget;
    private Button doneButton;

    public NameProfileScreen(Screen parent, Component title, String initialValue, Consumer<String> onConfirm) {
        super(title);
        this.parent = parent;
        this.initialValue = initialValue;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        super.init();
        //? if >=1.20.3 {
        LinearLayout layout = LinearLayout.vertical().spacing(8);
        //?} else {
        /*GridLayout grid = new GridLayout().rowSpacing(8);
        GridLayout.RowHelper layout = grid.createRowHelper(1);
        *///?}
        layout.defaultCellSetting().alignHorizontallyCenter();
        layout.addChild(new StringWidget(this.title, this.font));
        String previous = nameBox != null ? nameBox.getValue() : initialValue;
        //? if >=1.20.3 {
        nameBox = layout.addChild(new EditBox(this.font, 200, 20,
                Component.translatable("universalsettings.profiles.nameHint")));
        //?} else {
        /*nameBox = layout.addChild(new EditBox(this.font, 0, 0, 200, 20,
                Component.translatable("universalsettings.profiles.nameHint")));
        *///?}
        nameBox.setMaxLength(32);
        errorWidget = layout.addChild(new StringWidget(280, 9, Component.empty(), this.font));
        //? if >=1.20.3 {
        LinearLayout buttons = layout.addChild(LinearLayout.horizontal().spacing(8));
        //?} else {
        /*GridLayout.RowHelper buttons = layout.addChild(new GridLayout().columnSpacing(8)).createRowHelper(2);
        *///?}
        doneButton = buttons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> confirm()).width(96).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(96).build());
        //? if >=1.20.3 {
        layout.visitWidgets(this::addRenderableWidget);
        layout.arrangeElements();
        FrameLayout.centerInRectangle(layout, this.getRectangle());
        //?} else {
        /*grid.visitWidgets(this::addRenderableWidget);
        grid.arrangeElements();
        FrameLayout.centerInRectangle(grid, this.getRectangle());
        *///?}
        nameBox.setResponder(this::validate);
        nameBox.setValue(previous);
        validate(previous);
        this.setInitialFocus(nameBox);
    }

    private void validate(String value) {
        String name = value.trim();
        boolean valid = VALID_NAME.matcher(name).matches();
        doneButton.active = valid;
        errorWidget.setMessage(!valid && !name.isEmpty()
                ? Component.translatable("universalsettings.profiles.invalidName")
                        .withStyle(ChatFormatting.RED)
                : Component.empty());
    }

    private void confirm() {
        String name = nameBox.getValue().trim();
        if (!VALID_NAME.matcher(name).matches()) {
            return;
        }
        this.minecraft.setScreen(parent);
        onConfirm.accept(name);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

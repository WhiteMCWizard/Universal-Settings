package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
//? if >=1.20.3 {
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
//?} else {
/*import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
*///?}
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.profile.ProfileSummary;
import nl.whitemcwizard.universalsettings.sync.SyncManager;

import java.util.List;

/**
 * Profile management, laid out like vanilla selection screens. Reached from the
 * vanilla Options screen or the mod-list config button.
 */
public class ProfilesScreen extends Screen {

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 85);

    private ProfileList list;
    private StringWidget statusWidget;
    private Button useButton;
    private Button defaultButton;
    private Button renameButton;
    private Button duplicateButton;
    private Button deleteButton;

    private List<ProfileSummary> profiles;
    private String selectedName;
    private Component status;
    private boolean loadRequested;

    public ProfilesScreen(Screen parent) {
        super(Component.translatable("universalsettings.profiles.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addToHeader(new StringWidget(this.title, this.font));

        //? if >=1.20.3 {
        list = layout.addToContents(new ProfileList(this.minecraft));
        //?} else {
        /*list = addRenderableWidget(new ProfileList(this.minecraft));
        *///?}

        //? if >=1.20.3 {
        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        statusWidget = footer.addChild(new StringWidget(320, 9, Component.empty(), this.font));
        LinearLayout row1 = footer.addChild(LinearLayout.horizontal().spacing(4));
        //?} else {
        /*GridLayout footerGrid = layout.addToFooter(new GridLayout().rowSpacing(4));
        footerGrid.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper footer = footerGrid.createRowHelper(1);
        statusWidget = footer.addChild(new StringWidget(320, 9, Component.empty(), this.font));
        GridLayout.RowHelper row1 = footer.addChild(new GridLayout().columnSpacing(4)).createRowHelper(4);
        *///?}
        useButton = row1.addChild(button("universalsettings.profiles.use", 76, b -> use()));
        defaultButton = row1.addChild(button("universalsettings.profiles.setDefault", 76, b -> setDefault()));
        renameButton = row1.addChild(button("universalsettings.profiles.rename", 76, b -> rename()));
        duplicateButton = row1.addChild(button("universalsettings.profiles.duplicate", 76, b -> duplicate()));
        //? if >=1.20.3 {
        LinearLayout row2 = footer.addChild(LinearLayout.horizontal().spacing(4));
        //?} else {
        /*GridLayout.RowHelper row2 = footer.addChild(new GridLayout().columnSpacing(4)).createRowHelper(2);
        *///?}
        row2.addChild(button("universalsettings.profiles.new", 156, b -> newProfile()));
        deleteButton = row2.addChild(button("universalsettings.profiles.delete", 156, b -> confirmDelete()));
        //? if >=1.20.3 {
        LinearLayout row3 = footer.addChild(LinearLayout.horizontal().spacing(4));
        //?} else {
        /*GridLayout.RowHelper row3 = footer.addChild(new GridLayout().columnSpacing(4)).createRowHelper(2);
        *///?}
        row3.addChild(button("universalsettings.ignored.button", 156,
                b -> this.minecraft.setScreen(new IgnoredSettingsScreen(this))));
        row3.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(156).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();

        if (profiles != null) {
            list.setProfiles(profiles);
        } else if (!loadRequested) {
            loadRequested = true;
            refresh();
        }
        applyStatus();
        updateButtons();
    }

    private Button button(String key, int width, Button.OnPress action) {
        return Button.builder(Component.translatable(key), action).width(width).build();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        //? if >=1.20.3 {
        list.updateSize(this.width, layout);
        //?} else {
        /*list.updateSize(this.width, this.height,
                layout.getHeaderHeight(), this.height - layout.getFooterHeight());
        *///?}
    }

    private ProfileSummary selectedSummary() {
        if (selectedName == null || profiles == null) {
            return null;
        }
        return profiles.stream().filter(p -> p.name().equals(selectedName)).findFirst().orElse(null);
    }

    private void updateButtons() {
        if (useButton == null) {
            return;
        }
        ProfileSummary selected = selectedSummary();
        boolean hasSelection = selected != null;
        useButton.active = hasSelection && !selected.name().equals(ModConfig.get().activeProfile);
        defaultButton.active = hasSelection && !selected.isDefault();
        renameButton.active = hasSelection;
        duplicateButton.active = hasSelection;
        deleteButton.active = hasSelection;
    }

    private void setStatus(Component message) {
        status = message;
        applyStatus();
    }

    private void applyStatus() {
        if (statusWidget != null) {
            statusWidget.setMessage(status == null ? Component.empty() : status);
        }
    }

    private void refresh() {
        if (profiles == null) {
            setStatus(Component.translatable("universalsettings.profiles.loading")
                    .withStyle(ChatFormatting.GRAY));
        }
        SyncManager.get().fetchProfileList(
                result -> ifCurrent(() -> {
                    profiles = result;
                    if (selectedSummary() == null) {
                        selectedName = null;
                    }
                    list.setProfiles(result);
                    setStatus(result.isEmpty()
                            ? Component.translatable("universalsettings.profiles.empty")
                                    .withStyle(ChatFormatting.GRAY)
                            : null);
                    updateButtons();
                }),
                this::showError);
    }

    private void use() {
        setStatus(null);
        SyncManager.get().switchToProfile(selectedName, () -> ifCurrent(() -> {
            Toasts.show("universalsettings.toast.synced");
            refresh();
        }), this::showError);
    }

    private void setDefault() {
        setStatus(null);
        SyncManager.get().setDefaultProfile(selectedName, () -> ifCurrent(this::refresh), this::showError);
    }

    private void rename() {
        String name = selectedName;
        this.minecraft.setScreen(new NameProfileScreen(this,
                Component.translatable("universalsettings.profiles.renameTitle", name), name,
                newName -> {
                    setStatus(null);
                    selectedName = newName;
                    SyncManager.get().renameProfile(name, newName, () -> ifCurrent(this::refresh), this::showError);
                }));
    }

    private void duplicate() {
        String name = selectedName;
        this.minecraft.setScreen(new NameProfileScreen(this,
                Component.translatable("universalsettings.profiles.duplicateTitle", name), name + " 2",
                newName -> {
                    setStatus(null);
                    SyncManager.get().duplicateProfile(name, newName, () -> ifCurrent(this::refresh), this::showError);
                }));
    }

    private void newProfile() {
        this.minecraft.setScreen(new NameProfileScreen(this,
                Component.translatable("universalsettings.profiles.newTitle"), "",
                name -> {
                    setStatus(null);
                    SyncManager.get().createProfileFromDefaults(name, () -> ifCurrent(this::refresh), this::showError);
                }));
    }

    private void confirmDelete() {
        ProfileSummary selected = selectedSummary();
        if (selected == null) {
            return;
        }
        String name = selected.name();
        boolean wasDefault = selected.isDefault();
        Component message = Component.translatable("universalsettings.profiles.deleteConfirm", name);
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            this.minecraft.setScreen(this);
            if (confirmed) {
                SyncManager.get().deleteProfile(name, wasDefault,
                        () -> ifCurrent(this::refresh), this::showError);
            }
        }, this.title, message));
    }

    private void showError(String message) {
        ifCurrent(() -> setStatus(Component.literal(message).withStyle(ChatFormatting.RED)));
    }

    /** Async callbacks may arrive after the player closed this screen. */
    private void ifCurrent(Runnable action) {
        if (this.minecraft != null && this.minecraft.screen == this) {
            action.run();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private class ProfileList extends ObjectSelectionList<ProfileList.Entry> {

        ProfileList(Minecraft minecraft) {
            // Pre-1.20.3 list constructors take top/bottom edges instead of height/top.
            //? if >=1.20.3 {
            super(minecraft, ProfilesScreen.this.width,
                    ProfilesScreen.this.height - 33 - 61, 33, 18);
            //?} else {
            /*super(minecraft, ProfilesScreen.this.width, ProfilesScreen.this.height,
                    33, ProfilesScreen.this.height - 85, 18);
            *///?}
        }

        void setProfiles(List<ProfileSummary> profiles) {
            replaceEntries(profiles.stream().map(Entry::new).toList());
            // Re-clamp the scroll position after the content change.
            //? if >=1.21.2 {
            refreshScrollAmount();
            //?} else if >=1.20.3 {
            /*clampScrollAmount();
            *///?} else {
            /*setScrollAmount(getScrollAmount());
            *///?}
            for (Entry entry : children()) {
                if (entry.profile.name().equals(selectedName)) {
                    setSelected(entry);
                }
            }
        }

        @Override
        public void setSelected(Entry entry) {
            super.setSelected(entry);
            if (entry != null) {
                selectedName = entry.profile.name();
            }
            updateButtons();
        }

        private class Entry extends ObjectSelectionList.Entry<Entry> {

            private final ProfileSummary profile;
            private final Component label;

            Entry(ProfileSummary profile) {
                this.profile = profile;
                Component tags = Component.empty()
                        .append(profile.isDefault()
                                ? Component.translatable("universalsettings.profiles.defaultTag")
                                        .withStyle(ChatFormatting.GOLD)
                                : Component.empty())
                        .append(profile.name().equals(ModConfig.get().activeProfile)
                                ? Component.translatable("universalsettings.profiles.activeTag")
                                        .withStyle(ChatFormatting.GREEN)
                                : Component.empty());
                this.label = Component.literal(profile.name()).append(tags);
            }

            //? if >=26.1 {
            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float a) {
                graphics.centeredText(ProfilesScreen.this.font, label,
                        ProfileList.this.width / 2, this.getContentYMiddle() - 9 / 2, -1);
            }
            //?} else if >=1.21.9 {
            /*@Override
            public void renderContent(GuiGraphics graphics, int mouseX, int mouseY,
                                      boolean hovered, float a) {
                graphics.drawCenteredString(ProfilesScreen.this.font, label,
                        ProfileList.this.width / 2, this.getContentYMiddle() - 9 / 2, -1);
            }
            *///?} else {
            /*@Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth,
                               int rowHeight, int mouseX, int mouseY, boolean hovered, float a) {
                graphics.drawCenteredString(ProfilesScreen.this.font, label,
                        ProfileList.this.width / 2, top + rowHeight / 2 - 9 / 2, -1);
            }
            *///?}

            //? if >=1.21.9 {
            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
                ProfileList.this.setSelected(this);
                return super.mouseClicked(event, doubleClick);
            }
            //?} else {
            /*@Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                ProfileList.this.setSelected(this);
                return super.mouseClicked(mouseX, mouseY, button);
            }
            *///?}

            @Override
            public Component getNarration() {
                return Component.translatable("narrator.select", label);
            }
        }
    }
}

package nl.whitemcwizard.universalsettings.ui;

import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
//? if >=1.20.3 {
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
//?} else {
/*import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
*///?}
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import nl.whitemcwizard.universalsettings.Constants;
import nl.whitemcwizard.universalsettings.config.ModConfig;
import nl.whitemcwizard.universalsettings.options.OptionsFileCodec;
import nl.whitemcwizard.universalsettings.sync.SyncManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lets the player pick which settings are ignored by sync, and at what scope:
 * this instance (local config), the active profile, or account-wide (both stored
 * on the server). Changes are saved when the screen closes.
 */
public class IgnoredSettingsScreen extends Screen {

    private enum Scope {
        SYNCED("universalsettings.ignored.scope.synced", 54),
        INSTANCE("universalsettings.ignored.scope.instance", 56),
        PROFILE("universalsettings.ignored.scope.profile", 48),
        EVERYWHERE("universalsettings.ignored.scope.everywhere", 34);

        final Component label;
        final Component tooltip;
        final int width;

        Scope(String key, int width) {
            this.label = Component.translatable(key);
            this.tooltip = Component.translatable(key + ".tooltip");
            this.width = width;
        }
    }

    // Server-list sync scope, cycled in the footer button.
    private static final List<String> SERVERS_MODES = List.of(
            ModConfig.SERVERS_ACCOUNT, ModConfig.SERVERS_PROFILE, ModConfig.SERVERS_OFF);

    private static Component serversModeValueLabel(String mode) {
        return Component.translatable(
                "universalsettings.ignored.serversMode." + mode.toLowerCase(Locale.ROOT));
    }

    /** "Server list: <mode>" via the vanilla "%s: %s" key, stable across versions. */
    private static Component serversModeMessage(Component label, String mode) {
        return Component.translatable("options.generic_value", label, serversModeValueLabel(mode));
    }

    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 45, 61);

    // Working copies; persisted via SyncManager when the screen closes.
    private final Set<String> instanceSet;
    private final Set<String> profileSet;
    private final Set<String> globalSet;
    private boolean profileDirty;
    private boolean globalDirty;

    private final List<String> allKeys;
    private SettingsList list;
    private EditBox search;

    public IgnoredSettingsScreen(Screen parent) {
        super(Component.translatable("universalsettings.ignored.title"));
        this.parent = parent;
        ModConfig config = ModConfig.get();
        this.instanceSet = new LinkedHashSet<>(config.excludedKeys);
        this.profileSet = new LinkedHashSet<>(config.profileExclusions);
        this.globalSet = new LinkedHashSet<>(config.globalExclusions);
        this.allKeys = collectKeys();
    }

    /** Every known settings key: the local options file plus everything already excluded. */
    private List<String> collectKeys() {
        Set<String> keys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            keys.addAll(OptionsFileCodec.parse(Minecraft.getInstance().options.getFile().toPath()).keySet());
        } catch (Exception e) {
            Constants.LOG.warn("Could not read options.txt for the ignored-settings list", e);
        }
        keys.addAll(instanceSet);
        keys.addAll(profileSet);
        keys.addAll(globalSet);
        keys.removeIf(ModConfig::isForceExcluded); // not the player's call
        return new ArrayList<>(keys);
    }

    @Override
    protected void init() {
        //? if >=1.20.3 {
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
        //?} else {
        /*GridLayout headerGrid = layout.addToHeader(new GridLayout().rowSpacing(4));
        headerGrid.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper header = headerGrid.createRowHelper(1);
        *///?}
        //? if >=1.20.3 {
        header.defaultCellSetting().alignHorizontallyCenter();
        //?}
        header.addChild(new StringWidget(this.title, this.font));
        String previousQuery = search != null ? search.getValue() : "";
        //? if >=1.20.3 {
        search = header.addChild(new EditBox(this.font, 220, 15,
                Component.translatable("universalsettings.ignored.search")));
        //?} else {
        /*search = header.addChild(new EditBox(this.font, 0, 0, 220, 15,
                Component.translatable("universalsettings.ignored.search")));
        *///?}
        search.setValue(previousQuery);
        search.setResponder(query -> {
            if (list != null) {
                list.filter(query);
            }
        });

        //? if >=1.20.3 {
        list = layout.addToContents(new SettingsList(this.minecraft));
        //?} else {
        /*list = addRenderableWidget(new SettingsList(this.minecraft));
        *///?}
        list.filter(previousQuery);

        //? if >=1.20.3 {
        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        //?} else {
        /*GridLayout footerGrid = layout.addToFooter(new GridLayout().rowSpacing(4));
        footerGrid.defaultCellSetting().alignHorizontallyCenter();
        GridLayout.RowHelper footer = footerGrid.createRowHelper(1);
        *///?}
        ModConfig config = ModConfig.get();
        // The CycleButton builder overloads keep shifting between versions: 26.1 takes
        // the initial value in builder(), older versions set it with withInitialValue(),
        // and the 1.21.9-1.21.11 window is unstable enough that it gets a plain Button.
        Component serversModeLabel = Component.translatable("universalsettings.ignored.serversMode");
        String initialMode = SERVERS_MODES.contains(config.serversMode)
                ? config.serversMode : ModConfig.SERVERS_ACCOUNT;
        //? if >=26.1 {
        footer.addChild(CycleButton.builder(IgnoredSettingsScreen::serversModeValueLabel, initialMode)
                .withValues(SERVERS_MODES)
                .create(serversModeLabel,
                        (button, value) -> SyncManager.get().setServersMode(value)));
        //?} else if >=1.21.9 {
        /*int[] modeIdx = { Math.max(0, SERVERS_MODES.indexOf(initialMode)) };
        footer.addChild(Button.builder(serversModeMessage(serversModeLabel, SERVERS_MODES.get(modeIdx[0])),
                button -> {
                    modeIdx[0] = (modeIdx[0] + 1) % SERVERS_MODES.size();
                    String value = SERVERS_MODES.get(modeIdx[0]);
                    SyncManager.get().setServersMode(value);
                    button.setMessage(serversModeMessage(serversModeLabel, value));
                }).width(200).build());
        *///?} else if >=1.20.3 {
        /*footer.addChild(CycleButton.<String>builder(IgnoredSettingsScreen::serversModeValueLabel)
                .withValues(SERVERS_MODES)
                .withInitialValue(initialMode)
                .create(serversModeLabel,
                        (button, value) -> SyncManager.get().setServersMode(value)));
        *///?} else {
        /*footer.addChild(CycleButton.<String>builder(IgnoredSettingsScreen::serversModeValueLabel)
                .withValues(SERVERS_MODES)
                .withInitialValue(initialMode)
                .create(0, 0, 200, 20, serversModeLabel,
                        (button, value) -> SyncManager.get().setServersMode(value)));
        *///?}
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        this.setInitialFocus(search);
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

    private Scope scopeOf(String key) {
        if (globalSet.contains(key)) {
            return Scope.EVERYWHERE;
        }
        if (profileSet.contains(key)) {
            return Scope.PROFILE;
        }
        if (instanceSet.contains(key)) {
            return Scope.INSTANCE;
        }
        return Scope.SYNCED;
    }

    private void setScope(String key, Scope scope) {
        if (profileSet.remove(key)) {
            profileDirty = true;
        }
        if (globalSet.remove(key)) {
            globalDirty = true;
        }
        instanceSet.remove(key);
        switch (scope) {
            case INSTANCE -> instanceSet.add(key);
            case PROFILE -> {
                profileSet.add(key);
                profileDirty = true;
            }
            case EVERYWHERE -> {
                globalSet.add(key);
                globalDirty = true;
            }
            case SYNCED -> {
            }
        }
    }

    @Override
    public void onClose() {
        SyncManager.get().applyExclusionChanges(
                new ArrayList<>(instanceSet),
                new ArrayList<>(profileSet), profileDirty,
                new ArrayList<>(globalSet), globalDirty,
                () -> {
                }, message -> Toasts.show("universalsettings.toast.exclusionsFailed"));
        this.minecraft.setScreen(parent);
    }

    private class SettingsList extends ContainerObjectSelectionList<SettingsList.Entry> {

        SettingsList(Minecraft minecraft) {
            // Pre-1.20.3 list constructors take top/bottom edges instead of height/top.
            //? if >=1.20.3 {
            super(minecraft, IgnoredSettingsScreen.this.width,
                    IgnoredSettingsScreen.this.height - 45 - 61, 45, 24);
            //?} else {
            /*super(minecraft, IgnoredSettingsScreen.this.width, IgnoredSettingsScreen.this.height,
                    45, IgnoredSettingsScreen.this.height - 61, 24);
            *///?}
        }

        @Override
        public int getRowWidth() {
            return 340;
        }

        void filter(String query) {
            String needle = query.trim().toLowerCase(Locale.ROOT);
            List<Entry> entries = allKeys.stream()
                    .filter(key -> needle.isEmpty() || key.toLowerCase(Locale.ROOT).contains(needle))
                    .map(Entry::new)
                    .toList();
            replaceEntries(entries);
            // Re-clamp the scroll position after the content change.
            //? if >=1.21.2 {
            refreshScrollAmount();
            //?} else if >=1.20.3 {
            /*clampScrollAmount();
            *///?} else {
            /*setScrollAmount(getScrollAmount());
            *///?}
        }

        private class Entry extends ContainerObjectSelectionList.Entry<Entry> {

            private final String key;
            private final Map<Scope, Button> scopeButtons = new LinkedHashMap<>();

            Entry(String key) {
                this.key = key;
                List<Scope> scopes = ModConfig.get().activeProfile != null
                        ? List.of(Scope.SYNCED, Scope.INSTANCE, Scope.PROFILE, Scope.EVERYWHERE)
                        : List.of(Scope.SYNCED, Scope.INSTANCE, Scope.EVERYWHERE);
                for (Scope scope : scopes) {
                    Button button = Button.builder(scope.label, b -> select(scope))
                            .width(scope.width).build();
                    button.setTooltip(Tooltip.create(scope.tooltip));
                    scopeButtons.put(scope, button);
                }
                updatePressed();
            }

            private void select(Scope scope) {
                setScope(key, scope);
                updatePressed();
            }

            /** The current scope's button is disabled, which reads as "pressed". */
            private void updatePressed() {
                Scope current = scopeOf(key);
                scopeButtons.forEach((scope, button) -> button.active = scope != current);
            }

            //? if >=26.1 {
            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float a) {
                int x = SettingsList.this.scrollBarX() - 10;
                List<Button> ordered = new ArrayList<>(scopeButtons.values());
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    Button button = ordered.get(i);
                    x -= button.getWidth();
                    button.setPosition(x, this.getContentY());
                    button.extractRenderState(graphics, mouseX, mouseY, a);
                    x -= 2;
                }
                graphics.text(IgnoredSettingsScreen.this.font, Component.literal(key),
                        this.getContentX(), this.getContentYMiddle() - 9 / 2, -1);
            }
            //?} else if >=1.21.9 {
            /*@Override
            public void renderContent(GuiGraphics graphics, int mouseX, int mouseY,
                                      boolean hovered, float a) {
                int x = SettingsList.this.scrollBarX() - 10;
                List<Button> ordered = new ArrayList<>(scopeButtons.values());
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    Button button = ordered.get(i);
                    x -= button.getWidth();
                    button.setPosition(x, this.getContentY());
                    button.render(graphics, mouseX, mouseY, a);
                    x -= 2;
                }
                graphics.drawString(IgnoredSettingsScreen.this.font, Component.literal(key),
                        this.getContentX(), this.getContentYMiddle() - 9 / 2, -1);
            }
            *///?} else {
            /*@Override
            public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth,
                               int rowHeight, int mouseX, int mouseY, boolean hovered, float a) {
                int x = SettingsList.this.getScrollbarPosition() - 10;
                List<Button> ordered = new ArrayList<>(scopeButtons.values());
                for (int i = ordered.size() - 1; i >= 0; i--) {
                    Button button = ordered.get(i);
                    x -= button.getWidth();
                    button.setPosition(x, top);
                    button.render(graphics, mouseX, mouseY, a);
                    x -= 2;
                }
                graphics.drawString(IgnoredSettingsScreen.this.font, Component.literal(key),
                        left, top + rowHeight / 2 - 9 / 2, -1);
            }
            *///?}

            @Override
            public List<? extends GuiEventListener> children() {
                return List.copyOf(scopeButtons.values());
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.copyOf(scopeButtons.values());
            }
        }
    }
}

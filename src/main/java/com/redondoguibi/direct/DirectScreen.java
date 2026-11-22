package com.redondoguibi.direct;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

/** Main player-facing entry point, shared by Mods, the key binding and /direct menu. */
public final class DirectScreen extends Screen {
    private static final String[] PAGES = {"overview", "memory", "textures", "advanced"};
    private final Screen parent;
    private DirectSettings draft;
    private int page;
    private Component error = Component.empty();
    private int left;
    private int panelWidth;

    public DirectScreen(Screen parent) {
        super(Component.literal("Direct"));
        this.parent = parent;
    }

    static Component text(String key, Object... args) { return Component.translatable("direct.ui." + key, args); }

    @Override
    protected void init() {
        if (draft == null) draft = new DirectSettings();
        panelWidth = Math.min(420, width - 20);
        left = (width - panelWidth) / 2;
        int tabWidth = panelWidth / PAGES.length;
        for (int i = 0; i < PAGES.length; i++) {
            final int next = i;
            var tab = button(text(PAGES[i]), left + i * tabWidth, 28, tabWidth - 3, () -> { page = next; error = Component.empty(); rebuildWidgets(); });
            tab.active = i != page;
        }
        int half = (panelWidth - 6) / 2;
        int right = left + half + 6;
        if (page == 0) {
            int mode = !draft.get(ClientConfig.ENABLE_DX12) ? 0 : draft.get(ClientConfig.REPLACE_VANILLA_TERRAIN) ? 1 : 2;
            var modeButton = button(text("mode", text("mode." + mode)), left, 78, panelWidth, () -> {
                int next = (mode + 1) % 3;
                draft.set(ClientConfig.ENABLE_DX12, next != 0);
                draft.set(ClientConfig.REPLACE_VANILLA_TERRAIN, next != 2);
                if (next == 1) draft.set(ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT, 100);
                rebuildWidgets();
            });
            modeButton.setTooltip(Tooltip.create(text("mode.help")));
            int preset = draft.presetIndex();
            var presets = button(text("preset", text("preset." + preset)), left, 102, panelWidth, () -> { draft.applyPreset((preset + 1) % 3); rebuildWidgets(); });
            presets.setTooltip(Tooltip.create(text("presets.help")));
            toggle(ClientConfig.SHOW_STATUS, left, 126, half);
            toggle(ClientConfig.PROFILE_RENDERER, right, 126, half);
            var retry = button(text("retry"), left, 150, half, () -> { Dx12GlInterop.requestRestart(); error = text("retry_requested"); });
            retry.active = ClientConfig.ENABLE_DX12.get();
            retry.setTooltip(Tooltip.create(text("retry.help")));
            button(text("diagnostics"), right, 150, half, () -> minecraft.setScreen(new DirectDiagnosticsScreen(this)));
            var visuals = button(text("visuals"), left, 174, half, this::openRaydiance);
            visuals.active = ModList.get().getModContainerById("raydiance").flatMap(m -> m.getCustomExtension(IConfigScreenFactory.class)).isPresent();
            visuals.setTooltip(Tooltip.create(text("visuals.help")));
            if (parent != null && parent.getClass().getName().equals("com.redondoguibi.raydiance.client.gui.RaydianceScreen")) {
                visuals.active = false;
                visuals.setTooltip(Tooltip.create(text("finish_first")));
            }
            button(text("help"), right, 174, half, () -> minecraft.setScreen(new DirectDiagnosticsScreen(this, true)));
        } else if (page == 1) {
            number(ClientConfig.MAX_MESH_UPLOADS_PER_FRAME, 1, 512, left, 78, half);
            number(ClientConfig.MAX_MESH_UPLOAD_MIB_PER_FRAME, 1, 512, right, 78, half);
            number(ClientConfig.UPLOAD_RING_BUFFER_MIB, 16, 512, left, 102, half);
            number(ClientConfig.MAX_TERRAIN_ARENA_MIB, 64, 4096, right, 102, half);
            number(ClientConfig.TERRAIN_ARENA_PAGE_MIB, 8, 256, left, 126, half);
            number(ClientConfig.MAX_PENDING_MESH_MIB, 64, 2048, right, 126, half);
            number(ClientConfig.MAX_MESH_UPLOAD_MICROS, 0, 20000, left, 150, half);
        } else if (page == 2) {
            toggle(ClientConfig.COMPRESS_BLOCK_ATLAS, left, 78, half);
            number(ClientConfig.BC7_ENCODER_THREADS, 1, 16, right, 78, half);
            number(ClientConfig.ANIMATED_ATLAS_REFRESH_MILLIS, 50, 2000, left, 102, half);
            toggle(ClientConfig.CPU_ATLAS_ANIMATION, right, 102, half);
        } else {
            toggle(ClientConfig.USE_GPU_SEMAPHORE_SYNC, left, 78, half);
            toggle(ClientConfig.FACE_CULLING, right, 78, half);
            nativeSharing(left, 102, half);
            toggle(ClientConfig.NATIVE_VERTEX_PACKING, right, 102, half);
            toggle(ClientConfig.PARALLEL_SECTION_RECORDING, left, 126, half);
            toggle(ClientConfig.USE_EXECUTE_INDIRECT, right, 126, half);
            toggle(ClientConfig.DX12_TRANSLUCENCY, left, 150, half);
            number(ClientConfig.MAX_SECTION_ADOPTIONS_PER_FRAME, 1, 512, right, 150, half);
            var distance = number(ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT, 25, 100, left, 174, half);
            toggle(ClientConfig.FLUSH_OPENGL_STAGES, right, 174, half);
            distance.active = !draft.get(ClientConfig.REPLACE_VANILLA_TERRAIN);
        }
        button(text("save"), left, height - 26, half, this::save);
        button(Component.translatable("gui.cancel"), right, height - 26, half, this::onClose);
    }

    private void openRaydiance() {
        ModList.get().getModContainerById("raydiance").ifPresent(mod -> mod.getCustomExtension(IConfigScreenFactory.class)
                .ifPresent(factory -> minecraft.setScreen(factory.createScreen(mod, this))));
    }

    private Button button(Component label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, 20).build());
    }

    private static String key(ConfigValue<?> setting) { return String.join(".", setting.getPath()); }

    /** One control for both sharing switches: off, color only, color and depth (direct bridge). */
    private void nativeSharing(int x, int y, int w) {
        boolean color = draft.get(ClientConfig.PREFER_DIRECT_COLOR_INTEROP);
        int level = !color ? 0 : draft.get(ClientConfig.DIRECT_BRIDGE) ? 2 : 1;
        var control = button(Component.translatable("direct.control.nativeSharing")
                        .append(": ").append(Component.translatable("direct.control.nativeSharing." + level)),
                x, y, w, () -> {
                    int next = (level + 1) % 3;
                    draft.set(ClientConfig.PREFER_DIRECT_COLOR_INTEROP, next != 0);
                    draft.set(ClientConfig.DIRECT_BRIDGE, next == 2);
                    rebuildWidgets();
                });
        control.setTooltip(Tooltip.create(Component.translatable("direct.help.nativeSharing")));
    }

    private void toggle(BooleanValue setting, int x, int y, int w) {
        var label = Component.translatable("direct.control." + key(setting));
        var control = button(label.copy().append(": ").append(Component.translatable(draft.get(setting) ? "options.on" : "options.off")),
                x, y, w, () -> { draft.set(setting, !draft.get(setting)); rebuildWidgets(); });
        control.setTooltip(Tooltip.create(Component.translatable("direct.help." + key(setting))));
    }

    private AbstractSliderButton number(IntValue setting, int min, int max, int x, int y, int w) {
        return addRenderableWidget(new AbstractSliderButton(x, y, w, 20, Component.empty(), (draft.get(setting) - min) / (double) (max - min)) {
            { updateMessage(); setTooltip(Tooltip.create(Component.translatable("direct.help." + key(setting)))); }
            private int selected() { return (int) Math.round(min + value * (max - min)); }
            @Override protected void updateMessage() { setMessage(Component.translatable("direct.control." + key(setting)).append(": " + selected())); }
            @Override protected void applyValue() { draft.set(setting, selected()); }
        });
    }

    private void save() {
        try {
            if (draft.get(ClientConfig.ENABLE_DX12) && draft.get(ClientConfig.REPLACE_VANILLA_TERRAIN)) draft.set(ClientConfig.TERRAIN_RENDER_DISTANCE_PERCENT, 100);
            if (draft.save()) Dx12GlInterop.requestRestart();
            minecraft.setScreen(parent);
        } catch (RuntimeException exception) {
            Direct.LOGGER.error("Could not save Direct settings.", exception);
            error = text("save_failed");
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, title, width / 2, 9, 0xFFFFFF);
        var reason = DirectEnvironment.unavailableReason();
        Component state = reason == null ? DirectStatusOverlay.message() : reason;
        g.drawCenteredString(font, font.plainSubstrByWidth(state.getString(), panelWidth), width / 2, 57, reason == null ? 0xB8DEC6 : 0xFFC48A);
        Component hint = !error.getString().isEmpty() ? error : text(draft.changed() ? "pending" : "hint");
        g.drawCenteredString(font, font.plainSubstrByWidth(hint.getString(), panelWidth), width / 2, height - 39, 0xB7C4D7);
    }
}

package com.redondoguibi.direct;

import java.util.ArrayList;
import java.util.List;
import com.redondoguibi.direct.api.DirectRenderService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModList;

/** Scrollable help and a copyable, local diagnostic snapshot; no network requests. */
final class DirectDiagnosticsScreen extends Screen {
    private final Screen parent;
    private final boolean help;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private String report = "";
    private int offset;
    private int contentWidth;

    DirectDiagnosticsScreen(Screen parent) { this(parent, false); }

    DirectDiagnosticsScreen(Screen parent, boolean help) {
        super(DirectScreen.text(help ? "help" : "diagnostics"));
        this.parent = parent;
        this.help = help;
    }

    @Override protected void init() {
        contentWidth = Math.min(560, width - 28);
        lines.clear();
        if (help) {
            StringBuilder text = new StringBuilder();
            for (String key : List.of("start", "modes", "presets", "save", "recovery", "visuals", "limits")) {
                text.append(DirectScreen.text("guide." + key).getString()).append("\n\n");
            }
            report = text.toString();
        } else {
            report = report();
        }
        for (String paragraph : report.split("\n", -1)) lines.addAll(font.split(Component.literal(paragraph.isEmpty() ? " " : paragraph), contentWidth));
        clamp();
        int left = width / 2 - 150;
        addRenderableWidget(Button.builder(DirectScreen.text("copy"), button -> {
            minecraft.keyboardHandler.setClipboard(report);
            button.setMessage(DirectScreen.text("copied"));
        }).bounds(left, height - 28, 96, 20).build());
        addRenderableWidget(Button.builder(DirectScreen.text("refresh"), button -> rebuildWidgets()).bounds(left + 102, height - 28, 96, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(left + 204, height - 28, 96, 20).build());
    }

    private static String report() {
        var service = DirectRenderService.getInstance();
        var caps = service.capabilities();
        var text = new StringBuilder("Direct ");
        text.append(ModList.get().getModContainerById("direct").map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown"));
        text.append(" / API ").append(service.apiVersion()).append('\n');
        text.append(DirectStatusOverlay.message().getString()).append("\n\n");
        var reason = DirectEnvironment.unavailableReason();
        if (reason != null) text.append(reason.getString()).append('\n');
        text.append("OS: ").append(System.getProperty("os.name")).append(" / ").append(System.getProperty("os.arch")).append('\n');
        text.append("Java: ").append(System.getProperty("java.version")).append('\n');
        text.append("Backend: ").append(caps.backendName()).append("\nAdapter: ").append(caps.adapterName()).append('\n');
        text.append("Hardware: ").append(caps.hardwareAdapter()).append(" / WGL: ").append(caps.wglInterop()).append('\n');
        text.append("GPU semaphores: ").append(caps.gpuInteropSemaphores()).append(" / depth input: ").append(caps.depthInput()).append('\n');
        text.append("Raydiance: ").append(ModList.get().getModContainerById("raydiance").map(mod -> mod.getModInfo().getVersion().toString()).orElse("not installed")).append('\n');
        text.append("\n").append(DirectScreen.text("last_failure").getString()).append(":\n");
        text.append(Dx12GlInterop.lastFailure().isEmpty() ? DirectScreen.text("none").getString() : Dx12GlInterop.lastFailure()).append('\n');
        text.append('\n').append(DirectScreen.text("extensions").getString()).append(": ").append(service.passes().size()).append('\n');
        for (var pass : service.passes()) {
            text.append(pass.id());
            String failure = service.passFailure(pass.id());
            if (!failure.isEmpty()) text.append(": ").append(failure);
            text.append('\n');
        }
        text.append('\n').append(DirectScreen.text("saved_settings").getString()).append('\n');
        for (var value : DirectSettings.VALUES) text.append(String.join(".", value.getPath())).append(" = ").append(value.get()).append('\n');
        text.append('\n').append("Game optimizations (config/").append(com.redondoguibi.direct.optimization.OptimizationSettings.FILE_NAME).append("):\n");
        var optimizations = com.redondoguibi.direct.optimization.OptimizationMixinPlugin.settings();
        var applied = com.redondoguibi.direct.optimization.OptimizationMixinPlugin.applied();
        for (var feature : com.redondoguibi.direct.optimization.OptimizationSettings.Feature.values()) {
            text.append(feature.key).append(": ");
            if (optimizations == null) text.append("not loaded");
            else if (!optimizations.enabled(feature)) text.append("off, ").append(optimizations.decision(feature).reason());
            else text.append("on, applied ").append(feature.mixins.stream().filter(applied::containsKey).count()).append('/').append(feature.mixins.size());
            text.append('\n');
        }
        text.append('\n').append(DirectScreen.text("diagnostic_hint").getString());
        return text.toString();
    }

    private int visible() { return Math.max(1, (height - 82) / 12); }
    private void clamp() { offset = Math.clamp(offset, 0, Math.max(0, lines.size() - visible())); }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        offset -= (int) Math.signum(vertical) * 3; clamp(); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        switch (key) {
            case 264 -> offset++;
            case 265 -> offset--;
            case 267 -> offset += visible();
            case 266 -> offset -= visible();
            case 268 -> offset = 0;
            case 269 -> offset = lines.size();
            default -> { return super.keyPressed(key, scan, modifiers); }
        }
        clamp(); return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        for (int i = 0; i < visible() && offset + i < lines.size(); i++) g.drawString(font, lines.get(offset + i), (width - contentWidth) / 2, 34 + i * 12, 0xD5E0ED);
        if (lines.size() > visible()) g.drawCenteredString(font, DirectScreen.text("scroll", offset + 1, lines.size()), width / 2, height - 43, 0xAAAAAA);
    }
}

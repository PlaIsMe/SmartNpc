package com.pla.smart_npc.client.gui;

import com.pla.smart_npc.ai.AiModelDownloader;
import com.pla.smart_npc.ai.AiModelTiers;
import com.pla.smart_npc.ai.AiSetupManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;

/** Legacy chat plus one clickable card per maintained Qwen model. */
public final class AiChatConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 62;
    private static final String RELEASES = "https://github.com/PlaIsMe/SmartNpc/releases";
    private record CardTone(int background, int hovered, int border) {}
    private static final CardTone LEGACY_TONE = new CardTone(0xFFDDE4EF, 0xFFECF0F8, 0xFF8CA4CD);
    private static final CardTone QWEN_TONE = new CardTone(0xFFE5DFF4, 0xFFF0EBFC, 0xFFA28AE3);
    private final Screen parent;
    private int scroll;
    private int poll;
    private int left;
    private int panelWidth;
    private int visibleRows;
    private int rowHeight;
    private static final int CARDS_TOP = 38;
    private record LogoSize(int width, int height) {}
    private final Map<ResourceLocation, LogoSize> logoSizes = new HashMap<>();

    public AiChatConfigScreen(Screen parent) { super(Component.translatable("smart_npc.ai.settings")); this.parent = parent; }
    @Override protected void init() { logoSizes.clear(); update(); AiSetupClient.request(AiSetupManager.Action.STATE, ""); }
    public void update() {
        clearWidgets();
        left = Math.max(8, (width - 660) / 2);
        panelWidth = width - left * 2;
        int cardCount = AiModelTiers.all().size() + 1;
        int availableHeight = Math.max(40, height - CARDS_TOP - 76);
        rowHeight = Math.max(40, Math.min(ROW_HEIGHT, availableHeight / cardCount));
        visibleRows = Math.max(1, Math.min(cardCount, availableHeight / rowHeight));
        scroll = Math.max(0, Math.min(scroll, cardCount - visibleRows));
        var state = AiSetupClient.snapshot();
        for (int index = scroll; index < Math.min(cardCount, scroll + visibleRows); index++) {
            final int row = index;
            Component name = row == 0 ? Component.translatable("smart_npc.ai.legacy_name") : tierName(AiModelTiers.all().get(row - 1));
            Button card = addRenderableWidget(new Button(left, CARDS_TOP + (row - scroll) * rowHeight, panelWidth, rowHeight - 3, name, ignored -> select(row), narration -> narration.get()) {
                @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    drawCard(graphics, this, row);
                }
            });
            card.active = state != null && state.canManage() && !state.busy();
        }
        if (visibleRows < cardCount) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> { scroll = Math.max(0, scroll - 1); update(); }).bounds(width - left - 46, 21, 21, 18).build());
            addRenderableWidget(Button.builder(Component.literal(">"), button -> { scroll = Math.min(cardCount - visibleRows, scroll + 1); update(); }).bounds(width - left - 22, 21, 21, 18).build());
        }
        if (state != null && state.busy()) {
            Button cancel = addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED), button -> AiSetupClient.request(AiSetupManager.Action.CANCEL, "")).bounds(left + panelWidth - 20, height - 60, 20, 20).build());
            cancel.active = state.canManage();
        }
        boolean local = AiSetupClient.hasLocalModels();
        int directoryWidth = Math.min(155, Math.max(95, panelWidth - 174));
        Button models = addRenderableWidget(Button.builder(Component.translatable(local ? "smart_npc.ai.open_models" : "smart_npc.ai.copy_server_models"), button -> AiSetupClient.openModels()).bounds(left, height - 25, directoryWidth, 20).build());
        models.active = state != null && (local || state.canManage());
        addRenderableWidget(Button.builder(Component.translatable("smart_npc.ai.source"), button -> minecraft.keyboardHandler.setClipboard(RELEASES)).bounds(left + directoryWidth + 4, height - 25, 78, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose()).bounds(width - left - 80, height - 25, 80, 20).build());
    }
    private static Component tierName(AiModelTiers.Tier tier) { return Component.translatable("smart_npc.ai.tier." + tier.tier()).append(" - " + tier.name()); }
    private void select(int row) {
        var state = AiSetupClient.snapshot();
        if (state == null || !state.canManage() || state.busy()) return;
        if (row == 0) { AiSetupClient.request(AiSetupManager.Action.LEGACY, ""); return; }
        var tier = AiModelTiers.all().get(row - 1);
        minecraft.setScreen(new AiModelLanguageScreen(this, tier.family(), tierName(tier).getString()));
    }
    private AiSetupManager.Card representative(AiModelTiers.Tier tier) {
        var state = AiSetupClient.snapshot();
        if (state == null) return null;
        var variants = state.models().stream().filter(card -> card.family().equals(tier.family())).toList();
        return variants.stream().filter(card -> state.enabled() && card.id().equals(state.selected())).findFirst()
                .orElseGet(() -> variants.stream().filter(card -> card.id().equals(tier.family() + "-en_us")).findFirst()
                        .orElseGet(() -> variants.stream().filter(AiSetupManager.Card::installed).findFirst().orElse(null)));
    }
    private void drawCard(GuiGraphics graphics, Button button, int row) {
        var state = AiSetupClient.snapshot();
        var tier = row == 0 ? null : AiModelTiers.all().get(row - 1);
        var card = tier == null ? null : representative(tier);
        boolean selected = state != null && (row == 0 ? !state.enabled() : state.enabled() && card != null && card.id().equals(state.selected()));
        int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
        boolean hover = button.active && button.isHoveredOrFocused();
        CardTone tone = row == 0 ? LEGACY_TONE : QWEN_TONE;
        graphics.fill(x, y, x + w, y + h, selected ? 0xFF83E59A : hover ? 0xFFC3D2E8 : 0xFF3C495D);
        graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, selected ? 0xF01B352B : hover ? 0xEE293748 : 0xEE18212D);
        graphics.fill(x + 2, y + 2, x + (selected ? 5 : 4), y + h - 2, selected ? 0xFF83E59A : tone.border());
        boolean compact = rowHeight < 58;
        int artWidth = Math.max(70, Math.min(114, w / 3));
        drawModelPanel(graphics, x + w - artWidth - 2, y + 2, artWidth, h - 4, tone, hover);
        int textWidth = w - artWidth - 20;
        graphics.drawString(font, font.plainSubstrByWidth(button.getMessage().getString(), textWidth), x + 8, y + (compact ? 4 : 6), selected ? 0xB4F7C2 : 0xFFFFFF);
        String icon = tier == null ? "legacy_chat" : tier.icon();
        ResourceLocation logo = new ResourceLocation("smart_npc", "textures/gui/ai/" + icon + ".png");
        LogoSize size = logoSizes.computeIfAbsent(logo, location -> {
            try (var stream = minecraft.getResourceManager().open(location); var image = NativeImage.read(stream)) {
                return new LogoSize(image.getWidth(), image.getHeight());
            } catch (IOException exception) { return new LogoSize(1, 1); }
        });
        double logoScale = Math.min((artWidth - 16.0) / size.width(), (h - 8.0) / size.height());
        int iconWidth = Math.max(1, (int) (size.width() * logoScale));
        int iconHeight = Math.max(1, (int) (size.height() * logoScale));
        graphics.blit(logo, x + w - artWidth - 2 + (artWidth - iconWidth) / 2, y + (h - iconHeight) / 2, iconWidth, iconHeight, 0, 0, size.width(), size.height(), size.width(), size.height());
        if (row == 0) {
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("smart_npc.ai.legacy_description").getString(), textWidth), x + 8, y + (compact ? 15 : 21), 0xDDDDDD);
            if (selected && !compact) selectedBadge(graphics, x + 8, y + 35);
            else graphics.drawString(font, font.plainSubstrByWidth(Component.translatable(selected ? "smart_npc.ai.selected" : "smart_npc.ai.legacy_requirements").getString(), textWidth), x + 8, y + (compact ? 26 : 37), selected ? 0x83E59A : 0xBBBBBB);
        } else {
            Component language = card == null ? Component.translatable("smart_npc.ai.release_pending") : Component.translatable("smart_npc.ai.model_language", AiModelLanguageScreen.variantName(card));
            if (compact && selected) language = Component.translatable("smart_npc.ai.selected").append(" · ").append(AiModelLanguageScreen.variantName(card));
            graphics.drawString(font, font.plainSubstrByWidth(language.getString(), textWidth), x + 8, y + (compact ? 15 : 19), card != null && card.unfiltered() ? 0xFF8888 : 0xDDDDDD);
            Component info = Component.translatable("smart_npc.ai.ram", Component.literal(tier.ram()));
            graphics.drawString(font, font.plainSubstrByWidth(info.getString(), textWidth), x + 8, y + (compact ? 26 : 31), 0xBBBBBB);
            Component hint = Component.translatable(selected ? "smart_npc.ai.selected" : "smart_npc.ai.choose_language");
            if (!compact && selected) selectedBadge(graphics, x + 8, y + 41);
            else if (!compact) graphics.drawString(font, font.plainSubstrByWidth(hint.getString(), textWidth), x + 8, y + 43, 0xBBBBBB);
        }
    }
    private void selectedBadge(GuiGraphics graphics, int x, int y) {
        Component label = Component.translatable("smart_npc.ai.selected");
        graphics.fill(x - 2, y, x + font.width(label) + 4, y + 12, 0xFF83E59A);
        graphics.drawString(font, label.getString(), x + 1, y + 2, 0x17251B, false);
    }
    private static void drawModelPanel(GuiGraphics graphics, int x, int y, int width, int height, CardTone tone, boolean hover) {
        // A chamfered hardware panel, integrated into the card with a brand-colored seam.
        for (int line = 0; line < height; line++) {
            int inset = 14 * (height - 1 - line) / Math.max(1, height - 1);
            graphics.fill(x + inset, y + line, x + width, y + line + 1, hover ? tone.hovered() : tone.background());
            graphics.fill(x + inset, y + line, x + inset + 1, y + line + 1, tone.border());
        }
        // Quiet circuit traces frame the logo rather than covering the card's content.
        int ink = 0x33717E9E;
        graphics.fill(x + 20, y + 6, x + 43, y + 7, ink);
        graphics.fill(x + 43, y + 6, x + 44, y + 12, ink);
        graphics.fill(x + 42, y + 11, x + 45, y + 14, tone.border());
        graphics.fill(x + 12, y + height - 7, x + 31, y + height - 6, ink);
        graphics.fill(x + 30, y + height - 12, x + 31, y + height - 6, ink);
        graphics.fill(x + 29, y + height - 14, x + 32, y + height - 11, tone.border());
        graphics.fill(x + width - 8, y + height - 8, x + width - 5, y + height - 5, tone.border());
    }
    @Override public void tick() { if (++poll >= 20) { poll = 0; AiSetupClient.request(AiSetupManager.Action.STATE, ""); } }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int next = Math.max(0, Math.min(AiModelTiers.all().size() + 1 - visibleRows, scroll + (delta > 0 ? -1 : 1)));
        if (next != scroll) { scroll = next; update(); return true; }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        var state = AiSetupClient.snapshot();
        String status = state == null ? Component.translatable("smart_npc.ai.loading").getString() : Component.translatable(!state.canManage() ? "smart_npc.ai.readonly" : state.enabled() ? "smart_npc.ai.enabled" : "smart_npc.ai.default_mode").getString();
        // Retired/custom selections remain identified in the status line.
        if (state != null && state.enabled()) {
            var current = state.models().stream().filter(card -> card.id().equals(state.selected())).findFirst().orElse(null);
            if (current != null) status = Component.translatable("smart_npc.ai.current_model", current.name(), AiModelLanguageScreen.variantName(current)).getString();
        }
        graphics.drawString(font, font.plainSubstrByWidth(status, panelWidth - (visibleRows < AiModelTiers.all().size() + 1 ? 52 : 0)), left, 29, 0xDDDDAA);
        if (state != null) {
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("smart_npc.ai.storage", state.storage()).getString(), panelWidth), left, height - 76, 0xAAAAAA);
            if (state.busy()) drawProgress(graphics, state.progress());
            String message = !AiSetupClient.error().isEmpty() ? AiSetupClient.error() : !state.chatAllowed() ? Component.translatable("smart_npc.ai.chat_disabled").getString() : Component.translatable(state.message()).getString();
            if (message.isEmpty() && !state.busy() && state.runtimeStatus().startsWith("smart_npc.ai.")) message = Component.translatable(state.runtimeStatus()).getString();
            graphics.drawString(font, font.plainSubstrByWidth(message, panelWidth), left, height - 36, 0xE5C86B);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    public static Component ramEstimate(AiSetupManager.Card card) {
        String estimate = card.ramEstimate();
        String measured = " MiB (measured native peak)";
        if (estimate.matches("[0-9]+(?:\\.[0-9]+)? MiB \\(measured native peak\\)")) {
            double gib = Double.parseDouble(estimate.substring(0, estimate.length() - measured.length())) / 1024.0;
            return Component.translatable("smart_npc.ai.measured_ram", String.format(Locale.ROOT, "%.1f", Math.ceil(gib * 10) / 10));
        }
        if (estimate.equals("Not measured") || estimate.startsWith("Not benchmarked")) return Component.translatable("smart_npc.ai.ram_unmeasured");
        int suffix = estimate.indexOf(" (estimate");
        if (suffix >= 0) return Component.translatable("smart_npc.ai.ram_unmeasured");
        return Component.literal(estimate);
    }
    private void drawProgress(GuiGraphics graphics, AiModelDownloader.Progress progress) {
        String phase = progress == null ? "lookup" : progress.phase();
        long total = progress == null ? 0 : progress.total();
        double fraction = total > 0 ? Math.max(0, Math.min(1, progress.received() / (double) total)) : 0;
        String label = Component.translatable("smart_npc.ai.progress." + phase).getString();
        if (total > 0) label += "  " + (int) (fraction * 100) + "%";
        if (progress != null && progress.secondsRemaining() >= 0) {
            long seconds = progress.secondsRemaining();
            String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
            label += "  " + Component.translatable("smart_npc.ai.remaining", time).getString();
        }
        graphics.drawString(font, font.plainSubstrByWidth(label, panelWidth - 24), left, height - 63, 0xDDDDDD);
        int y = height - 50;
        graphics.fill(left, y, left + panelWidth - 24, y + 8, 0xFF777777);
        graphics.fill(left + 1, y + 1, left + panelWidth - 25, y + 7, 0xFF252525);
        int available = panelWidth - 26;
        if (total > 0) graphics.fill(left + 1, y + 1, left + 1 + (int) (available * fraction), y + 7, 0xFF83D98B);
        else {
            int pulse = Math.max(1, available / 5);
            int offset = (int) ((net.minecraft.Util.getMillis() / 12) % Math.max(1, available - pulse));
            graphics.fill(left + 1 + offset, y + 1, left + 1 + offset + pulse, y + 7, 0xFF83D98B);
        }
    }
    public static String megabytes(long bytes) { return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

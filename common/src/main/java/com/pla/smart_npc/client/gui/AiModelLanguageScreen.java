package com.pla.smart_npc.client.gui;

import com.pla.smart_npc.ai.AiSetupManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A language click activates installed weights or installs and activates a published release. */
public final class AiModelLanguageScreen extends Screen {
    private final AiChatConfigScreen parent;
    private final String family;
    private final String modelName;
    private final List<Button> selectedButtons = new ArrayList<>();
    private int panelTop;
    private int panelLeft;
    private int panelWidth;
    private int panelHeight;
    private int poll;

    public AiModelLanguageScreen(AiChatConfigScreen parent, String family, String modelName) {
        super(Component.translatable("smart_npc.ai.choose_language"));
        this.parent = parent;
        this.family = family;
        this.modelName = modelName;
    }
    @Override protected void init() { update(); AiSetupClient.request(AiSetupManager.Action.STATE, ""); }
    public void update() {
        clearWidgets();
        selectedButtons.clear();
        panelWidth = Math.min(440, width - 16);
        panelLeft = (width - panelWidth) / 2;
        panelHeight = Math.min(256, height - 8);
        panelTop = Math.max(4, (height - panelHeight) / 2);
        int x = panelLeft + 12;
        int y = panelTop + 79;
        int spacing = Math.max(22, Math.min(30, (panelHeight - 143) / 3));
        languageButton("en_us", false, x, y);
        languageButton("en_us", true, x, y + spacing);
        languageButton("zh_cn", false, x, y + spacing * 2);
        languageButton("vi_vn", false, x, y + spacing * 3);
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose()).bounds(x, panelTop + panelHeight - 30, panelWidth - 24, 20).build());
    }
    private void languageButton(String locale, boolean unfiltered, int x, int y) {
        var state = AiSetupClient.snapshot();
        var variants = state == null ? List.<AiSetupManager.Card>of() : state.models().stream().filter(card -> family.equals(card.family()) && locale.equals(card.language()) && card.unfiltered() == unfiltered).toList();
        var variant = variants.stream().sorted(Comparator.comparingInt(card -> state.enabled() && card.id().equals(state.selected()) ? 0 : card.installed() ? 1 : card.id().equals(card.family()) ? 3 : 2)).findFirst().orElse(null);
        boolean selected = state != null && state.enabled() && variant != null && variant.id().equals(state.selected());
        Component label = unfiltered ? Component.translatable("smart_npc.ai.unfiltered_english") : Component.literal(languageName(locale));
        Component text = label.copy();
        if (selected || variant == null) text = text.copy().append(" - ").append(Component.translatable(selected ? "smart_npc.ai.selected" : "smart_npc.ai.language_unavailable_short"));
        if (unfiltered) text = text.copy().withStyle(ChatFormatting.RED);
        boolean installed = variant != null && variant.installed();
        int buttonWidth = panelWidth - 48;
        Button button = addRenderableWidget(Button.builder(text, ignored -> choose(variant)).bounds(x, y, buttonWidth, 20).build());
        if (selected) selectedButtons.add(button);
        button.active = state != null && state.canManage() && state.chatAllowed() && !state.busy() && variant != null && !selected;
        if (installed) {
            Button delete = addRenderableWidget(new Button(x + buttonWidth + 4, y, 20, 20, Component.translatable("smart_npc.ai.delete"), ignored -> delete(variant), narration -> narration.get()) {
                @Override public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    int bx = getX(), by = getY();
                    boolean hover = active && isHoveredOrFocused();
                    graphics.fill(bx, by, bx + 20, by + 20, hover ? 0xFFFF9292 : active ? 0xFF91636C : 0xFF46505B);
                    graphics.fill(bx + 1, by + 1, bx + 19, by + 19, hover ? 0xFF633440 : 0xFF28313C);
                    int ink = active ? 0xFFFFBBBB : 0xFF69727C;
                    // A pixel trash bin, independent of font/locale glyph support.
                    graphics.fill(bx + 8, by + 4, bx + 12, by + 5, ink);
                    graphics.fill(bx + 5, by + 6, bx + 15, by + 7, ink);
                    graphics.fill(bx + 6, by + 8, bx + 7, by + 15, ink);
                    graphics.fill(bx + 13, by + 8, bx + 14, by + 15, ink);
                    graphics.fill(bx + 7, by + 15, bx + 13, by + 16, ink);
                    graphics.fill(bx + 9, by + 9, bx + 10, by + 14, ink);
                    graphics.fill(bx + 11, by + 9, bx + 12, by + 14, ink);
                }
            });
            var active = state.models().stream().filter(card -> state.enabled() && card.id().equals(state.selected())).findFirst().orElse(null);
            boolean activeFile = active != null && active.file().equals(variant.file());
            delete.active = state.canManage() && !state.busy() && !selected && !activeFile;
        }
    }
    private void choose(AiSetupManager.Card card) {
        var state = AiSetupClient.snapshot();
        if (card == null || state == null || !state.canManage() || !state.chatAllowed() || state.busy()) return;
        minecraft.setScreen(parent);
        AiSetupClient.request(card.installed() ? AiSetupManager.Action.USE : AiSetupManager.Action.DOWNLOAD, card.id());
    }
    private void delete(AiSetupManager.Card card) {
        minecraft.setScreen(new ConfirmScreen(accepted -> {
            minecraft.setScreen(this);
            if (accepted) AiSetupClient.request(AiSetupManager.Action.DELETE, card.id());
        }, Component.translatable("smart_npc.ai.delete_confirm", card.name() + " - " + variantName(card)), Component.literal(card.file())));
    }
    @Override public void tick() { if (++poll >= 20) { poll = 0; AiSetupClient.request(AiSetupManager.Action.STATE, ""); } }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelTop + panelHeight + 1, 0xFF617183);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xEE18212C);
        graphics.drawCenteredString(font, title, width / 2, panelTop + 12, 0xFFFFFF);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(modelName, panelWidth - 20), width / 2, panelTop + 28, 0xDDDDDD);
        var state = AiSetupClient.snapshot();
        var selected = state == null || !state.enabled() ? null : state.models().stream().filter(card -> card.id().equals(state.selected())).findFirst().orElse(null);
        Component current = selected == null ? Component.translatable("smart_npc.ai.no_language_selected") : Component.translatable("smart_npc.ai.selected_language", variantName(selected));
        graphics.drawCenteredString(font, font.plainSubstrByWidth(current.getString(), panelWidth - 16), width / 2, panelTop + 47, 0x83D98B);
        graphics.drawCenteredString(font, Component.translatable("smart_npc.ai.language_shared"), width / 2, panelTop + 63, 0xBBBBBB);
        Component note = Component.translatable(state != null && !state.message().isBlank() ? state.message() : "smart_npc.ai.language_click_note");
        graphics.drawCenteredString(font, font.plainSubstrByWidth(note.getString(), panelWidth - 16), width / 2, panelTop + panelHeight - 44, 0xBBBBBB);
        for (Button button : selectedButtons) graphics.fill(button.getX() - 2, button.getY() - 2, button.getX() + button.getWidth() + 2, button.getY() + button.getHeight() + 2, 0xFF83D98B);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    public static String languageName(String locale) {
        return switch (locale) {
            case "en_us" -> "English (en_us)";
            case "zh_cn" -> "简体中文 (zh_cn)";
            case "vi_vn" -> "Tiếng Việt (vi_vn)";
            default -> locale;
        };
    }
    public static String variantName(AiSetupManager.Card card) {
        return card.unfiltered() ? Component.translatable("smart_npc.ai.unfiltered_english").getString() : languageName(card.language());
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

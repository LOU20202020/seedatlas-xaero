package org.seedatlas.xaero.gui;

import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.nativeapi.BiomeSample;

/** Biome focus selector, laid out like MarkerSettingsScreen. */
public final class BiomeHighlightScreen extends SeedAtlasScreen {
    private static final int HEADER_HEIGHT = 92;
    private static final int FOOTER_HEIGHT = 38;
    private List<BiomeSample> biomes = List.of();
    private Button highlightButton;
    private Button clearButton;

    public BiomeHighlightScreen(Screen parent) {
        super(Component.translatable("screen.seedatlas_xaero.biome_highlights"), parent);
    }

    @Override
    protected void init() {
        this.biomes = SeedAtlasXaeroIntegration.availableBiomes().stream()
            .sorted(Comparator.comparing(b -> SeedAtlasXaeroIntegration.biomeDisplayName(b.name()).getString()))
            .toList();
        int margin = Math.min(16, Math.max(6, this.width / 18));
        int listWidth = Math.min(420, Math.max(180, this.width - margin * 2));
        listWidth = Math.min(listWidth, this.width - 8);
        int left = (this.width - listWidth) / 2;
        int bottom = Math.min(Math.max(HEADER_HEIGHT + 48, this.height - FOOTER_HEIGHT), this.height - 26);
        int listHeight = Math.max(48, bottom - HEADER_HEIGHT);
        int halfWidth = (listWidth - 6) / 2;

        this.highlightButton = this.addRenderableWidget(Button.builder(highlightSwitchMessage(), button -> {
            SeedAtlasClientState.setBiomeHighlightEnabled(!SeedAtlasClientState.config().biomeHighlightEnabled());
            refreshLabels();
        }).bounds(left, 36, halfWidth, 20).build());
        this.clearButton = this.addRenderableWidget(Button.builder(
            Component.translatable("options.seedatlas_xaero.highlight_clear"), button -> {
                SeedAtlasClientState.clearHighlightedBiomes();
                refreshLabels();
            }).bounds(left + halfWidth + 6, 36, listWidth - halfWidth - 6, 20).build());
        BiomeToggleList list = new BiomeToggleList(this.minecraft, left, HEADER_HEIGHT, listWidth, listHeight, this.biomes, this::refreshLabels);
        EditBox search = new EditBox(this.font, left, 62, listWidth, 20, Component.translatable("options.seedatlas_xaero.biome_search"));
        search.setHint(Component.translatable("options.seedatlas_xaero.biome_search"));
        search.setResponder(list::setFilter);
        this.addRenderableWidget(search);
        this.addRenderableWidget(list);
        refreshLabels();

        int doneWidth = Math.min(200, Math.max(120, this.width - 24));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds((this.width - doneWidth) / 2, Math.max(0, this.height - 28), doneWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        Component status = Component.translatable(
            SeedAtlasClientState.config().biomeHighlightEnabled()
                ? "screen.seedatlas_xaero.biome_highlights.selected"
                : "screen.seedatlas_xaero.biome_highlights.disabled",
            SeedAtlasClientState.config().highlightedBiomeCount());
        graphics.centeredText(this.font, status, this.width / 2, 23, 0xFFB8B8B8);
        if (this.biomes.isEmpty()) {
            graphics.centeredText(this.font,
                Component.translatable("screen.seedatlas_xaero.biome_highlights.no_seed"),
                this.width / 2, HEADER_HEIGHT + 16, 0xFFB8B8B8);
        }
    }

    private void refreshLabels() {
        this.highlightButton.setMessage(highlightSwitchMessage());
        this.highlightButton.active = !this.biomes.isEmpty();
        this.clearButton.active = SeedAtlasClientState.config().highlightedBiomeCount() > 0;
    }

    private static Component highlightSwitchMessage() {
        return Component.translatable("options.seedatlas_xaero.highlight_enabled",
            SeedAtlasSettingsScreen.toggleValue(SeedAtlasClientState.config().biomeHighlightEnabled()));
    }

    @Override
    public void onClose() {
        SeedAtlasClientState.save();
        this.minecraft.gui.setScreen(this.parent);
    }
}

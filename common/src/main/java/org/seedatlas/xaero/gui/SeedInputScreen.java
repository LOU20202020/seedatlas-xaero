package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.nativeapi.Dimension;

/**
 * Seed entry over the world map, opened by clicking the seed bar. The field accepts a number or any
 * text and hashes it exactly like vanilla's world creation screen; the seed belongs to the world or
 * server of the active context, like every other Seed Atlas setting.
 */
public final class SeedInputScreen extends SeedAtlasScreen {
	private static final Component TITLE = Component.translatable("screen.seedatlas_xaero.seed_input");
	private static final int CONTENT_WIDTH = 300;
	private static final int TOP_OFFSET = 84;

	private final Dimension dimension;
	private EditBox field;
	private Component error;

	public SeedInputScreen(final Screen parent, final Dimension dimension) {
		super(TITLE, parent);
		this.dimension = dimension;
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(CONTENT_WIDTH, this.width - 40);
		int left = (this.width - contentWidth) / 2;
		int top = this.height / 2 - TOP_OFFSET;

		this.field = new EditBox(this.font, left, top + 34, contentWidth, 20,
			Component.translatable("screen.seedatlas_xaero.seed_input.field"));
		this.field.setMaxLength(64);
		this.field.setHint(Component.translatable("screen.seedatlas_xaero.seed_input.hint"));
		SeedAtlasClientState.activeSeed(this.dimension)
			.ifPresent(seed -> this.field.setValue(Long.toString(seed)));
		this.addRenderableWidget(this.field);
		this.setInitialFocus(this.field);

		int half = (contentWidth - 6) / 2;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.apply())
			.bounds(left, top + 62, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
			.bounds(left + half + 6, top + 62, contentWidth - half - 6, 20).build());

		boolean own = this.dimension != Dimension.OVERWORLD && SeedAtlasClientState.activeProfile()
			.map(profile -> profile.ownSeed(this.dimension) != null).orElse(false);
		if (own) {
			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.seedatlas_xaero.seed_input.follow_base"),
				button -> {
					SeedAtlasClientState.clearDimensionSeed(this.dimension);
					this.onClose();
				}).bounds(left, top + 88, contentWidth, 20).build());
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY,
		final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = this.height / 2 - TOP_OFFSET;
		graphics.centeredText(this.font, this.title, this.width / 2, top - 22, 0xFFFFFFFF);
		graphics.centeredText(this.font,
			Component.translatable("screen.seedatlas_xaero.seed_input.dimension", dimensionName()),
			this.width / 2, top, 0xFFB8B8B8);
		graphics.centeredText(this.font,
			Component.translatable(this.dimension == Dimension.OVERWORLD
				? "screen.seedatlas_xaero.seed_input.note_base"
				: "screen.seedatlas_xaero.seed_input.note_dimension"),
			this.width / 2, top + 14, 0xFF8A8A8A);
		if (this.error != null) {
			graphics.centeredText(this.font, this.error, this.width / 2, top + 116, 0xFFFF7070);
		}
	}

	private Component dimensionName() {
		return Component.translatable(switch (this.dimension) {
			case OVERWORLD -> "dimension.seedatlas_xaero.overworld";
			case NETHER -> "dimension.seedatlas_xaero.nether";
			case END -> "dimension.seedatlas_xaero.end";
		});
	}

	private void apply() {
		String input = this.field.getValue().trim();
		if (input.isEmpty()) {
			this.error = Component.translatable("screen.seedatlas_xaero.seed_input.blank");
			return;
		}
		try {
			if (this.dimension == Dimension.OVERWORLD) {
				SeedAtlasClientState.setSeed(input);
			} else {
				SeedAtlasClientState.setDimensionSeed(this.dimension, input);
			}
			this.onClose();
		} catch (IllegalStateException noContext) {
			this.error = Component.translatable("screen.seedatlas_xaero.seed_input.no_context");
		} catch (RuntimeException invalid) {
			this.error = Component.translatable("screen.seedatlas_xaero.seed_input.invalid");
		}
	}

	@Override
	public void onClose() {
		SeedAtlasClientState.save();
		this.minecraft.gui.setScreen(this.parent);
	}
}

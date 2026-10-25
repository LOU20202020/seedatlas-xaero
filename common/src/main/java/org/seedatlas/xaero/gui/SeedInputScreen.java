package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.compat.SeedCrackerSupport;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.nativeapi.Dimension;

/**
 * Seed entry over the world map, opened by clicking the seed bar or the settings button. The field
 * accepts a number or any text and hashes it exactly like vanilla's world creation screen; the
 * Overworld button edits the base seed, the Nether and End buttons give those dimensions their own.
 */
public final class SeedInputScreen extends SeedAtlasScreen {
	private static final Component TITLE = Component.translatable("screen.seedatlas_xaero.seed_input");
	private static final int CONTENT_WIDTH = 300;
	private static final int TOP_OFFSET = 92;
	private static final Dimension[] DIMENSIONS = {Dimension.OVERWORLD, Dimension.NETHER, Dimension.END};

	private Dimension dimension;
	private EditBox field;
	private Component error;
	private final Button[] tabs = new Button[DIMENSIONS.length];
	private Button followBase;

	public SeedInputScreen(final Screen parent, final Dimension dimension) {
		super(TITLE, parent);
		this.dimension = dimension;
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(CONTENT_WIDTH, this.width - 40);
		int left = (this.width - contentWidth) / 2;
		int top = this.height / 2 - TOP_OFFSET;

		int tabGap = 4;
		int tabWidth = (contentWidth - tabGap * (DIMENSIONS.length - 1)) / DIMENSIONS.length;
		for (int i = 0; i < DIMENSIONS.length; i++) {
			Dimension tab = DIMENSIONS[i];
			Button button = Button.builder(dimensionName(tab), b -> this.select(tab))
				.bounds(left + (tabWidth + tabGap) * i, top - 20, tabWidth, 18).build();
			this.tabs[i] = button;
			this.addRenderableWidget(button);
		}

		this.field = new EditBox(this.font, left, top + 16, contentWidth, 20,
			Component.translatable("screen.seedatlas_xaero.seed_input.field"));
		this.field.setMaxLength(64);
		this.field.setHint(Component.translatable("screen.seedatlas_xaero.seed_input.hint"));
		this.addRenderableWidget(this.field);
		this.setInitialFocus(this.field);

		int half = (contentWidth - 6) / 2;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.apply())
			.bounds(left, top + 44, half, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
			.bounds(left + half + 6, top + 44, contentWidth - half - 6, 20).build());

		this.followBase = this.addRenderableWidget(Button.builder(
			Component.translatable("screen.seedatlas_xaero.seed_input.follow_base"), button -> {
				SeedAtlasClientState.clearDimensionSeed(this.dimension);
				this.select(this.dimension);
			}).bounds(left, top + 68, contentWidth, 20).build());

		if (SeedCrackerSupport.available()) {
			this.addRenderableWidget(Button.builder(
				Component.translatable("screen.seedatlas_xaero.seed_input.clear_data"), button -> {
					SeedCrackerSupport.clearCollected();
				}).bounds(left, top + 198, contentWidth, 20).build());
		}

		this.refresh();
	}

	/** Switches which dimension the field edits; the tab row always shows the selection. */
	private void select(final Dimension next) {
		this.dimension = next;
		this.error = null;
		this.refresh();
	}

	private void refresh() {
		SeedAtlasClientState.activeSeed(this.dimension).ifPresentOrElse(
			seed -> this.field.setValue(Long.toString(seed)),
			() -> this.field.setValue(""));
		for (int i = 0; i < DIMENSIONS.length; i++) {
			boolean selected = DIMENSIONS[i] == this.dimension;
			this.tabs[i].setMessage(dimensionName(DIMENSIONS[i]));
			this.tabs[i].active = !selected;
		}
		boolean own = this.dimension != Dimension.OVERWORLD && SeedAtlasClientState.activeProfile()
			.map(profile -> profile.ownSeed(this.dimension) != null).orElse(false);
		this.followBase.visible = own;
		this.followBase.active = own;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY,
		final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int top = this.height / 2 - TOP_OFFSET;
		graphics.centeredText(this.font, this.title, this.width / 2, top - 44, 0xFFFFFFFF);
		graphics.centeredText(this.font,
			Component.translatable(this.dimension == Dimension.OVERWORLD
				? "screen.seedatlas_xaero.seed_input.note_base"
				: "screen.seedatlas_xaero.seed_input.note_dimension"),
			this.width / 2, top - 2, 0xFF8A8A8A);
		if (this.error != null) {
			graphics.centeredText(this.font, this.error, this.width / 2, top + 94, 0xFFFF7070);
		}
		int contentWidth = Math.min(CONTENT_WIDTH, this.width - 40);
		int left = (this.width - contentWidth) / 2;
		if (!SeedCrackerSupport.available()) {
			graphics.centeredText(this.font,
				Component.translatable("screen.seedatlas_xaero.seed_input.cracker_missing"),
				this.width / 2, top + 110, 0xFF7A7A7A);
			return;
		}

		SeedCrackerSupport.Status status = SeedCrackerSupport.status();
		int barWidth = (contentWidth - 8) / 2;
		graphics.text(this.font, Component.translatable("screen.seedatlas_xaero.seed_input.bits_base",
			Math.round(status.base()), SeedCrackerSupport.BASE_TARGET), left, top + 108, 0xFFA0A0A0);
		graphics.text(this.font, Component.translatable("screen.seedatlas_xaero.seed_input.bits_lift",
			Math.round(status.lifting()), SeedCrackerSupport.LIFT_TARGET),
			left + barWidth + 8, top + 108, 0xFFA0A0A0);
		drawBar(graphics, left, top + 124, barWidth, status.base(), SeedCrackerSupport.BASE_TARGET, 0xFF55AA55);
		drawBar(graphics, left + barWidth + 8, top + 124, barWidth, status.lifting(), SeedCrackerSupport.LIFT_TARGET, 0xFF4A90D9);

		Component state;
		int color = 0xFF9A9A9A;
		if (status.solved()) {
			state = Component.translatable("screen.seedatlas_xaero.seed_input.state_solved");
			color = 0xFF7CE07C;
		} else if (status.cracking()) {
			state = Component.translatable("screen.seedatlas_xaero.seed_input.state_running");
			color = 0xFFFFD070;
		} else if (status.hasBase() && status.hasLifting()) {
			state = Component.translatable("screen.seedatlas_xaero.seed_input.state_ready");
			color = 0xFF7CE07C;
		} else {
			state = Component.translatable("screen.seedatlas_xaero.seed_input.state_more",
				Math.max(0, SeedCrackerSupport.BASE_TARGET - (int)Math.round(status.base())),
				Math.max(0, SeedCrackerSupport.LIFT_TARGET - (int)Math.round(status.lifting())));
		}
		graphics.centeredText(this.font, state, this.width / 2, top + 138, color);

		// Plain-language how-to: the numbers above mean nothing without it. Three short lines fit
		// the smallest window this screen is used at (~408x285 logical pixels).
		if (!status.solved()) {
			graphics.centeredText(this.font,
				Component.translatable("screen.seedatlas_xaero.seed_input.guide1"),
				this.width / 2, top + 156, 0xFFB8B8B8);
			graphics.centeredText(this.font,
				Component.translatable("screen.seedatlas_xaero.seed_input.guide2"),
				this.width / 2, top + 167, 0xFFB8B8B8);
			graphics.centeredText(this.font,
				Component.translatable("screen.seedatlas_xaero.seed_input.guide3"),
				this.width / 2, top + 178, 0xFFB8B8B8);
		}
	}

	/** Five-pixel bar: collected against the target SeedCrackerX waits for. */
	private static void drawBar(final GuiGraphicsExtractor graphics, final int x, final int y, final int width,
		final double value, final int target, final int color) {
		graphics.fill(x, y, x + width, y + 5, 0x60000000);
		int filled = (int)Math.round(Math.min(1.0D, value / target) * width);
		if (filled > 0) {
			graphics.fill(x, y, x + filled, y + 5, color);
		}
		graphics.outline(x, y, width, 5, 0xFF5A5A5A);
	}

	private static Component dimensionName(final Dimension dimension) {
		return Component.translatable(switch (dimension) {
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

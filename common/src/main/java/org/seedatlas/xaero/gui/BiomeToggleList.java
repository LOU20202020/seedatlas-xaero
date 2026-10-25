package org.seedatlas.xaero.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.seedatlas.xaero.nativeapi.BiomeSample;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;

/** Biome selector with the same rows, switches and scrollbar as the structure selector; or one button per biome. */
final class BiomeToggleList extends AbstractWidget {
	private static final int ROW_HEIGHT = 28;
	private static final int ROW_GAP = 2;
	private static final int CONTENT_PAD = 6;
	private static final int ICON_SIZE = 20;
	private static final int TOGGLE_WIDTH = 56;
	private static final int TOGGLE_HEIGHT = 20;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int SCROLLBAR_PAD = 3;

	private final Minecraft minecraft;
	private final List<Row> allRows = new ArrayList<>();
	private List<Row> rows = this.allRows;
	private final Runnable onChange;
	private final Predicate<BiomeSample> isOn = m -> SeedAtlasClientState.config().isBiomeHighlighted(m.id());
	private Consumer<BiomeSample> flip = m -> SeedAtlasClientState.toggleHighlightedBiome(m.id());
	/** Set in button mode: the label every row's button shows instead of its on/off state. */
	private Component actionLabel;
	private BooleanSupplier actionsEnabled = () -> true;
	private double scrollAmount;
	private boolean draggingScrollbar;

	BiomeToggleList(final Minecraft minecraft, final int x, final int y, final int width, final int height, final List<BiomeSample> biomes, final Runnable onChange) {
		super(x, y, width, height, Component.translatable("screen.seedatlas_xaero.biome_highlights"));
		this.minecraft = minecraft;
		this.onChange = onChange;
		for (BiomeSample marker : biomes) {
			this.allRows.add(new Row(marker));
		}
	}

	/** One-shot buttons instead of switches: each press runs the action, and nothing stays selected. */
	void useAction(final Component label, final BooleanSupplier enabled, final Consumer<BiomeSample> action) {
		this.actionLabel = label;
		this.actionsEnabled = enabled;
		this.flip = action;
	}

	void setFilter(final String query) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		this.rows = this.allRows.stream().filter(r -> q.isEmpty()
			|| SeedAtlasXaeroIntegration.biomeDisplayName(r.marker.name()).getString().toLowerCase(Locale.ROOT).contains(q)
			|| r.marker.name().toLowerCase(Locale.ROOT).contains(q)).toList();
		this.scrollAmount = 0.0D;
	}

	@Override
	protected void extractWidgetRenderState(
		final GuiGraphicsExtractor graphics,
		final int mouseX,
		final int mouseY,
		final float partialTick
	) {
		clampScroll();

		graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0xD0121212);
		graphics.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0xFF5A5A5A);
		graphics.fill(this.getX(), this.getY(), this.getRight(), this.getY() + 1, 0xFF888888);
		graphics.fill(this.getX(), this.getBottom() - 1, this.getRight(), this.getBottom(), 0xFF303030);

		int contentLeft = this.getX() + CONTENT_PAD;
		int contentRight = contentRight();
		int contentTop = this.getY() + CONTENT_PAD;
		int contentBottom = this.getBottom() - CONTENT_PAD;

		graphics.enableScissor(contentLeft - 1, contentTop - 1, contentRight + 1, contentBottom + 1);
		int y = contentTop - (int) Math.round(this.scrollAmount);
		for (int index = 0; index < this.rows.size(); index++) {
			Row row = this.rows.get(index);
			int rowBottom = y + ROW_HEIGHT;
			if (rowBottom >= contentTop && y <= contentBottom) {
				boolean hovered = mouseX >= contentLeft
					&& mouseX < contentRight
					&& mouseY >= Math.max(y, contentTop)
					&& mouseY < Math.min(rowBottom, contentBottom);
				row.render(graphics, contentLeft, y, contentRight - contentLeft, index, hovered, mouseX, mouseY, partialTick);
			}
			y += ROW_HEIGHT + ROW_GAP;
		}
		graphics.disableScissor();

		renderScrollbar(graphics);
	}

	@Override
	public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
		if (!this.visible || !this.isMouseOver(mouseX, mouseY)) {
			return false;
		}
		this.scrollAmount = Mth.clamp(this.scrollAmount - scrollY * ROW_HEIGHT, 0.0D, maxScroll());
		return true;
	}

	@Override
	public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
		if (!this.active || !this.visible) {
			return;
		}
		double mouseX = event.x();
		double mouseY = event.y();
		if (isOverScrollbar(mouseX, mouseY) && maxScroll() > 0) {
			this.draggingScrollbar = true;
			scrollToMouse(mouseY);
			return;
		}

		int contentLeft = this.getX() + CONTENT_PAD;
		int contentRight = contentRight();
		int contentTop = this.getY() + CONTENT_PAD;
		int contentBottom = this.getBottom() - CONTENT_PAD;
		if (mouseX < contentLeft || mouseX >= contentRight || mouseY < contentTop || mouseY >= contentBottom) {
			return;
		}

		int y = contentTop - (int) Math.round(this.scrollAmount);
		for (Row row : this.rows) {
			int rowBottom = y + ROW_HEIGHT;
			if (mouseY >= y && mouseY < rowBottom) {
				int toggleX = contentRight - TOGGLE_WIDTH - 4;
				int toggleY = y + (ROW_HEIGHT - TOGGLE_HEIGHT) / 2;
				if (mouseX >= toggleX && mouseX < toggleX + TOGGLE_WIDTH
					&& mouseY >= toggleY && mouseY < toggleY + TOGGLE_HEIGHT
					&& this.actionsEnabled.getAsBoolean()) {
					row.toggle();
					this.playDownSound(this.minecraft.getSoundManager());
				}
				return;
			}
			y += ROW_HEIGHT + ROW_GAP;
		}
	}

	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dragX, final double dragY) {
		if (this.draggingScrollbar && maxScroll() > 0) {
			scrollToMouse(event.y());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public void onRelease(final MouseButtonEvent event) {
		this.draggingScrollbar = false;
		super.onRelease(event);
	}

	@Override
	public void playDownSound(final SoundManager soundManager) {
		AbstractWidget.playButtonClickSound(soundManager);
	}

	@Override
	protected void updateWidgetNarration(final NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}

	private void renderScrollbar(final GuiGraphicsExtractor graphics) {
		int max = maxScroll();
		if (max <= 0) {
			return;
		}
		int trackLeft = this.getRight() - SCROLLBAR_PAD - SCROLLBAR_WIDTH;
		int trackTop = this.getY() + CONTENT_PAD;
		int trackBottom = this.getBottom() - CONTENT_PAD;
		int trackHeight = Math.max(1, trackBottom - trackTop);
		graphics.fill(trackLeft, trackTop, trackLeft + SCROLLBAR_WIDTH, trackBottom, 0xFF2A2A2A);

		int thumbHeight = Math.max(16, (int) Math.round((double) trackHeight * trackHeight / (trackHeight + max)));
		int thumbTravel = Math.max(1, trackHeight - thumbHeight);
		int thumbTop = trackTop + (int) Math.round(this.scrollAmount / max * thumbTravel);
		graphics.fill(trackLeft, thumbTop, trackLeft + SCROLLBAR_WIDTH, thumbTop + thumbHeight, 0xFF8A8A8A);
		graphics.fill(trackLeft, thumbTop, trackLeft + 1, thumbTop + thumbHeight, 0xFFB0B0B0);
	}

	private void scrollToMouse(final double mouseY) {
		int trackTop = this.getY() + CONTENT_PAD;
		int trackBottom = this.getBottom() - CONTENT_PAD;
		int trackHeight = Math.max(1, trackBottom - trackTop);
		int max = maxScroll();
		if (max <= 0) {
			this.scrollAmount = 0.0D;
			return;
		}
		int thumbHeight = Math.max(16, (int) Math.round((double) trackHeight * trackHeight / (trackHeight + max)));
		int thumbTravel = Math.max(1, trackHeight - thumbHeight);
		double relative = (mouseY - trackTop - thumbHeight / 2.0D) / thumbTravel;
		this.scrollAmount = Mth.clamp(relative * max, 0.0D, max);
	}

	private boolean isOverScrollbar(final double mouseX, final double mouseY) {
		int trackLeft = this.getRight() - SCROLLBAR_PAD - SCROLLBAR_WIDTH - 2;
		return mouseX >= trackLeft
			&& mouseX < this.getRight() - 1
			&& mouseY >= this.getY() + CONTENT_PAD
			&& mouseY < this.getBottom() - CONTENT_PAD;
	}

	private int contentRight() {
		return this.getRight() - CONTENT_PAD - (maxScroll() > 0 ? SCROLLBAR_WIDTH + SCROLLBAR_PAD + 2 : 0);
	}

	private int contentHeight() {
		if (this.rows.isEmpty()) {
			return 0;
		}
		return this.rows.size() * ROW_HEIGHT + Math.max(0, this.rows.size() - 1) * ROW_GAP;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - Math.max(0, this.getHeight() - CONTENT_PAD * 2));
	}

	private void clampScroll() {
		this.scrollAmount = Mth.clamp(this.scrollAmount, 0.0D, maxScroll());
	}

	private final class Row {
		private final BiomeSample marker;
		private final Button toggle;

		private Row(final BiomeSample marker) {
			this.marker = marker;
			this.toggle = Button.builder(toggleMessage(marker), button -> {
			}).bounds(0, 0, TOGGLE_WIDTH, TOGGLE_HEIGHT).build();
		}

		private void toggle() {
			BiomeToggleList.this.flip.accept(this.marker);
			BiomeToggleList.this.onChange.run();
			this.toggle.setMessage(toggleMessage(this.marker));
		}

		private void render(
			final GuiGraphicsExtractor graphics,
			final int x,
			final int y,
			final int width,
			final int index,
			final boolean hovered,
			final int mouseX,
			final int mouseY,
			final float partialTick
		) {
			int right = x + width;
			int bottom = y + ROW_HEIGHT;
			int background = hovered ? 0x70474747 : (index & 1) == 0 ? 0x301C1C1C : 0x40242424;
			graphics.fill(x, y, right, bottom, background);
			if (BiomeToggleList.this.actionLabel == null) {
				boolean enabled = BiomeToggleList.this.isOn.test(this.marker);
				graphics.fill(x, y + 2, x + 2, bottom - 2, enabled ? 0xFF55AA55 : 0xFF555555);
			}

			int swatchX = x + 7;
			int swatchY = y + (ROW_HEIGHT - 18) / 2;
			graphics.fill(swatchX, swatchY, swatchX + 18, swatchY + 18, 0xFF0A0A0A);
			graphics.fill(swatchX + 1, swatchY + 1, swatchX + 17, swatchY + 17,
				0xFF000000 | (this.marker.argb() & 0xFFFFFF));
			int toggleX = right - TOGGLE_WIDTH - 4;
			int toggleY = y + (ROW_HEIGHT - TOGGLE_HEIGHT) / 2;
			this.toggle.setMessage(toggleMessage(this.marker));
			this.toggle.active = BiomeToggleList.this.actionsEnabled.getAsBoolean();
			// Minecraft 26.2: setRectangle(width, height, x, y)
			this.toggle.setRectangle(TOGGLE_WIDTH, TOGGLE_HEIGHT, toggleX, toggleY);
			this.toggle.extractRenderState(graphics, mouseX, mouseY, partialTick);

			int nameX = x + 6 + ICON_SIZE + 8;
			int available = Math.max(0, toggleX - 8 - nameX);
			FormattedCharSequence name = clippedName(SeedAtlasXaeroIntegration.biomeDisplayName(this.marker.name()), available);
			int nameY = y + (ROW_HEIGHT - BiomeToggleList.this.minecraft.font.lineHeight) / 2;
			graphics.text(BiomeToggleList.this.minecraft.font, name, nameX, nameY, 0xFFFFFFFF);
		}

		private FormattedCharSequence clippedName(final Component name, final int availableWidth) {
			if (availableWidth <= 0) {
				return FormattedCharSequence.EMPTY;
			}
			if (BiomeToggleList.this.minecraft.font.width(name) <= availableWidth) {
				return name.getVisualOrderText();
			}
			String ellipsis = "...";
			int textWidth = Math.max(0, availableWidth - BiomeToggleList.this.minecraft.font.width(ellipsis));
			FormattedText clipped = BiomeToggleList.this.minecraft.font.substrByWidth(name, textWidth);
			return Language.getInstance().getVisualOrder(FormattedText.composite(clipped, FormattedText.of(ellipsis)));
		}
	}

	private Component toggleMessage(final BiomeSample marker) {
		return this.actionLabel != null ? this.actionLabel : SeedAtlasSettingsScreen.toggleValue(this.isOn.test(marker));
	}
}

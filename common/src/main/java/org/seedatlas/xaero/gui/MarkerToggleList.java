package org.seedatlas.xaero.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.icon.CompletedBadge;
import org.seedatlas.xaero.integration.icon.StructureIcons;

/**
 * Compact, Xaero-like structure selector, with a switch or a one-shot button per row.
 *
 * <p>Minecraft 26.2 changed selection-list layout enough that the stock
 * ContainerObjectSelectionList produced broken hitboxes and a dead scrollbar.
 * This widget owns its own clipping, wheel scrolling and row layout.</p>
 */
final class MarkerToggleList extends AbstractWidget {
	private static final int ROW_HEIGHT = 28;
	private static final int ROW_GAP = 2;
	private static final int CONTENT_PAD = 6;
	private static final int ICON_SIZE = 20;
	private static final int TOGGLE_WIDTH = 56;
	private static final int TOGGLE_HEIGHT = 20;
	private static final int RESET_WIDTH = 40;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int SCROLLBAR_PAD = 3;

	private final Minecraft minecraft;
	private final List<Row> allRows = new ArrayList<>();
	private List<Row> rows = this.allRows;
	private final Predicate<MarkerType> isOn = m -> SeedAtlasClientState.config().markerEnabled(m.id());
	private Consumer<MarkerType> flip = m ->
		SeedAtlasClientState.setMarkerEnabled(m.id(), !SeedAtlasClientState.config().markerEnabled(m.id()));
	/** Set in button mode: the label every row's button shows instead of its on/off state. */
	private Component actionLabel;
	private BooleanSupplier actionsEnabled = () -> true;
	/** Set when every row also offers to reset its completed structures. */
	private ToIntFunction<MarkerType> completedCount;
	private Consumer<MarkerType> resetCompleted;
	private double scrollAmount;
	private boolean draggingScrollbar;

	MarkerToggleList(final Minecraft minecraft, final int x, final int y, final int width, final int height) {
		this(minecraft, x, y, width, height, MarkerType.all());
	}

	/** One-shot buttons instead of switches: each press runs the action, and nothing stays selected. */
	void useAction(final Component label, final BooleanSupplier enabled, final Consumer<MarkerType> action) {
		this.actionLabel = label;
		this.actionsEnabled = enabled;
		this.flip = action;
	}

	/** Adds a button left of each switch that resets the completed structures of that type. */
	void useReset(final ToIntFunction<MarkerType> count, final Consumer<MarkerType> reset) {
		this.completedCount = count;
		this.resetCompleted = reset;
	}

	MarkerToggleList(final Minecraft minecraft, final int x, final int y, final int width, final int height, final List<MarkerType> types) {
		super(x, y, width, height, Component.translatable("screen.seedatlas_xaero.markers"));
		this.minecraft = minecraft;
		for (MarkerType marker : types) {
			this.allRows.add(new Row(marker));
		}
	}

	void setFilter(final String query) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		this.rows = this.allRows.stream().filter(r -> q.isEmpty()
			|| r.marker.displayName().getString().toLowerCase(Locale.ROOT).contains(q)
			|| r.marker.id().contains(q)).toList();
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
				int resetX = toggleX - RESET_WIDTH - 4;
				if (this.resetCompleted != null && mouseX >= resetX && mouseX < resetX + RESET_WIDTH
					&& mouseY >= toggleY && mouseY < toggleY + TOGGLE_HEIGHT
					&& this.completedCount.applyAsInt(row.marker) > 0) {
					this.playDownSound(this.minecraft.getSoundManager());
					this.resetCompleted.accept(row.marker);
					return;
				}
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
		private final MarkerType marker;
		private final Button toggle;
		private final Button reset;

		private Row(final MarkerType marker) {
			this.marker = marker;
			this.toggle = Button.builder(toggleMessage(marker), button -> {
			}).bounds(0, 0, TOGGLE_WIDTH, TOGGLE_HEIGHT).build();
			this.reset = Button.builder(Component.empty(), button -> {
			}).bounds(0, 0, RESET_WIDTH, TOGGLE_HEIGHT).build();
		}

		private void toggle() {
			MarkerToggleList.this.flip.accept(this.marker);
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
			if (MarkerToggleList.this.actionLabel == null) {
				boolean enabled = MarkerToggleList.this.isOn.test(this.marker);
				graphics.fill(x, y + 2, x + 2, bottom - 2, enabled ? 0xFF55AA55 : 0xFF555555);
			}

			var structureIcon = StructureIcons.get(this.marker.id());
			var layout = structureIcon.layout();
			int iconX = x + 6 + (ICON_SIZE - layout.displayWidth()) / 2;
			int iconY = y + (ROW_HEIGHT - layout.displayHeight()) / 2;
			graphics.blit(
				RenderPipelines.GUI_TEXTURED,
				structureIcon.texture(),
				iconX,
				iconY,
				(float) layout.u(),
				(float) layout.v(),
				layout.displayWidth(),
				layout.displayHeight(),
				layout.width(),
				layout.height(),
				layout.textureWidth(),
				layout.textureHeight()
			);
			int toggleX = right - TOGGLE_WIDTH - 4;
			int toggleY = y + (ROW_HEIGHT - TOGGLE_HEIGHT) / 2;
			this.toggle.setMessage(toggleMessage(this.marker));
			this.toggle.active = MarkerToggleList.this.actionsEnabled.getAsBoolean();
			// Minecraft 26.2: setRectangle(width, height, x, y)
			this.toggle.setRectangle(TOGGLE_WIDTH, TOGGLE_HEIGHT, toggleX, toggleY);
			this.toggle.extractRenderState(graphics, mouseX, mouseY, partialTick);

			int nameRight = toggleX;
			if (MarkerToggleList.this.resetCompleted != null) {
				int completed = MarkerToggleList.this.completedCount.applyAsInt(this.marker);
				int resetX = toggleX - RESET_WIDTH - 4;
				this.reset.active = completed > 0;
				this.reset.setRectangle(RESET_WIDTH, TOGGLE_HEIGHT, resetX, toggleY);
				this.reset.extractRenderState(graphics, mouseX, mouseY, partialTick);
				// The completed badge and how many of this type are completed.
				var font = MarkerToggleList.this.minecraft.font;
				String count = Integer.toString(completed);
				int contentWidth = CompletedBadge.SOURCE_SIZE + 3 + font.width(count);
				int badgeX = resetX + (RESET_WIDTH - contentWidth) / 2;
				int badgeY = toggleY + (TOGGLE_HEIGHT - CompletedBadge.SOURCE_SIZE) / 2;
				graphics.blit(RenderPipelines.GUI_TEXTURED, CompletedBadge.TEXTURE, badgeX, badgeY, 0.0F, 0.0F,
					CompletedBadge.SOURCE_SIZE, CompletedBadge.SOURCE_SIZE, CompletedBadge.SOURCE_SIZE, CompletedBadge.SOURCE_SIZE);
				graphics.text(font, count, badgeX + CompletedBadge.SOURCE_SIZE + 3,
					toggleY + (TOGGLE_HEIGHT - font.lineHeight) / 2 + 1, completed > 0 ? 0xFFFFFFFF : 0xFFA0A0A0);
				if (completed == 0) {
					graphics.fill(badgeX, badgeY, badgeX + CompletedBadge.SOURCE_SIZE, badgeY + CompletedBadge.SOURCE_SIZE, 0x90303030);
				}
				if (completed > 0 && mouseX >= resetX && mouseX < resetX + RESET_WIDTH
					&& mouseY >= toggleY && mouseY < toggleY + TOGGLE_HEIGHT) {
					graphics.setTooltipForNextFrame(Component.translatable(
						"tooltip.seedatlas_xaero.reset_completed_type", this.marker.displayName()), mouseX, mouseY);
				}
				nameRight = resetX;
			}

			int nameX = x + 6 + ICON_SIZE + 8;
			int available = Math.max(0, nameRight - 8 - nameX);
			FormattedCharSequence name = clippedName(this.marker.displayName(), available);
			int nameY = y + (ROW_HEIGHT - MarkerToggleList.this.minecraft.font.lineHeight) / 2;
			graphics.text(MarkerToggleList.this.minecraft.font, name, nameX, nameY, 0xFFFFFFFF);
		}

		private FormattedCharSequence clippedName(final Component name, final int availableWidth) {
			if (availableWidth <= 0) {
				return FormattedCharSequence.EMPTY;
			}
			if (MarkerToggleList.this.minecraft.font.width(name) <= availableWidth) {
				return name.getVisualOrderText();
			}
			String ellipsis = "...";
			int textWidth = Math.max(0, availableWidth - MarkerToggleList.this.minecraft.font.width(ellipsis));
			FormattedText clipped = MarkerToggleList.this.minecraft.font.substrByWidth(name, textWidth);
			return Language.getInstance().getVisualOrder(FormattedText.composite(clipped, FormattedText.of(ellipsis)));
		}
	}

	private Component toggleMessage(final MarkerType marker) {
		return this.actionLabel != null ? this.actionLabel : SeedAtlasSettingsScreen.toggleValue(this.isOn.test(marker));
	}
}

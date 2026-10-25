package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.config.SeedAtlasConfig;

/** Scrollable, compact marker selector using Seed Atlas' original icons. */
public final class MarkerSettingsScreen extends SeedAtlasScreen {
	private static final Component TITLE = Component.translatable("screen.seedatlas_xaero.markers");
	private static final int HEADER_HEIGHT = 68;
	private static final int FOOTER_HEIGHT = 38;
	private static final int MAX_LIST_WIDTH = 420;
	private static final int MIN_LIST_WIDTH = 180;

	public MarkerSettingsScreen(final Screen parent) {
		super(TITLE, parent);
	}

	@Override
	protected void init() {
		int horizontalMargin = Math.min(16, Math.max(6, this.width / 18));
		int listWidth = Math.min(MAX_LIST_WIDTH, Math.max(MIN_LIST_WIDTH, this.width - horizontalMargin * 2));
		listWidth = Math.min(listWidth, this.width - 8);
		int listLeft = (this.width - listWidth) / 2;
		int listTop = HEADER_HEIGHT;
		int listBottom = Math.max(listTop + 48, this.height - FOOTER_HEIGHT);
		listBottom = Math.min(listBottom, this.height - 26);
		int listHeight = Math.max(48, listBottom - listTop);

		int headerGap = 6;
		int headerThird = (listWidth - headerGap * 2) / 3;
		this.addRenderableWidget(new CompletedOpacitySlider(listLeft, 40, headerThird,
			SeedAtlasClientState.config().completedOpacity()));

		this.addRenderableWidget(Button.builder(markerSizeMessage(), button -> {
			SeedAtlasClientState.setMarkerSize(SeedAtlasConfig.nextMarkerSize(SeedAtlasClientState.config().markerSize()));
			button.setMessage(markerSizeMessage());
		}).bounds(listLeft + headerThird + headerGap, 40, headerThird, 20).build());

		int completed = SeedAtlasClientState.completedCount(null);
		Button resetAll = this.addRenderableWidget(Button.builder(
			Component.translatable("options.seedatlas_xaero.reset_completed", completed), button -> confirmReset(null))
			.bounds(listLeft + (headerThird + headerGap) * 2, 40, listWidth - (headerThird + headerGap) * 2, 20)
			.tooltip(Tooltip.create(Component.translatable("tooltip.seedatlas_xaero.reset_completed"))).build());
		resetAll.active = completed > 0;

		MarkerToggleList list = new MarkerToggleList(this.minecraft, listLeft, listTop, listWidth, listHeight);
		list.useReset(SeedAtlasClientState::completedCount, this::confirmReset);
		this.addRenderableWidget(list);

		int doneWidth = Math.min(200, Math.max(120, this.width - 24));
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - doneWidth) / 2, Math.max(0, this.height - 28), doneWidth, 20)
			.build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		Component status = Component.translatable(
			SeedAtlasClientState.config().structuresEnabled()
			? "screen.seedatlas_xaero.markers.enabled"
			: "screen.seedatlas_xaero.markers.disabled"
		);
		graphics.centeredText(this.font, status, this.width / 2, 23, 0xFFB8B8B8);
	}


	/** Asks before marking the completed structures of one type, or ({@code null}) all, as not completed. */
	private void confirmReset(final MarkerType type) {
		int count = SeedAtlasClientState.completedCount(type);
		if (count == 0) return;
		Component world = Component.literal(SeedAtlasClientState.currentContextLabel());
		Component message = type == null
			? Component.translatable("screen.seedatlas_xaero.reset_completed.all", count, world)
			: Component.translatable("screen.seedatlas_xaero.reset_completed.type", count, type.displayName(), world);
		this.minecraft.gui.setScreen(new ConfirmResetScreen(this,
			Component.translatable("screen.seedatlas_xaero.reset_completed.title"), message,
			() -> SeedAtlasClientState.resetCompleted(type)));
	}

	private static Component markerSizeMessage() {
		return Component.translatable("options.seedatlas_xaero.marker_size",
			SeedAtlasClientState.config().markerSize() + " %");
	}

	@Override
	public void onClose() {
		SeedAtlasClientState.save();
		// Parent is the main Seed Atlas settings screen; keep heavy work paused.
		this.minecraft.gui.setScreen(this.parent);
	}

	/** Fades completed structures from 100 % (like any marker) down to 0 % (hidden), previewed live on the map. */
	private static final class CompletedOpacitySlider extends AbstractSliderButton {
		private CompletedOpacitySlider(final int x, final int y, final int width, final int percent) {
			super(x, y, width, 20, Component.empty(), percent / 100.0D);
			this.setTooltip(Tooltip.create(Component.translatable("tooltip.seedatlas_xaero.completed_opacity")));
			this.updateMessage();
		}

		private int percent() {
			return (int) Math.round(this.value * 100.0D);
		}

		@Override
		protected void updateMessage() {
			int percent = this.percent();
			this.setMessage(Component.translatable("options.seedatlas_xaero.completed_opacity", percent == 0
				? Component.translatable("options.seedatlas_xaero.completed_opacity.hidden")
				: Component.literal(percent + " %")));
		}

		@Override
		protected void applyValue() {
			SeedAtlasClientState.setCompletedOpacity(this.percent(), false);
		}

		@Override
		public void onRelease(final MouseButtonEvent event) {
			super.onRelease(event);
			SeedAtlasClientState.save();
		}
	}
}

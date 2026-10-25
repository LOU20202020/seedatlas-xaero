package org.seedatlas.xaero.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.seedatlas.xaero.command.SeedAtlasCommands;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;

/**
 * World type and the Minecraft version the world or server generates with, e.g. 26.2 for a 26.2
 * server joined from a 26.3 client. Versions are listed as spans with identical world generation;
 * the choice belongs to the current world or server only.
 */
public final class WorldGenerationScreen extends SeedAtlasScreen {
	private static final Component TITLE = Component.translatable("screen.seedatlas_xaero.world_generation");
	private static final int LIST_TOP = 74;

	public WorldGenerationScreen(final Screen parent) {
		super(TITLE, parent);
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(340, this.width - 20);
		int left = (this.width - contentWidth) / 2;

		this.addRenderableWidget(Button.builder(worldTypeMessage(SeedAtlasClientState.config().largeBiomes()), button -> {
			boolean large = !SeedAtlasClientState.config().largeBiomes();
			SeedAtlasClientState.setLargeBiomes(large);
			button.setMessage(worldTypeMessage(large));
		}).bounds(left, 34, contentWidth, 20).build());

		int listBottom = Math.max(LIST_TOP + 40, this.height - 50);
		this.addRenderableWidget(new VersionList(left, LIST_TOP, contentWidth, listBottom - LIST_TOP));

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 28, 200, 20)
			.build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
		Component heading = SeedAtlasClientState.currentContextKey().isEmpty()
			? Component.translatable("screen.seedatlas_xaero.game_version.no_context")
			: Component.translatable("screen.seedatlas_xaero.game_version", SeedAtlasCommands.gameVersionName());
		graphics.centeredText(this.font, heading, this.width / 2, 62, 0xFFA0A0A0);
		Component note = SeedAtlasNative.supports(GameVersion.ALL.getLast())
			? Component.translatable("screen.seedatlas_xaero.game_version.note")
			: Component.translatable("screen.seedatlas_xaero.game_version.unsupported", GameVersion.NEWEST.name());
		graphics.centeredText(this.font, note, this.width / 2, this.height - 42, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		SeedAtlasClientState.save();
		// Parent is the main Seed Atlas settings screen; keep heavy work paused.
		this.minecraft.gui.setScreen(this.parent);
	}

	static Component worldTypeMessage(final boolean largeBiomes) {
		return Component.translatable(
			"options.seedatlas_xaero.world_type",
			Component.translatable(largeBiomes ? "options.seedatlas_xaero.world_type.large" : "options.seedatlas_xaero.world_type.normal")
		);
	}

	/** Automatic plus every generation span, newest first; clicking a row selects it. */
	private final class VersionList extends AbstractWidget {
		private static final int ROW_HEIGHT = 18;
		private static final int PAD = 4;
		private static final int SCROLLBAR_WIDTH = 4;
		/** null stands for following the running game. */
		private final List<GameVersion> rows = new ArrayList<>();
		private double scroll;

		private VersionList(final int x, final int y, final int width, final int height) {
			super(x, y, width, height, Component.translatable("screen.seedatlas_xaero.world_generation"));
			this.rows.add(null);
			this.rows.addAll(GameVersion.ALL);
			this.active = !SeedAtlasClientState.currentContextKey().isEmpty();
			// Show the selection without scrolling for it.
			int selected = this.rows.indexOf(SeedAtlasClientState.gameVersionAutomatic() ? null
				: SeedAtlasClientState.activeGameVersion());
			this.scroll = Mth.clamp((selected - 2) * (double) ROW_HEIGHT, 0.0D, maxScroll());
		}

		private int maxScroll() {
			return Math.max(0, this.rows.size() * ROW_HEIGHT - (this.getHeight() - PAD * 2));
		}

		private boolean selectable(final GameVersion version) {
			return this.active && (version == null || SeedAtlasNative.supports(version));
		}

		private boolean selected(final GameVersion version) {
			boolean automatic = SeedAtlasClientState.gameVersionAutomatic();
			return version == null ? automatic : !automatic && version.equals(SeedAtlasClientState.activeGameVersion());
		}

		@Override
		protected void extractWidgetRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
			var font = WorldGenerationScreen.this.font;
			graphics.fill(this.getX(), this.getY(), this.getRight(), this.getBottom(), 0xD0121212);
			graphics.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), 0xFF5A5A5A);
			int left = this.getX() + PAD;
			int right = this.getRight() - PAD - (maxScroll() > 0 ? SCROLLBAR_WIDTH + 2 : 0);
			int top = this.getY() + PAD;
			int bottom = this.getBottom() - PAD;
			graphics.enableScissor(left, top, right, bottom);
			int y = top - (int) Math.round(this.scroll);
			for (GameVersion version : this.rows) {
				if (y + ROW_HEIGHT > top && y < bottom) {
					boolean hovered = this.active && mouseX >= left && mouseX < right && mouseY >= Math.max(y, top)
						&& mouseY < Math.min(y + ROW_HEIGHT, bottom);
					boolean selected = selected(version);
					boolean enabled = selectable(version);
					if (selected) {
						graphics.fill(left, y, right, y + ROW_HEIGHT, 0x6038803A);
						graphics.fill(left, y, left + 2, y + ROW_HEIGHT, 0xFF55AA55);
					} else if (hovered && enabled) {
						graphics.fill(left, y, right, y + ROW_HEIGHT, 0x50474747);
					}
					Component label = version == null
						? Component.translatable("options.seedatlas_xaero.game_version.auto", GameVersion.NEWEST.name())
						: Component.literal(version.label());
					int textY = y + (ROW_HEIGHT - font.lineHeight) / 2 + 1;
					int color = !enabled ? 0xFF6A6A6A : selected ? 0xFF7CE07C : 0xFFFFFFFF;
					graphics.text(font, label, left + 8, textY, color);
					if (version != null && !version.hasTerrain()) {
						Component hint = Component.translatable("screen.seedatlas_xaero.game_version.no_terrain");
						graphics.text(font, hint, right - 6 - font.width(hint), textY, 0xFF8A8A8A);
					}
				}
				y += ROW_HEIGHT;
			}
			graphics.disableScissor();
			int max = maxScroll();
			if (max > 0) {
				int trackLeft = this.getRight() - PAD - SCROLLBAR_WIDTH;
				int trackHeight = bottom - top;
				int thumb = Math.max(16, trackHeight * trackHeight / (trackHeight + max));
				int thumbTop = top + (int) Math.round(this.scroll / max * (trackHeight - thumb));
				graphics.fill(trackLeft, top, trackLeft + SCROLLBAR_WIDTH, bottom, 0xFF2A2A2A);
				graphics.fill(trackLeft, thumbTop, trackLeft + SCROLLBAR_WIDTH, thumbTop + thumb, 0xFF8A8A8A);
			}
		}

		@Override
		public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
			if (!this.isMouseOver(mouseX, mouseY)) {
				return false;
			}
			this.scroll = Mth.clamp(this.scroll - scrollY * ROW_HEIGHT * 2, 0.0D, maxScroll());
			return true;
		}

		@Override
		public void onClick(final MouseButtonEvent event, final boolean doubleClick) {
			int top = this.getY() + PAD;
			if (event.y() < top || event.y() >= this.getBottom() - PAD) {
				return;
			}
			int index = (int) Math.floor((event.y() - top + this.scroll) / ROW_HEIGHT);
			if (index < 0 || index >= this.rows.size()) {
				return;
			}
			GameVersion version = this.rows.get(index);
			if (!selectable(version)) {
				return;
			}
			SeedAtlasClientState.setGameVersion(version);
			AbstractWidget.playButtonClickSound(WorldGenerationScreen.this.minecraft.getSoundManager());
		}

		@Override
		public void playDownSound(final SoundManager soundManager) {
			// Only a selected row clicks, see onClick.
		}

		@Override
		protected void updateWidgetNarration(final NarrationElementOutput output) {
			this.defaultButtonNarrationText(output);
		}
	}
}

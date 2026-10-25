package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import xaero.lib.client.gui.IScreenBase;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;
import xaero.map.gui.GuiMap;

/**
 * Base of the Seed Atlas menus. Opened from the world map, they draw the map behind them like Xaero's
 * own menus and ask World Map to skip the world (its "skip world render" option); otherwise every frame
 * redrew the whole world, shaders included, only to blur it, which ran the GPU at full load.
 */
abstract class SeedAtlasScreen extends Screen implements IScreenBase {
	/** What vanilla allows its menus outside a world; at an unlimited limit these ran at ~500 FPS. */
	private static final int MENU_FRAMERATE_LIMIT = 60;
	protected final Screen parent;

	protected SeedAtlasScreen(final Component title, final Screen parent) {
		super(title);
		this.parent = parent;
	}

	@Override
	public void added() {
		super.added();
		this.minecraft.getFramerateLimitTracker().setFramerateLimit(
			Math.min(this.minecraft.options.framerateLimit().get(), MENU_FRAMERATE_LIMIT));
		SeedAtlasXaeroIntegration.setMapBehindMenu(mapBehind() != null);
	}

	@Override
	public void removed() {
		this.minecraft.getFramerateLimitTracker().setFramerateLimit(this.minecraft.options.framerateLimit().get());
		super.removed();
	}

	/** The world map this menu was opened from; null when it was opened by command. */
	private Screen mapBehind() {
		Screen screen = this.parent;
		while (screen instanceof SeedAtlasScreen menu) {
			screen = menu.parent;
		}
		return screen instanceof GuiMap ? screen : null;
	}

	@Override
	public boolean shouldSkipWorldRender() {
		return mapBehind() != null;
	}

	@Override
	public Screen getEscape() {
		return mapBehind();
	}

	@Override
	public void onDropdownOpen(final DropDownWidget menu) {
	}

	@Override
	public void onDropdownClosed(final DropDownWidget menu) {
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		Screen map = mapBehind();
		if (map != null) {
			if (map.width != this.width || map.height != this.height) {
				map.init(this.width, this.height);
			}
			// The cursor stays off the map, so nothing on it reacts behind the menu.
			map.extractRenderStateWithTooltipAndSubtitles(graphics, -1, -1, a);
			// Map terrain is drawn into its own target, so vanilla's world blur cannot dim it.
			// Keep menu text readable over bright terrain, especially snow.
			graphics.fill(0, 0, this.width, this.height, 0x88000000);
		} else {
			super.extractBackground(graphics, mouseX, mouseY, a);
		}
	}
}

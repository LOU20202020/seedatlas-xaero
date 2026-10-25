package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Yes/no question over the world map, like the other Seed Atlas menus, instead of the world behind it. */
final class ConfirmResetScreen extends SeedAtlasScreen {
	private final Component message;
	private final Runnable onConfirm;

	ConfirmResetScreen(final Screen parent, final Component title, final Component message, final Runnable onConfirm) {
		super(title, parent);
		this.message = message;
		this.onConfirm = onConfirm;
	}

	@Override
	protected void init() {
		int y = this.height / 2 + 10;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_YES, button -> {
			this.onConfirm.run();
			this.onClose();
		}).bounds(this.width / 2 - 154, y, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_NO, button -> this.onClose())
			.bounds(this.width / 2 + 4, y, 150, 20).build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
		graphics.centeredText(this.font, this.message, this.width / 2, this.height / 2 - 14, 0xFFB8B8B8);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

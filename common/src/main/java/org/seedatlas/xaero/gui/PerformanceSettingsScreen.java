package org.seedatlas.xaero.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.config.SeedAtlasConfig;

/** Performance controls consumed by the asynchronous map renderer. */
public final class PerformanceSettingsScreen extends SeedAtlasScreen {
	private static final Component TITLE = Component.translatable("screen.seedatlas_xaero.performance");

	public PerformanceSettingsScreen(final Screen parent) {
		super(TITLE, parent);
	}

	@Override
	protected void init() {
		SeedAtlasConfig config = SeedAtlasClientState.config();
		int contentWidth = Math.min(340, this.width - 20);
		int left = (this.width - contentWidth) / 2;

		this.addRenderableWidget(Button.builder(resolutionMessage(config.biomeResolution()), button -> {
			int next = nextValue(SeedAtlasClientState.config().biomeResolution(), SeedAtlasConfig.BIOME_RESOLUTION_STEPS);
			SeedAtlasClientState.setBiomeResolution(next);
			button.setMessage(resolutionMessage(next));
		}).bounds(left, 36, contentWidth, 20).build());

		this.addRenderableWidget(Button.builder(workerMessage(config.workerThreads()), button -> {
			int next = nextValue(SeedAtlasClientState.config().workerThreads(), SeedAtlasConfig.WORKER_THREAD_STEPS);
			SeedAtlasClientState.setWorkerThreads(next);
			button.setMessage(workerMessage(next));
		}).bounds(left, 61, contentWidth, 20).build());

		this.addRenderableWidget(Button.builder(prefetchMessage(config.prefetchRadius()), button -> {
			int next = nextValue(SeedAtlasClientState.config().prefetchRadius(), SeedAtlasConfig.PREFETCH_RADIUS_STEPS);
			SeedAtlasClientState.setPrefetchRadius(next);
			button.setMessage(prefetchMessage(next));
		}).bounds(left, 86, contentWidth, 20).build());

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 28, 200, 20)
			.build());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
		int contentWidth = Math.min(340, this.width - 20);
		int left = (this.width - contentWidth) / 2;
		graphics.textWithWordWrap(
			this.font,
			Component.translatable("screen.seedatlas_xaero.performance.description"),
			left,
			118,
			contentWidth,
			0xFFA0A0A0
		);
	}

	@Override
	public void onClose() {
		SeedAtlasClientState.save();
		// Parent is the main Seed Atlas settings screen; keep heavy work paused.
		this.minecraft.gui.setScreen(this.parent);
	}

	private static int nextValue(final int current, final int[] values) {
		for (int index = 0; index < values.length; index++) {
			if (values[index] == current) {
				return values[(index + 1) % values.length];
			}
		}
		return values[0];
	}

	private static Component resolutionMessage(final int resolution) {
		return Component.translatable(
			"options.seedatlas_xaero.biome_resolution",
			Component.translatable("options.seedatlas_xaero.biome_resolution." + resolution)
		);
	}

	private static Component workerMessage(final int workers) {
		Component value = workers == 0
			? Component.translatable("options.seedatlas_xaero.worker_threads.auto")
			: Component.literal(Integer.toString(workers));
		return Component.translatable("options.seedatlas_xaero.worker_threads", value);
	}

	private static Component prefetchMessage(final int radius) {
		return Component.translatable(
			"options.seedatlas_xaero.prefetch_radius",
			Component.translatable("options.seedatlas_xaero.prefetch_radius." + radius)
		);
	}
}
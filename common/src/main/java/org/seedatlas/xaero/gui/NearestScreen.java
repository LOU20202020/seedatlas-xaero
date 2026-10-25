package org.seedatlas.xaero.gui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.integration.MapNavigator;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.integration.marker.NearestFinder;
import org.seedatlas.xaero.nativeapi.BiomeSample;

/** Biome/structure list (same rows as the settings pages) whose buttons open the map at the nearest one. */
public final class NearestScreen extends SeedAtlasScreen {
	private static final int FOOTER_HEIGHT = 38;
	// Remembered for the session only.
	private static MapNavigator.WaypointMode waypointMode = MapNavigator.WaypointMode.TEMPORARY;
	private static boolean skipCompleted = true;

	private final boolean biomeMode;
	private ResourceKey<Level> level;
	private int px;
	private int pz;
	private boolean busy;
	private boolean noSeed;
	private Component status = Component.empty();

	public NearestScreen(final Screen parent, final boolean biomeMode) {
		super(Component.translatable(biomeMode ? "screen.seedatlas_xaero.go_biome" : "screen.seedatlas_xaero.go_structure"), parent);
		this.biomeMode = biomeMode;
	}

	@Override
	protected void init() {
		var player = this.minecraft.player;
		if (player == null) return;
		this.level = player.level().dimension();
		this.px = player.getBlockX();
		this.pz = player.getBlockZ();

		int margin = Math.min(16, Math.max(6, this.width / 18));
		int listWidth = Math.min(420, Math.max(180, this.width - margin * 2));
		listWidth = Math.min(listWidth, this.width - 8);
		int left = (this.width - listWidth) / 2;
		int top = 92;
		int bottom = Math.min(Math.max(top + 48, this.height - FOOTER_HEIGHT), this.height - 26);
		int listHeight = Math.max(48, bottom - top);

		int half = (listWidth - 6) / 2;
		this.addRenderableWidget(Button.builder(waypointMessage(), button -> {
			var modes = MapNavigator.WaypointMode.values();
			waypointMode = modes[(waypointMode.ordinal() + 1) % modes.length];
			button.setMessage(waypointMessage());
		}).bounds(left, 38, this.biomeMode ? listWidth : half, 20).build());
		if (!this.biomeMode) {
			this.addRenderableWidget(Button.builder(skipMessage(), button -> {
				skipCompleted = !skipCompleted;
				button.setMessage(skipMessage());
			}).bounds(left + half + 6, 38, listWidth - half - 6, 20).build());
		}

		Component find = Component.translatable("options.seedatlas_xaero.nearest_find");
		List<BiomeSample> available = SeedAtlasXaeroIntegration.availableBiomes();
		this.noSeed = available.isEmpty();
		if (!this.noSeed && this.biomeMode) {
			List<BiomeSample> biomes = available.stream()
				.sorted(Comparator.comparing(b -> SeedAtlasXaeroIntegration.biomeDisplayName(b.name()).getString()))
				.toList();
			BiomeToggleList list = new BiomeToggleList(this.minecraft, left, top, listWidth, listHeight, biomes, () -> { });
			list.useAction(find, () -> !this.busy, this::findBiome);
			EditBox search = new EditBox(this.font, left, 64, listWidth, 20, Component.translatable("options.seedatlas_xaero.biome_search"));
			search.setHint(Component.translatable("options.seedatlas_xaero.biome_search"));
			search.setResponder(list::setFilter);
			this.addRenderableWidget(search);
			this.addRenderableWidget(list);
		} else if (!this.noSeed) {
			List<MarkerType> types = MarkerType.all().stream()
				.filter(t -> NearestFinder.appliesTo(t, this.level)).toList();
			MarkerToggleList list = new MarkerToggleList(this.minecraft, left, top, listWidth, listHeight, types);
			list.useAction(find, () -> !this.busy, this::findStructure);
			EditBox search = new EditBox(this.font, left, 64, listWidth, 20, Component.translatable("options.seedatlas_xaero.structure_search"));
			search.setHint(Component.translatable("options.seedatlas_xaero.structure_search"));
			search.setResponder(list::setFilter);
			this.addRenderableWidget(search);
			this.addRenderableWidget(list);
		}

		int doneWidth = Math.min(200, Math.max(120, this.width - 24));
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - doneWidth) / 2, Math.max(0, this.height - 28), doneWidth, 20).build());
	}

	private static Component waypointMessage() {
		return Component.translatable("options.seedatlas_xaero.nearest_waypoint",
			Component.translatable("options.seedatlas_xaero.nearest_waypoint." + waypointMode.name().toLowerCase(Locale.ROOT)));
	}

	private static Component skipMessage() {
		return Component.translatable("options.seedatlas_xaero.nearest_skip_completed",
			SeedAtlasSettingsScreen.toggleValue(skipCompleted));
	}

	private void findBiome(final BiomeSample biome) {
		ResourceKey<Level> level = this.level;
		int x = this.px;
		int z = this.pz;
		search(SeedAtlasXaeroIntegration.biomeDisplayName(biome.name()).getString(),
			() -> NearestFinder.biome(biome.id(), level, x, z));
	}

	private void findStructure(final MarkerType type) {
		ResourceKey<Level> level = this.level;
		int x = this.px;
		int z = this.pz;
		boolean skip = skipCompleted;
		search(type.displayName().getString(), () -> NearestFinder.structure(type, level, x, z, skip));
	}

	private void search(final String name, final Supplier<NearestFinder.Result> finder) {
		if (this.busy) return;
		this.busy = true;
		this.status = Component.translatable("message.seedatlas_xaero.nearest_searching", name);
		Minecraft mc = this.minecraft;
		Thread thread = new Thread(() -> {
			NearestFinder.Result result = null;
			try {
				result = finder.get();
			} catch (RuntimeException | LinkageError ignored) {
				// Reported as "not found" below.
			}
			NearestFinder.Result found = result;
			mc.execute(() -> finish(name, found));
		}, "seedatlas-nearest");
		thread.setDaemon(true);
		thread.start();
	}

	private void finish(final String name, final NearestFinder.Result result) {
		this.busy = false;
		if (this.minecraft.gui.screen() != this) return;
		if (result == null) {
			this.status = Component.translatable("message.seedatlas_xaero.nearest_none", name);
		} else if (!MapNavigator.open(result.x(), result.z(), name, waypointMode)) {
			this.status = Component.translatable("message.seedatlas_xaero.nearest_map");
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(this.font, this.status, this.width / 2, 24, 0xFFB8B8B8);
		if (this.noSeed) {
			graphics.centeredText(this.font, Component.translatable("screen.seedatlas_xaero.no_seed"), this.width / 2, 100, 0xFFB8B8B8);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}

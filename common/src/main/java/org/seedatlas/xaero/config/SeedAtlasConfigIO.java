package org.seedatlas.xaero.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.seedatlas.xaero.nativeapi.GameVersion;

final class SeedAtlasConfigIO {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private SeedAtlasConfigIO() {
	}

	static SeedAtlasConfig load(final Path path) throws IOException {
		SeedAtlasConfig config = new SeedAtlasConfig();
		if (!Files.isRegularFile(path)) {
			return config;
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			JsonElement rootElement = JsonParser.parseReader(reader);
			if (!rootElement.isJsonObject()) {
				throw new IOException("Seed Atlas config root must be a JSON object");
			}

			JsonObject root = rootElement.getAsJsonObject();
			int loadedVersion = integer(root, "version", 1);
			JsonObject layer = object(root, "layer");
			config.setLayerEnabled(bool(layer, "enabled", config.layerEnabled()));
			int loadedOpacity = integer(layer, "opacity", config.opacity());
			// Version 2's 75% default exposed Xaero's black/unexplored background and
			// produced a bright circle around locally loaded chunks. Migrate only that
			// exact legacy default; every other custom opacity remains untouched.
			config.setOpacity(loadedVersion <= 2 && loadedOpacity == 191 ? SeedAtlasConfig.DEFAULT_OPACITY : loadedOpacity);

			JsonObject structures = object(root, "structures");
			config.setStructuresEnabled(bool(structures, "enabled", config.structuresEnabled()));
			// Version 7 and older only had a switch that hid completed structures.
			config.setCompletedOpacity(integer(structures, "completedOpacity",
				bool(structures, "hideCompleted", false) ? 0 : SeedAtlasConfig.DEFAULT_COMPLETED_OPACITY));
			JsonElement completed = structures.get("completed");
			if (completed != null && completed.isJsonArray()) {
				for (JsonElement entry : completed.getAsJsonArray()) {
					if (!entry.isJsonObject() || !entry.getAsJsonObject().has("seed")
						|| !entry.getAsJsonObject().has("x") || !entry.getAsJsonObject().has("z")) continue;
					try {
						config.restoreCompleted(GSON.fromJson(entry, SeedAtlasConfig.StructureKey.class));
					} catch (RuntimeException ignored) {
						// A malformed marker must not discard other saved settings or progress.
					}
				}
			}

			JsonObject performance = object(root, "performance");
			int loadedBiomeResolution = integer(
				performance, "biomeResolution", config.biomeResolution());
			// The pre-batch renderer used 4x as a defensive default because every
			// Xaero leaf caused hundreds of native calls. ABI 3 renders a whole
			// raster per call, so migrate that exact legacy default to full detail.
			config.setBiomeResolution(
				loadedVersion <= 3 && loadedBiomeResolution == 4
					? SeedAtlasConfig.DEFAULT_BIOME_RESOLUTION
					: loadedBiomeResolution);
			config.setWorkerThreads(integer(performance, "workerThreads", config.workerThreads()));
			int loadedPrefetch = integer(performance, "prefetchRadius", config.prefetchRadius());
			// Prefetch no longer runs during normal gameplay. Migrate the old
			// default of 2 down to 1 so map-open prefetch stays light.
			if (loadedVersion <= 4 && loadedPrefetch == 2) {
				loadedPrefetch = SeedAtlasConfig.DEFAULT_PREFETCH_RADIUS;
			}
			config.setPrefetchRadius(loadedPrefetch);

			JsonObject generation = object(root, "worldGeneration");
			config.setLargeBiomes(bool(generation, "largeBiomes", config.largeBiomes()));

			JsonObject display = object(root, "display");
			config.setMarkerSize(integer(display, "markerSize", config.markerSize()));
			config.setTerrainStyle(bool(display, "terrainStyle", config.terrainStyle()));
			config.setOverlayExplored(bool(display, "overlayExplored", config.overlayExplored()));

			JsonObject highlight = object(root, "biomeHighlight");
			config.setBiomeHighlightEnabled(
				bool(highlight, "enabled", config.biomeHighlightEnabled()));
			JsonElement highlightedBiomes = highlight.get("biomes");
			if (highlightedBiomes != null && highlightedBiomes.isJsonArray()) {
				for (JsonElement entry : highlightedBiomes.getAsJsonArray()) {
					if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isNumber()) {
						config.addHighlightedBiome(entry.getAsInt());
					}
				}
			}

			JsonObject heights = object(root, "biomeSampleY");
			config.setOverworldY(integer(heights, "overworld", config.overworldY()));
			config.setNetherY(integer(heights, "nether", config.netherY()));
			config.setEndY(integer(heights, "end", config.endY()));

			JsonObject markers = object(root, "markers");
			for (MarkerType marker : MarkerType.all()) {
				config.setMarkerEnabled(marker.id(), bool(markers, marker.id(), marker.enabledByDefault()));
			}

			JsonObject profiles = object(root, "seeds");
			for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
				if (entry.getKey().isBlank() || !entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject profile = entry.getValue().getAsJsonObject();
				JsonObject dimensions = object(profile, "dimensions");
				SeedAtlasConfig.SeedProfile loaded = new SeedAtlasConfig.SeedProfile(
					seedValue(profile), seedValue(object(dimensions, "nether")), seedValue(object(dimensions, "end")),
					string(profile, "label", ""));
				if (!loaded.isEmpty()) {
					config.putSeedProfile(entry.getKey(), loaded);
				}
			}

			JsonObject versions = object(root, "gameVersions");
			for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
				String version = entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
					? entry.getValue().getAsString() : null;
				if (!entry.getKey().isBlank() && GameVersion.parse(version).isPresent()) {
					config.setGameVersion(entry.getKey(), GameVersion.parse(version).get().name());
				}
			}
			return config;
		} catch (RuntimeException exception) {
			throw new IOException("Invalid Seed Atlas config JSON", exception);
		}
	}

	static void save(final Path path, final SeedAtlasConfig config) throws IOException {
		Path directory = path.getParent();
		if (directory != null) {
			Files.createDirectories(directory);
		}

		JsonObject root = new JsonObject();
		root.addProperty("version", SeedAtlasConfig.CURRENT_VERSION);

		JsonObject layer = new JsonObject();
		layer.addProperty("enabled", config.layerEnabled());
		layer.addProperty("opacity", config.opacity());
		root.add("layer", layer);

		JsonObject structures = new JsonObject();
		structures.addProperty("enabled", config.structuresEnabled());
		structures.addProperty("completedOpacity", config.completedOpacity());
		// Older releases read only this switch.
		structures.addProperty("hideCompleted", config.hideCompletedStructures());
		structures.add("completed", GSON.toJsonTree(config.completedSnapshot()));
		root.add("structures", structures);

		JsonObject performance = new JsonObject();
		performance.addProperty("biomeResolution", config.biomeResolution());
		performance.addProperty("workerThreads", config.workerThreads());
		performance.addProperty("prefetchRadius", config.prefetchRadius());
		root.add("performance", performance);

		JsonObject generation = new JsonObject();
		generation.addProperty("largeBiomes", config.largeBiomes());
		root.add("worldGeneration", generation);

		JsonObject display = new JsonObject();
		display.addProperty("markerSize", config.markerSize());
		display.addProperty("terrainStyle", config.terrainStyle());
		display.addProperty("overlayExplored", config.overlayExplored());
		root.add("display", display);

		JsonObject highlight = new JsonObject();
		highlight.addProperty("enabled", config.biomeHighlightEnabled());
		highlight.add("biomes", GSON.toJsonTree(config.highlightedBiomes()));
		root.add("biomeHighlight", highlight);

		JsonObject heights = new JsonObject();
		heights.addProperty("overworld", config.overworldY());
		heights.addProperty("nether", config.netherY());
		heights.addProperty("end", config.endY());
		root.add("biomeSampleY", heights);

		JsonObject markers = new JsonObject();
		for (Map.Entry<String, Boolean> marker : config.markerSnapshot().entrySet()) {
			markers.addProperty(marker.getKey(), marker.getValue());
		}
		root.add("markers", markers);

		JsonObject profiles = new JsonObject();
		for (Map.Entry<String, SeedAtlasConfig.SeedProfile> entry : config.profileSnapshot().entrySet()) {
			SeedAtlasConfig.SeedProfile seed = entry.getValue();
			JsonObject profile = new JsonObject();
			// The base seed keeps its version 6 location, so older releases still read it.
			addSeedValue(profile, seed.base());
			profile.addProperty("label", seed.contextLabel());
			if (seed.nether() != null || seed.end() != null) {
				JsonObject dimensions = new JsonObject();
				if (seed.nether() != null) dimensions.add("nether", addSeedValue(new JsonObject(), seed.nether()));
				if (seed.end() != null) dimensions.add("end", addSeedValue(new JsonObject(), seed.end()));
				profile.add("dimensions", dimensions);
			}
			profiles.add(entry.getKey(), profile);
		}
		root.add("seeds", profiles);

		JsonObject versions = new JsonObject();
		config.gameVersionSnapshot().forEach(versions::addProperty);
		root.add("gameVersions", versions);

		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(
			temporary,
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		)) {
			GSON.toJson(root, writer);
		}

		try {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static JsonObject object(final JsonObject parent, final String key) {
		JsonElement element = parent.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static boolean bool(final JsonObject object, final String key, final boolean fallback) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() ? element.getAsBoolean() : fallback;
	}

	private static int integer(final JsonObject object, final String key, final int fallback) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	/** Reads {@code {"input": ..., "value": ...}}; null when no numeric value is stored. */
	private static SeedAtlasConfig.SeedValue seedValue(final JsonObject object) {
		Long seed = longValue(object, "value");
		return seed == null ? null : new SeedAtlasConfig.SeedValue(string(object, "input", Long.toString(seed)), seed);
	}

	private static JsonObject addSeedValue(final JsonObject object, final SeedAtlasConfig.SeedValue value) {
		if (value != null) {
			object.addProperty("input", value.input());
			object.addProperty("value", value.seed());
		}
		return object;
	}

	private static Long longValue(final JsonObject object, final String key) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			return element.getAsLong();
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static String string(final JsonObject object, final String key, final String fallback) {
		JsonElement element = object.get(key);
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : fallback;
	}
}

package org.seedatlas.xaero.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.nativeapi.Dimension;

/** Mutable, synchronized representation of the client configuration. */
public final class SeedAtlasConfig {
	public static final int CURRENT_VERSION = 8;
	public static final int DEFAULT_OPACITY = 255;
	/** Completed structure markers in percent: 0 hides them, 100 draws them like every other marker. */
	public static final int DEFAULT_COMPLETED_OPACITY = 100;
	/**
	 * Blocks a completed structure may move, e.g. between Minecraft versions, and stay completed.
	 * Below 16 so neighbouring slime chunks and other per-chunk markers stay apart.
	 */
	public static final int COMPLETED_TOLERANCE = 12;
	public static final int DEFAULT_BIOME_RESOLUTION = 1;
	public static final int DEFAULT_WORKER_THREADS = 0;
	public static final int DEFAULT_PREFETCH_RADIUS = 1;
	public static final int[] BIOME_RESOLUTION_STEPS = {1, 2, 4, 8, 16};
	public static final int[] WORKER_THREAD_STEPS = {0, 1, 2, 4, 8};
	public static final int[] PREFETCH_RADIUS_STEPS = {0, 1, 2, 3};
	/** Marker size in percent of the default pixel band. */
	public static final int DEFAULT_MARKER_SIZE = 150;
	public static final int[] MARKER_SIZE_STEPS = {50, 75, 100, 150, 200, 250};
	public static final int DEFAULT_OVERWORLD_Y = 255;
	public static final int DEFAULT_NETHER_Y = 64;
	public static final int DEFAULT_END_Y = 128;
	public static final int MIN_SAMPLE_Y = -2048;
	public static final int MAX_SAMPLE_Y = 2048;

	private boolean layerEnabled = true;
	private int opacity = DEFAULT_OPACITY;
	private boolean structuresEnabled;
	private int completedOpacity = DEFAULT_COMPLETED_OPACITY;
	private final Set<StructureKey> completedStructures = new LinkedHashSet<>();
	private final Map<StructureGroup, List<StructureKey>> completedByGroup = new HashMap<>();
	private int biomeResolution = DEFAULT_BIOME_RESOLUTION;
	private int workerThreads = DEFAULT_WORKER_THREADS;
	private int prefetchRadius = DEFAULT_PREFETCH_RADIUS;
	private int markerSize = DEFAULT_MARKER_SIZE;
	private boolean biomeHighlightEnabled;
	private final Set<Integer> highlightedBiomes = new LinkedHashSet<>();
	private boolean largeBiomes;
	private boolean terrainStyle = true;
	private boolean overlayExplored;
	private int overworldY = DEFAULT_OVERWORLD_Y;
	private int netherY = DEFAULT_NETHER_Y;
	private int endY = DEFAULT_END_Y;
	private final Map<String, Boolean> markers = new LinkedHashMap<>();
	private final Map<String, SeedProfile> seedProfiles = new LinkedHashMap<>();
	/** Minecraft version per world or server context; absent means the running game's version. */
	private final Map<String, String> gameVersions = new LinkedHashMap<>();

	public SeedAtlasConfig() {
		for (MarkerType type : MarkerType.all()) {
			this.markers.put(type.id(), type.enabledByDefault());
		}
	}

	public synchronized boolean layerEnabled() {
		return this.layerEnabled;
	}

	public synchronized int opacity() {
		return this.opacity;
	}

	public synchronized float opacityFraction() {
		return this.opacity / 255.0F;
	}

	public synchronized boolean structuresEnabled() {
		return this.structuresEnabled;
	}

	public synchronized int markerSize() {
		return this.markerSize;
	}

	/** Marker size as a factor of the default pixel band, read every frame by the renderer. */
	public synchronized float markerSizeFactor() {
		return this.markerSize / 100.0F;
	}

	public static int nextMarkerSize(final int current) {
		for (int index = 0; index < MARKER_SIZE_STEPS.length; index++) {
			if (MARKER_SIZE_STEPS[index] == current) {
				return MARKER_SIZE_STEPS[(index + 1) % MARKER_SIZE_STEPS.length];
			}
		}
		return DEFAULT_MARKER_SIZE;
	}

	public synchronized boolean biomeHighlightEnabled() {
		return this.biomeHighlightEnabled;
	}

	/** Snapshot of the highlighted engine biome ids; the set is not shared. */
	public synchronized Set<Integer> highlightedBiomes() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(this.highlightedBiomes));
	}

	public synchronized int highlightedBiomeCount() {
		return this.highlightedBiomes.size();
	}

	/** Allocation-free check used by the selector UI on every frame. */
	public synchronized boolean isBiomeHighlighted(final int biomeId) {
		return this.highlightedBiomes.contains(biomeId);
	}

	/** Exact, immutable cache signature; independent of the order biomes were selected. */
	public synchronized Set<Integer> highlightSignature() {
		return this.biomeHighlightEnabled ? Set.copyOf(this.highlightedBiomes) : Set.of();
	}

	public synchronized int completedOpacity() { return completedOpacity; }

	/** Read every frame by the marker renderer. */
	public synchronized float completedOpacityFraction() { return completedOpacity / 100.0F; }

	/** Completed markers at 0 % are hidden entirely, so they cannot be hovered or clicked either. */
	public synchronized boolean hideCompletedStructures() { return completedOpacity == 0; }

	synchronized boolean setCompletedOpacity(int opacity) {
		int clamped = Math.max(0, Math.min(100, opacity));
		if (completedOpacity == clamped) return false;
		completedOpacity = clamped;
		return true;
	}

	/**
	 * Completed at this position or within {@link #COMPLETED_TOLERANCE} blocks of it, so progress
	 * survives a Minecraft version or engine update that moves a structure by a few blocks.
	 */
	public synchronized boolean isCompleted(StructureKey key) {
		return key != null && (completedStructures.contains(key) || completedNear(key) != null);
	}

	synchronized boolean setCompleted(StructureKey key, boolean completed) {
		Objects.requireNonNull(key);
		if (completed) {
			return !isCompleted(key) && restoreCompleted(key);
		}
		boolean removed = removeCompleted(key);
		for (StructureKey near; (near = completedNear(key)) != null; ) removed |= removeCompleted(near);
		return removed;
	}

	/** Adds a stored entry exactly as saved, without merging it into a nearby one. */
	synchronized boolean restoreCompleted(StructureKey key) {
		if (!completedStructures.add(key)) return false;
		completedByGroup.computeIfAbsent(StructureGroup.of(key), group -> new ArrayList<>()).add(key);
		return true;
	}

	/** Completed structures of a world or server, of one type or ({@code null}) all types. */
	public synchronized int completedCount(String world, String type) {
		int count = 0;
		for (StructureKey key : completedStructures) {
			if (key.world().equals(world) && (type == null || key.type().equals(type))) count++;
		}
		return count;
	}

	/** Marks every completed structure of a world or server, of one type or all, as not completed. */
	synchronized int resetCompleted(String world, String type) {
		List<StructureKey> matching = new ArrayList<>();
		for (StructureKey key : completedStructures) {
			if (key.world().equals(world) && (type == null || key.type().equals(type))) matching.add(key);
		}
		matching.forEach(this::removeCompleted);
		return matching.size();
	}

	private boolean removeCompleted(StructureKey key) {
		if (!completedStructures.remove(key)) return false;
		List<StructureKey> group = completedByGroup.get(StructureGroup.of(key));
		if (group != null) {
			group.remove(key);
			if (group.isEmpty()) completedByGroup.remove(StructureGroup.of(key));
		}
		return true;
	}

	private StructureKey completedNear(StructureKey key) {
		List<StructureKey> group = completedByGroup.get(StructureGroup.of(key));
		if (group == null) return null;
		for (StructureKey other : group) {
			if (Math.abs((long) other.x() - key.x()) <= COMPLETED_TOLERANCE
				&& Math.abs((long) other.z() - key.z()) <= COMPLETED_TOLERANCE) return other;
		}
		return null;
	}

	/** Everything that identifies a completed structure except its position. */
	private record StructureGroup(String world, long seed, boolean largeBiomes, String dimension, String type) {
		static StructureGroup of(StructureKey key) {
			return new StructureGroup(key.world(), key.seed(), key.largeBiomes(), key.dimension(), key.type());
		}
	}

	synchronized Set<StructureKey> completedSnapshot() { return new LinkedHashSet<>(completedStructures); }

	/** Y is deliberately excluded: approximate structure heights can change with sampling. */
	public record StructureKey(String world, long seed, boolean largeBiomes,
	                           String dimension, String type, int x, int z) {
		public StructureKey {
			if (world == null || world.isBlank() || dimension == null || dimension.isBlank()
				|| type == null || type.isBlank()) throw new IllegalArgumentException("Invalid structure identity");
		}
	}

	public synchronized int biomeResolution() {
		return this.biomeResolution;
	}

	/** Zero means an automatically selected worker count. */
	public synchronized int workerThreads() {
		return this.workerThreads;
	}

	/** Number of low-priority tile rings prefetched around the current view/player. */
	public synchronized int prefetchRadius() {
		return this.prefetchRadius;
	}

	public synchronized boolean largeBiomes() {
		return this.largeBiomes;
	}

	public synchronized int overworldY() {
		return this.overworldY;
	}

	public synchronized int netherY() {
		return this.netherY;
	}

	public synchronized int endY() {
		return this.endY;
	}

	public int yFor(final ResourceKey<Level> dimension) {
		return dimension == null ? this.overworldY() : this.yFor(dimension.identifier().toString());
	}

	/** Returns the configured sample height, falling back to Overworld for custom dimensions. */
	public synchronized int yFor(final String dimensionId) {
		if ("minecraft:the_nether".equals(dimensionId) || "the_nether".equals(dimensionId) || "nether".equals(dimensionId)) {
			return this.netherY;
		}
		if ("minecraft:the_end".equals(dimensionId) || "the_end".equals(dimensionId) || "end".equals(dimensionId)) {
			return this.endY;
		}
		return this.overworldY;
	}

	public synchronized boolean markerEnabled(final String markerId) {
		return MarkerType.byId(markerId)
			.map(type -> this.markers.getOrDefault(type.id(), type.enabledByDefault()))
			.orElse(false);
	}

	public synchronized Set<String> enabledMarkerIds() {
		Set<String> result = new LinkedHashSet<>();
		for (MarkerType type : MarkerType.all()) {
			if (this.markers.getOrDefault(type.id(), type.enabledByDefault())) {
				result.add(type.id());
			}
		}
		return Collections.unmodifiableSet(result);
	}

	synchronized boolean setLayerEnabled(final boolean enabled) {
		if (this.layerEnabled == enabled) {
			return false;
		}
		this.layerEnabled = enabled;
		return true;
	}

	synchronized boolean setOpacity(final int opacity) {
		int clamped = Math.max(0, Math.min(255, opacity));
		if (this.opacity == clamped) {
			return false;
		}
		this.opacity = clamped;
		return true;
	}

	synchronized boolean setStructuresEnabled(final boolean enabled) {
		if (this.structuresEnabled == enabled) {
			return false;
		}
		this.structuresEnabled = enabled;
		return true;
	}

	synchronized boolean setMarkerSize(final int markerSize) {
		int normalized = allowedValue(markerSize, MARKER_SIZE_STEPS, DEFAULT_MARKER_SIZE);
		if (this.markerSize == normalized) {
			return false;
		}
		this.markerSize = normalized;
		return true;
	}

	public synchronized boolean terrainStyle() {
		return this.terrainStyle;
	}

	public synchronized boolean overlayExplored() {
		return this.overlayExplored;
	}

	synchronized boolean setOverlayExplored(final boolean enabled) {
		if (this.overlayExplored == enabled) return false;
		this.overlayExplored = enabled;
		return true;
	}

	synchronized boolean setTerrainStyle(final boolean enabled) {
		if (this.terrainStyle == enabled) {
			return false;
		}
		this.terrainStyle = enabled;
		return true;
	}

	synchronized boolean setBiomeHighlightEnabled(final boolean enabled) {
		if (this.biomeHighlightEnabled == enabled) {
			return false;
		}
		this.biomeHighlightEnabled = enabled;
		return true;
	}

	synchronized boolean addHighlightedBiome(final int biomeId) {
		if (biomeId < 0 || biomeId > 255) {
			return false;
		}
		return this.highlightedBiomes.add(biomeId);
	}

	synchronized boolean toggleHighlightedBiome(final int biomeId) {
		if (biomeId < 0 || biomeId > 255) {
			return false;
		}
		if (!this.highlightedBiomes.remove(biomeId)) {
			this.highlightedBiomes.add(biomeId);
		}
		return true;
	}

	synchronized boolean clearHighlightedBiomes() {
		if (this.highlightedBiomes.isEmpty()) {
			return false;
		}
		this.highlightedBiomes.clear();
		return true;
	}

	synchronized boolean setBiomeResolution(final int resolution) {
		int normalized = allowedValue(resolution, BIOME_RESOLUTION_STEPS, DEFAULT_BIOME_RESOLUTION);
		if (this.biomeResolution == normalized) {
			return false;
		}
		this.biomeResolution = normalized;
		return true;
	}

	synchronized boolean setWorkerThreads(final int workerThreads) {
		int normalized = allowedValue(workerThreads, WORKER_THREAD_STEPS, DEFAULT_WORKER_THREADS);
		if (this.workerThreads == normalized) {
			return false;
		}
		this.workerThreads = normalized;
		return true;
	}

	synchronized boolean setPrefetchRadius(final int prefetchRadius) {
		int normalized = allowedValue(prefetchRadius, PREFETCH_RADIUS_STEPS, DEFAULT_PREFETCH_RADIUS);
		if (this.prefetchRadius == normalized) {
			return false;
		}
		this.prefetchRadius = normalized;
		return true;
	}

	synchronized boolean setLargeBiomes(final boolean largeBiomes) {
		if (this.largeBiomes == largeBiomes) {
			return false;
		}
		this.largeBiomes = largeBiomes;
		return true;
	}

	synchronized boolean setOverworldY(final int y) {
		int clamped = clampY(y);
		if (this.overworldY == clamped) {
			return false;
		}
		this.overworldY = clamped;
		return true;
	}

	synchronized boolean setNetherY(final int y) {
		int clamped = clampY(y);
		if (this.netherY == clamped) {
			return false;
		}
		this.netherY = clamped;
		return true;
	}

	synchronized boolean setEndY(final int y) {
		int clamped = clampY(y);
		if (this.endY == clamped) {
			return false;
		}
		this.endY = clamped;
		return true;
	}

	synchronized boolean setMarkerEnabled(final String markerId, final boolean enabled) {
		MarkerType type = MarkerType.byId(markerId).orElse(null);
		if (type == null || this.markers.getOrDefault(type.id(), type.enabledByDefault()) == enabled) {
			return false;
		}
		this.markers.put(type.id(), enabled);
		return true;
	}

	synchronized SeedProfile seedProfile(final String contextKey) {
		return this.seedProfiles.get(contextKey);
	}

	synchronized boolean putSeedProfile(final String contextKey, final SeedProfile profile) {
		return !profile.equals(this.seedProfiles.put(contextKey, profile));
	}

	synchronized boolean removeSeedProfile(final String contextKey) {
		return this.seedProfiles.remove(contextKey) != null;
	}

	synchronized String gameVersion(final String contextKey) {
		return this.gameVersions.get(contextKey);
	}

	/** {@code null} returns the context to the running game's version. */
	synchronized boolean setGameVersion(final String contextKey, final String version) {
		if (contextKey == null || contextKey.isBlank()) return false;
		return version == null
			? this.gameVersions.remove(contextKey) != null
			: !version.equals(this.gameVersions.put(contextKey, version));
	}

	synchronized Map<String, String> gameVersionSnapshot() {
		return new LinkedHashMap<>(this.gameVersions);
	}

	synchronized Map<String, Boolean> markerSnapshot() {
		return new LinkedHashMap<>(this.markers);
	}

	synchronized Map<String, SeedProfile> profileSnapshot() {
		return new LinkedHashMap<>(this.seedProfiles);
	}

	static int clampY(final int y) {
		return Math.max(MIN_SAMPLE_Y, Math.min(MAX_SAMPLE_Y, y));
	}

	private static int allowedValue(final int value, final int[] allowed, final int fallback) {
		for (int candidate : allowed) {
			if (value == candidate) {
				return value;
			}
		}
		return fallback;
	}

	/** Seed text is kept so numeric and textual inputs can both be shown in status UI. */
	public record SeedValue(String input, long seed) {
		public SeedValue {
			input = input == null || input.isBlank() ? Long.toString(seed) : input;
		}
	}

	/**
	 * Seeds of one world or server. {@code base} applies to every dimension without its own seed,
	 * because some servers generate the Nether or End from a different seed than the Overworld.
	 * Any of the three may be absent, but a stored profile always has at least one.
	 */
	public record SeedProfile(SeedValue base, SeedValue nether, SeedValue end, String contextLabel) {
		public SeedProfile {
			contextLabel = contextLabel == null ? "" : contextLabel;
		}

		public SeedProfile(final String input, final long seed, final String contextLabel) {
			this(new SeedValue(input, seed), null, null, contextLabel);
		}

		/** The seed generating {@code dimension}: its own seed, else the base seed. */
		public SeedValue seedFor(final Dimension dimension) {
			SeedValue own = ownSeed(dimension);
			return own != null ? own : this.base;
		}

		/** The Nether's or End's own seed; null when it follows the base seed (always for the Overworld). */
		public SeedValue ownSeed(final Dimension dimension) {
			return switch (dimension) {
				case NETHER -> this.nether;
				case END -> this.end;
				case OVERWORLD -> null;
			};
		}

		/** Replaces (or with {@code null} removes) the own seed of the Nether or End. */
		public SeedProfile withDimension(final Dimension dimension, final SeedValue value) {
			return switch (dimension) {
				case NETHER -> new SeedProfile(this.base, value, this.end, this.contextLabel);
				case END -> new SeedProfile(this.base, this.nether, value, this.contextLabel);
				case OVERWORLD -> throw new IllegalArgumentException("The Overworld uses the base seed");
			};
		}

		public boolean isEmpty() {
			return this.base == null && this.nether == null && this.end == null;
		}
	}
}

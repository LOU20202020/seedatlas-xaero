package org.seedatlas.xaero.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.gui.SeedAtlasSettingsScreen;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stable client facade shared by commands, settings and the Xaero/native integration.
 *
 * <p>All mutations persist immediately. Generation/display changes advance {@link #revision()}, allowing
 * renderers to invalidate cached tiles without depending on implementation details.</p>
 */
public final class SeedAtlasClientState {
	private static final Logger LOGGER = LoggerFactory.getLogger("seedatlas_xaero");
	private static final Object LOCK = new Object();
	private static Path CONFIG_PATH;
	private static final AtomicLong REVISION = new AtomicLong();
	private static final CopyOnWriteArrayList<StateListener> LISTENERS = new CopyOnWriteArrayList<>();
	private static final WorldContext NO_CONTEXT = new WorldContext("", "");

	private static volatile SeedAtlasConfig config = new SeedAtlasConfig();
	private static volatile boolean initialized;
	private static volatile WorldContext activeContext = NO_CONTEXT;
	/** Seeds of the active context, or null when none is configured. */
	private static volatile SeedAtlasConfig.SeedProfile activeProfile;
	private static volatile String autoDetectSuppressedContext;
	private static volatile ContextProvider contextProvider;

	private SeedAtlasClientState() {
	}

	/** Called by the loader before the shared client code is initialized. */
	public static void initialize(final Path configDirectory) {
		synchronized (LOCK) {
			Path path = configDirectory.toAbsolutePath().normalize().resolve("seedatlas_xaero.json");
			if (CONFIG_PATH != null && !CONFIG_PATH.equals(path)) {
				throw new IllegalStateException("Seed Atlas config directory is already configured");
			}
			CONFIG_PATH = path;
			initialize();
		}
	}

	public static void initialize() {
		if (initialized) {
			return;
		}
		synchronized (LOCK) {
			if (initialized) {
				return;
			}
			try {
				config = SeedAtlasConfigIO.load(Objects.requireNonNull(CONFIG_PATH, "Loader must initialize the config directory"));
			} catch (IOException exception) {
				LOGGER.error("Could not load {}; using defaults", CONFIG_PATH, exception);
				config = new SeedAtlasConfig();
			}
			initialized = true;
		}
	}

	public static SeedAtlasConfig config() {
		initialize();
		return config;
	}

	/** The base seed: the Overworld's, and every dimension's that has no seed of its own. */
	public static OptionalLong activeSeed() {
		initialize();
		SeedAtlasConfig.SeedProfile profile = activeProfile;
		return profile == null ? OptionalLong.empty() : seedOf(profile.base());
	}

	/** The seed that generates {@code dimension} in the active context. */
	public static OptionalLong activeSeed(final Dimension dimension) {
		initialize();
		SeedAtlasConfig.SeedProfile profile = activeProfile;
		return profile == null ? OptionalLong.empty() : seedOf(profile.seedFor(dimension));
	}

	/** True when at least one dimension of the active context has a seed. */
	public static boolean hasActiveSeed() {
		initialize();
		SeedAtlasConfig.SeedProfile profile = activeProfile;
		return profile != null && !profile.isEmpty();
	}

	public static Optional<SeedAtlasConfig.SeedProfile> activeProfile() {
		initialize();
		return Optional.ofNullable(activeProfile);
	}

	private static OptionalLong seedOf(final SeedAtlasConfig.SeedValue value) {
		return value == null ? OptionalLong.empty() : OptionalLong.of(value.seed());
	}

	/** The Minecraft version the active world or server generates with; the running game's by default. */
	public static GameVersion activeGameVersion() {
		initialize();
		return GameVersion.parse(config.gameVersion(activeContext.key())).orElse(GameVersion.NEWEST);
	}

	/** True when the active context follows the running game's version instead of a chosen one. */
	public static boolean gameVersionAutomatic() {
		initialize();
		return config.gameVersion(activeContext.key()) == null;
	}

	/**
	 * Selects the version the active world or server generates with, e.g. a 26.2 server joined from
	 * 26.3; {@code null} follows the running game again.
	 */
	public static void setGameVersion(final GameVersion version) {
		initialize();
		refreshContext(Minecraft.getInstance());
		WorldContext context = activeContext;
		if (context.key().isEmpty()) {
			throw new IllegalStateException("No active world or server context");
		}
		// An explicit choice stays put when a later mod update moves the automatic version on.
		String stored = version == null ? null : version.name();
		if (config.setGameVersion(context.key(), stored)) {
			saveAndPublish(ChangeReason.WORLD_GENERATION);
		}
	}

	public static String currentContextKey() {
		initialize();
		return activeContext.key();
	}

	public static String currentContextLabel() {
		initialize();
		return activeContext.label();
	}

	public static long revision() {
		return REVISION.get();
	}

	/** Called from the client tick; cheap when the selected world has not changed. */
	public static void refreshContext(final Minecraft minecraft) {
		initialize();
		WorldContext nextContext = resolveContext(minecraft);
		OptionalLong detectedSingleplayerSeed = detectSingleplayerSeed(minecraft);
		if (nextContext.key().equals(autoDetectSuppressedContext)) {
			detectedSingleplayerSeed = OptionalLong.empty();
		}
		boolean contextChanged;
		boolean profileChanged = false;

		synchronized (LOCK) {
			contextChanged = !activeContext.key().equals(nextContext.key());
			if (contextChanged && !nextContext.key().equals(autoDetectSuppressedContext)) {
				autoDetectSuppressedContext = null;
			}
			SeedAtlasConfig.SeedProfile current = activeProfile;
			if (!contextChanged && (current != null && current.base() != null || detectedSingleplayerSeed.isEmpty())) {
				return;
			}

			activeContext = nextContext;
			if (nextContext.key().isEmpty()) {
				activeProfile = null;
			} else {
				SeedAtlasConfig.SeedProfile profile = config.seedProfile(nextContext.key());
				if (detectedSingleplayerSeed.isPresent() && (profile == null || profile.base() == null
					|| profile.base().seed() != detectedSingleplayerSeed.getAsLong())) {
					long seed = detectedSingleplayerSeed.getAsLong();
					SeedAtlasConfig.SeedValue base = new SeedAtlasConfig.SeedValue(Long.toString(seed), seed);
					profile = profile == null
						? new SeedAtlasConfig.SeedProfile(base, null, null, nextContext.label())
						: new SeedAtlasConfig.SeedProfile(base, profile.nether(), profile.end(), nextContext.label());
					profileChanged = config.putSeedProfile(nextContext.key(), profile);
				}
				activeProfile = profile;
			}
		}

		if (profileChanged) {
			save();
		}
		if (contextChanged || profileChanged) {
			publish(ChangeReason.CONTEXT);
		}
	}

	public static void setContextProvider(final ContextProvider provider) {
		contextProvider = provider;
		refreshContext(Minecraft.getInstance());
	}

	/** Parses exactly like vanilla: a long when possible, otherwise Java's text hash. */
	public static long parseSeed(final String input) {
		if (input == null) {
			throw new IllegalArgumentException("Seed must not be null");
		}
		return WorldOptions.parseSeed(input).orElseThrow(() -> new IllegalArgumentException("Seed must not be blank"));
	}

	public static long setSeed(final String input) {
		initialize();
		String normalized = Objects.requireNonNull(input, "input").trim();
		long seed = parseSeed(normalized);
		refreshContext(Minecraft.getInstance());
		WorldContext context = activeContext;
		if (context.key().isEmpty()) {
			throw new IllegalStateException("No active world or server context");
		}

		synchronized (LOCK) {
			SeedAtlasConfig.SeedValue value = new SeedAtlasConfig.SeedValue(normalized, seed);
			SeedAtlasConfig.SeedProfile existing = config.seedProfile(context.key());
			SeedAtlasConfig.SeedProfile profile = existing == null
				? new SeedAtlasConfig.SeedProfile(value, null, null, context.label())
				: new SeedAtlasConfig.SeedProfile(value, existing.nether(), existing.end(), context.label());
			config.putSeedProfile(context.key(), profile);
			activeProfile = profile;
			autoDetectSuppressedContext = null;
		}
		saveAndPublish(ChangeReason.SEED);
		return seed;
	}

	/**
	 * Gives the Nether or End its own seed in the active context; the base seed keeps
	 * generating the other dimensions.
	 */
	public static long setDimensionSeed(final Dimension dimension, final String input) {
		initialize();
		if (dimension == Dimension.OVERWORLD) {
			throw new IllegalArgumentException("The Overworld uses the base seed");
		}
		String normalized = Objects.requireNonNull(input, "input").trim();
		long seed = parseSeed(normalized);
		refreshContext(Minecraft.getInstance());
		WorldContext context = activeContext;
		if (context.key().isEmpty()) {
			throw new IllegalStateException("No active world or server context");
		}

		synchronized (LOCK) {
			SeedAtlasConfig.SeedProfile existing = config.seedProfile(context.key());
			SeedAtlasConfig.SeedProfile profile = (existing == null
				? new SeedAtlasConfig.SeedProfile(null, null, null, context.label())
				: existing).withDimension(dimension, new SeedAtlasConfig.SeedValue(normalized, seed));
			config.putSeedProfile(context.key(), profile);
			activeProfile = profile;
		}
		saveAndPublish(ChangeReason.SEED);
		return seed;
	}

	/** Removes the own seed of the Nether or End, which then follows the base seed again. */
	public static boolean clearDimensionSeed(final Dimension dimension) {
		initialize();
		if (dimension == Dimension.OVERWORLD) {
			throw new IllegalArgumentException("The Overworld uses the base seed");
		}
		refreshContext(Minecraft.getInstance());
		WorldContext context = activeContext;
		if (context.key().isEmpty()) {
			return false;
		}

		synchronized (LOCK) {
			SeedAtlasConfig.SeedProfile existing = config.seedProfile(context.key());
			if (existing == null || existing.ownSeed(dimension) == null) {
				return false;
			}
			SeedAtlasConfig.SeedProfile profile = existing.withDimension(dimension, null);
			if (profile.isEmpty()) {
				config.removeSeedProfile(context.key());
				activeProfile = null;
			} else {
				config.putSeedProfile(context.key(), profile);
				activeProfile = profile;
			}
		}
		saveAndPublish(ChangeReason.SEED);
		return true;
	}

	public static boolean clearSeed() {
		initialize();
		refreshContext(Minecraft.getInstance());
		WorldContext context = activeContext;
		if (context.key().isEmpty()) {
			return false;
		}

		boolean changed;
		synchronized (LOCK) {
			changed = config.removeSeedProfile(context.key());
			activeProfile = null;
			autoDetectSuppressedContext = context.key();
		}
		if (changed) {
			saveAndPublish(ChangeReason.SEED);
		}
		return changed;
	}

	public static void toggleLayer() {
		setLayerEnabled(!config().layerEnabled());
	}

	public static void setLayerEnabled(final boolean enabled) {
		if (config().setLayerEnabled(enabled)) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void setOpacity(final int opacity) {
		if (config().setOpacity(opacity)) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void setTerrainStyle(final boolean enabled) {
		if (config().setTerrainStyle(enabled)) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void setOverlayExplored(final boolean enabled) {
		if (config().setOverlayExplored(enabled)) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void setStructuresEnabled(final boolean enabled) {
		if (config().setStructuresEnabled(enabled)) {
			saveAndPublish(ChangeReason.MARKERS);
		}
	}

	/**
	 * Read by the marker renderer every frame, so a slider can preview it live; no rescan is
	 * needed. Pass {@code persist} once the value settles to write the config.
	 */
	public static void setCompletedOpacity(int opacity, boolean persist) {
		if (config().setCompletedOpacity(opacity) && persist) save();
	}

	/** Marker size is read by the marker renderer every frame, so no rescan is needed. */
	public static void setMarkerSize(final int markerSize) {
		if (config().setMarkerSize(markerSize)) save();
	}

	public static float markerSizeFactor() {
		return config().markerSizeFactor();
	}

	/**
	 * Highlight changes rebuild the biome tiles, so they publish a display revision
	 * instead of only saving.
	 */
	public static void setBiomeHighlightEnabled(final boolean enabled) {
		if (config().setBiomeHighlightEnabled(enabled)) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void toggleHighlightedBiome(final int biomeId) {
		if (config().toggleHighlightedBiome(biomeId)) {
			// Selecting a biome should immediately activate the focus view.
			if (config().isBiomeHighlighted(biomeId)) config().setBiomeHighlightEnabled(true);
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void clearHighlightedBiomes() {
		if (config().clearHighlightedBiomes()) {
			saveAndPublish(ChangeReason.DISPLAY);
		}
	}

	public static void setStructureCompleted(SeedAtlasConfig.StructureKey key, boolean completed) {
		if (config().setCompleted(key, completed)) save();
	}

	/** Completed structures of the active world or server, of one marker type or ({@code null}) all. */
	public static int completedCount(final MarkerType type) {
		String world = currentContextKey();
		return world.isEmpty() ? 0 : config().completedCount(world, type == null ? null : type.id());
	}

	/** Marks the active world's completed structures of one type or ({@code null}) all as not completed. */
	public static int resetCompleted(final MarkerType type) {
		String world = currentContextKey();
		if (world.isEmpty()) return 0;
		int removed = config().resetCompleted(world, type == null ? null : type.id());
		if (removed > 0) save();
		return removed;
	}

	public static void setBiomeResolution(final int resolution) {
		if (config().setBiomeResolution(resolution)) {
			saveAndPublish(ChangeReason.PERFORMANCE);
		}
	}

	public static void setWorkerThreads(final int workerThreads) {
		if (config().setWorkerThreads(workerThreads)) {
			saveAndPublish(ChangeReason.PERFORMANCE);
		}
	}

	public static void setPrefetchRadius(final int prefetchRadius) {
		if (config().setPrefetchRadius(prefetchRadius)) {
			saveAndPublish(ChangeReason.PERFORMANCE);
		}
	}

	public static void setLargeBiomes(final boolean largeBiomes) {
		if (config().setLargeBiomes(largeBiomes)) {
			saveAndPublish(ChangeReason.WORLD_GENERATION);
		}
	}

	public static void setOverworldY(final int y) {
		if (config().setOverworldY(y)) {
			saveAndPublish(ChangeReason.WORLD_GENERATION);
		}
	}

	public static void setNetherY(final int y) {
		if (config().setNetherY(y)) {
			saveAndPublish(ChangeReason.WORLD_GENERATION);
		}
	}

	public static void setEndY(final int y) {
		if (config().setEndY(y)) {
			saveAndPublish(ChangeReason.WORLD_GENERATION);
		}
	}

	public static void setMarkerEnabled(final String markerId, final boolean enabled) {
		if (config().setMarkerEnabled(markerId, enabled)) {
			saveAndPublish(ChangeReason.MARKERS);
		}
	}

	public static void openSettings(final Screen parent) {
		initialize();
		if (!SeedAtlasXaero.xaeroCompatible()) {
			// The settings drive the Xaero integration, which stays off for this Xaero version.
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.sendSystemMessage(SeedAtlasXaero.incompatibleMessage());
			}
			return;
		}
		Minecraft.getInstance().gui.setScreen(new SeedAtlasSettingsScreen(parent));
	}

	public static void save() {
		initialize();
		try {
			SeedAtlasConfigIO.save(CONFIG_PATH, config);
		} catch (IOException exception) {
			LOGGER.error("Could not save {}", CONFIG_PATH, exception);
		}
	}

	public static void reload() {
		initialize();
		try {
			SeedAtlasConfig loaded = SeedAtlasConfigIO.load(CONFIG_PATH);
			synchronized (LOCK) {
				config = loaded;
				autoDetectSuppressedContext = null;
				loadActiveProfileLocked();
			}
			publish(ChangeReason.RELOAD);
		} catch (IOException exception) {
			LOGGER.error("Could not reload {}", CONFIG_PATH, exception);
		}
	}

	public static Subscription addListener(final StateListener listener) {
		Objects.requireNonNull(listener, "listener");
		LISTENERS.add(listener);
		return () -> LISTENERS.remove(listener);
	}

	private static void loadActiveProfileLocked() {
		activeProfile = activeContext.key().isEmpty() ? null : config.seedProfile(activeContext.key());
	}

	private static void saveAndPublish(final ChangeReason reason) {
		save();
		publish(reason);
	}

	private static void publish(final ChangeReason reason) {
		long newRevision = REVISION.incrementAndGet();
		for (StateListener listener : LISTENERS) {
			try {
				listener.onStateChanged(newRevision, reason);
			} catch (RuntimeException exception) {
				LOGGER.error("Seed Atlas state listener failed", exception);
			}
		}
	}

	private static WorldContext resolveContext(final Minecraft minecraft) {
		ContextProvider provider = contextProvider;
		if (provider != null) {
			try {
				WorldContext supplied = provider.resolve(minecraft);
				if (supplied != null && !supplied.key().isBlank()) {
					return supplied;
				}
			} catch (RuntimeException exception) {
				LOGGER.error("Seed Atlas context provider failed", exception);
			}
		}

		IntegratedServer integratedServer = minecraft.getSingleplayerServer();
		if (integratedServer != null) {
			String label = integratedServer.getWorldData().getLevelName();
			String path = integratedServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString();
			return new WorldContext("singleplayer:" + digest(path), label);
		}

		ServerData server = minecraft.getCurrentServer();
		if (server != null && server.ip != null && !server.ip.isBlank()) {
			String address = server.ip.trim().toLowerCase(Locale.ROOT);
			String label = server.name == null || server.name.isBlank() ? server.ip : server.name + " (" + server.ip + ")";
			return new WorldContext("server:" + digest(address), label);
		}

		return NO_CONTEXT;
	}

	private static OptionalLong detectSingleplayerSeed(final Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(server.getWorldGenSettings().options().seed());
	}

	private static String digest(final String input) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public enum ChangeReason {
		CONTEXT,
		SEED,
		DISPLAY,
		WORLD_GENERATION,
		MARKERS,
		PERFORMANCE,
		RELOAD
	}

	@FunctionalInterface
	public interface StateListener {
		void onStateChanged(long revision, ChangeReason reason);
	}

	@FunctionalInterface
	public interface ContextProvider {
		WorldContext resolve(Minecraft minecraft);
	}

	@FunctionalInterface
	public interface Subscription extends AutoCloseable {
		@Override
		void close();
	}

	public record WorldContext(String key, String label) {
		public WorldContext {
			key = Objects.requireNonNull(key, "key").trim();
			label = Objects.requireNonNullElse(label, "").trim();
			if (key.length() > 256) {
				key = "external:" + digest(key);
			}
		}
	}
}

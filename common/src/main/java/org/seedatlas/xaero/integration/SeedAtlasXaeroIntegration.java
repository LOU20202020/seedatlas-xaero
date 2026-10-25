package org.seedatlas.xaero.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.biome.BiomeHighlighter;
import org.seedatlas.xaero.integration.biome.SeedAtlasBiomeOverlayRenderer;
import org.seedatlas.xaero.nativeapi.BiomeSample;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.WorldType;
import xaero.lib.client.gui.IScreenBase;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** Runtime boundary between Xaero, the adaptive overlay and the native engine. */
public final class SeedAtlasXaeroIntegration {
    private static final Object NATIVE_LOCK = new Object();

    private static volatile boolean fullMapActive;
    /** True while a Seed Atlas menu covers the world map without drawing it. */
    private static volatile boolean heavyWorkPaused;
    private static final StateRevisionTracker REVISIONS = new StateRevisionTracker();
    private static volatile double observedMapScale = 1.0;
    /** Biomes of the version they were listed for; versions differ in biomes and their names. */
    private static volatile Map.Entry<GameVersion, List<BiomeSample>> availableBiomes;
    private static boolean initialized;
    /** One engine context per seed; up to three when every dimension has its own seed. */
    private static final int MAX_SESSIONS = 3;
    private static final LinkedHashMap<SessionKey, SeedAtlasNative> SESSIONS = new LinkedHashMap<>(4, 0.75F, true);

    private SeedAtlasXaeroIntegration() {
    }

    /** Keeps configured seeds separate for Xaero multiworlds on one server. */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        SeedAtlasClientState.setContextProvider(minecraft -> {
            WorldMapSession mapSession = WorldMapSession.getCurrentSession();
            if (mapSession == null || mapSession.getMapProcessor() == null) {
                return null;
            }
            String worldId = mapSession.getMapProcessor().getCurrentWorldId();
            String multiworldId = mapSession.getMapProcessor().getCurrentMWId();
            if (worldId == null || worldId.isBlank()) {
                return null;
            }
            String label = multiworldId == null || multiworldId.isBlank()
                ? worldId : worldId + " / " + multiworldId;
            return new SeedAtlasClientState.WorldContext(
                "xaero:" + contextDigest(worldId, multiworldId), label);
        });
        initialized = true;
    }

    public static boolean isLayerActive() {
        return fullMapActive && !heavyWorkPaused && isLayerConfigured();
    }

    public static boolean isLayerConfigured() {
        return SeedAtlasClientState.config().layerEnabled()
            && SeedAtlasClientState.hasActiveSeed();
    }

    public static boolean isHeavyWorkPaused() {
        return heavyWorkPaused;
    }

    public static void setFullMapActive(boolean active) {
        fullMapActive = active;
        if (!active) {
            heavyWorkPaused = false;
        }
        synchronizeRevision();
        SeedAtlasBiomeOverlayRenderer.INSTANCE.setMapActive(active && !heavyWorkPaused);
        if (!active) {
            // Leaving the world map must idle native workers and GPU uploads.
            SeedAtlasBiomeOverlayRenderer.INSTANCE.idleCompletely();
        }
    }

    /**
     * Suspends biome/structure generation and GPU tile uploads while Seed Atlas
     * settings cover the map. Prevents coil-whine from background work under menus.
     */
    public static void setHeavyWorkPaused(boolean paused) {
        if (heavyWorkPaused == paused) {
            return;
        }
        heavyWorkPaused = paused;
        if (paused) {
            SeedAtlasBiomeOverlayRenderer.INSTANCE.setMapActive(false);
            SeedAtlasBiomeOverlayRenderer.INSTANCE.idleCompletely();
        } else if (fullMapActive) {
            synchronizeRevision();
            SeedAtlasBiomeOverlayRenderer.INSTANCE.setMapActive(true);
        }
    }

    /**
     * Seed Atlas menus opened from the world map draw it behind them, so its layer stays live there,
     * and changes show at once; a menu without the map behind it pauses the layer instead.
     */
    public static void setMapBehindMenu(boolean mapBehind) {
        if (mapBehind) {
            setFullMapActive(true);
        }
        setHeavyWorkPaused(!mapBehind);
    }

    /** Applies configuration changes without touching Xaero's leaf-region cache. */
    public static void synchronizeRevision() {
        REVISIONS.synchronize(SeedAtlasClientState.revision(), heavyWorkPaused,
            revision -> SeedAtlasBiomeOverlayRenderer.INSTANCE.onStateRevision(revision));
    }

    /** Records and schedules the current viewport without waiting for Xaero to request chunks. */
    public static void observeMapView(
        ResourceKey<Level> level,
        double cameraX,
        double cameraZ,
        double scale,
        int viewportWidth,
        int viewportHeight
    ) {
        if (level == null || !Double.isFinite(scale) || scale <= 0.0
            || !fullMapActive || heavyWorkPaused) {
            return;
        }
        observedMapScale = scale;
        SeedAtlasBiomeOverlayRenderer.INSTANCE.observeView(
            level, cameraX, cameraZ, scale, viewportWidth, viewportHeight);
    }

    /** Map-only maintenance, including the map drawn behind Seed Atlas menus. */
    public static void tickBackground(Minecraft minecraft) {
        synchronizeRevision();
        Screen screen = minecraft.gui.screen();
        if (fullMapActive && !(screen instanceof GuiMap)
            && !(screen instanceof IScreenBase menu && menu.getEscape() instanceof GuiMap)) {
            // A menu over the map was closed without returning to it.
            setFullMapActive(false);
        }
        if (!fullMapActive || heavyWorkPaused) {
            return;
        }
        SeedAtlasBiomeOverlayRenderer.INSTANCE.tickBackground(minecraft);
    }

    public static int yFor(ResourceKey<Level> dimension) {
        return SeedAtlasClientState.config().yFor(dimension);
    }

    /** Returns only already-computed overlay data; it never blocks the render thread. */
    public static BiomeSample biomeAtCached(
        ResourceKey<Level> level, int blockX, int blockZ
    ) {
        return SeedAtlasBiomeOverlayRenderer.INSTANCE.biomeAtCached(level, blockX, blockZ);
    }

    /** Localised biome name with a readable fallback for unknown engine ids. */
    public static Component biomeDisplayName(String biomeId) {
        String sanitized = biomeId == null ? "" : biomeId.replace((char) 13, ' ').replace((char) 10, ' ').trim();
        // The engine reports display names ("Badlands Plateau"); Minecraft keys are snake_case.
        String path = org.seedatlas.xaero.detail.BiomeNames.snake(sanitized);
        String legacy = org.seedatlas.xaero.detail.BiomeNames.legacyChinese(path);
        if (legacy != null) {
            return Component.literal(legacy);
        }
        String[] words = path.split("_");
        StringBuilder fallback = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (!fallback.isEmpty()) fallback.append(' ');
            fallback.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return Component.translatableWithFallback(
            "biome.minecraft." + path,
            fallback.isEmpty() ? path : fallback.toString()
        );
    }

    /**
     * Every biome of the active world's Minecraft version, sorted by resource name. Requires a native
     * session, so the list is empty while no seed is configured.
     */
    public static List<BiomeSample> availableBiomes() {
        GameVersion version = SeedAtlasClientState.activeGameVersion();
        Map.Entry<GameVersion, List<BiomeSample>> cached = availableBiomes;
        if (cached != null && cached.getKey().equals(version)) {
            return cached.getValue();
        }
        List<BiomeSample> found = withAnySession(session -> {
            List<BiomeSample> biomes = new ArrayList<>();
            for (int id = 0; id < BiomeHighlighter.BIOME_ID_LIMIT; ++id) {
                String name = session.biomeName(id);
                if (name == null || name.contains("unknown_")) {
                    continue;
                }
                // Legacy 1.17-era ids are not registered in this version: hide them so only
                // highlightable biomes remain (and no black colour swatches).
                if (!org.seedatlas.xaero.detail.BiomeNames.existsInGame(name)) {
                    continue;
                }
                biomes.add(new BiomeSample(id, name, session.biomeColor(id)));
            }
            biomes.sort(Comparator.comparing(BiomeSample::name));
            return List.copyOf(biomes);
        });
        if (found == null || found.isEmpty()) {
            return List.of();
        }
        availableBiomes = Map.entry(version, found);
        return found;
    }

    /** Runs against the engine for the seed that generates {@code dimension} in the active context. */
    public static <T> T withSession(Dimension dimension, SessionOperation<T> operation) {
        OptionalLong seed = SeedAtlasClientState.activeSeed(dimension);
        if (seed.isEmpty()) {
            return null;
        }
        return withSession(seed.getAsLong(), SeedAtlasClientState.config().largeBiomes(),
            SeedAtlasClientState.activeGameVersion(), operation);
    }

    /** For seed-independent queries such as biome names and colours. */
    public static <T> T withAnySession(SessionOperation<T> operation) {
        for (Dimension dimension : Dimension.values()) {
            if (SeedAtlasClientState.activeSeed(dimension).isPresent()) {
                return withSession(dimension, operation);
            }
        }
        return null;
    }

    /** Runs against the engine for an explicit seed, e.g. the one a cached tile was keyed with. */
    public static <T> T withSession(long seed, boolean largeBiomes, GameVersion gameVersion,
                                    SessionOperation<T> operation) {
        SeedAtlasNative nativeSession;
        synchronized (NATIVE_LOCK) {
            nativeSession = session(seed, largeBiomes, gameVersion);
            if (nativeSession == null) {
                return null;
            }
        }
        try {
            return operation.apply(nativeSession);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static Dimension nativeDimension(ResourceKey<Level> dimension) {
        if (Level.OVERWORLD.equals(dimension)) {
            return Dimension.OVERWORLD;
        }
        if (Level.NETHER.equals(dimension)) {
            return Dimension.NETHER;
        }
        if (Level.END.equals(dimension)) {
            return Dimension.END;
        }
        return null;
    }

    public static int configuredWorkerThreads() {
        int configured = SeedAtlasClientState.config().workerThreads();
        if (configured > 0) {
            return Math.clamp(configured, 1, 8);
        }
        return Math.clamp(Runtime.getRuntime().availableProcessors() - 2, 2, 8);
    }

    public static double observedMapScale() {
        return observedMapScale;
    }

    /** Called with NATIVE_LOCK held. */
    private static SeedAtlasNative session(long seed, boolean largeBiomes, GameVersion gameVersion) {
        SessionKey key = new SessionKey(seed, largeBiomes, gameVersion);
        SeedAtlasNative cached = SESSIONS.get(key);
        if (cached != null) {
            return cached;
        }
        SeedAtlasNative opened;
        try {
            WorldType worldType = largeBiomes ? WorldType.LARGE_BIOMES : WorldType.NORMAL;
            opened = SeedAtlasNative.open(seed, worldType, gameVersion);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
        if (SESSIONS.size() >= MAX_SESSIONS) {
            var eldest = SESSIONS.entrySet().iterator().next();
            SESSIONS.remove(eldest.getKey());
            closeSession(eldest.getValue());
        }
        SESSIONS.put(key, opened);
        return opened;
    }

    /** In-flight calls finish first: closing waits for the session's lifecycle lock. */
    private static void closeSession(SeedAtlasNative session) {
        try {
            session.close();
        } catch (Exception ignored) {
            // Native cleanup cannot be allowed to take the client down.
        }
    }

    private record SessionKey(long seed, boolean largeBiomes, GameVersion gameVersion) { }

    private static String contextDigest(String worldId, String multiworldId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(worldId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            if (multiworldId == null) {
                digest.update((byte)0);
            } else {
                digest.update((byte)1);
                digest.update(multiworldId.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    public interface SessionOperation<T> {
        T apply(SeedAtlasNative session);
    }
}

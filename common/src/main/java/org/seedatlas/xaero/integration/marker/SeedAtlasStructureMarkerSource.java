package org.seedatlas.xaero.integration.marker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.nativeapi.BlockBox;
import org.seedatlas.xaero.nativeapi.BlockPos;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.seedatlas.xaero.nativeapi.ScanResult;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.SpawnMode;
import org.seedatlas.xaero.nativeapi.StructurePosition;
import org.seedatlas.xaero.nativeapi.StructureType;

/** Non-blocking, viewport-local and zoom-aware structure snapshots. */
final class SeedAtlasStructureMarkerSource {
    private static final int NORMAL_RESULTS_PER_TYPE = 1024;
    private static final int NORMAL_SLIME_RESULTS = 4096;
    private static final int MIN_GRID = 1024;
    private static final int MAX_GRID = 16384;
    private static final int MAX_RADIUS = 65536;
    private static final long SCAN_DEBOUNCE_MILLIS = 150L;
    private static final int MAX_STRUCTURE_WORKERS = 4;

    private static final ScheduledExecutorService SCAN_COORDINATOR =
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Seed Atlas structure coordinator");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });
    private static final ThreadPoolExecutor STRUCTURE_WORKERS = new ThreadPoolExecutor(
        2,
        MAX_STRUCTURE_WORKERS,
        30L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64),
        task -> {
            Thread thread = new Thread(task, "Seed Atlas structure worker");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy()
    );

    static {
        STRUCTURE_WORKERS.allowCoreThreadTimeOut(true);
    }

    private final AtomicLong generation = new AtomicLong();
    private final Object scheduleLock = new Object();
    private final Object cacheLock = new Object();
    private final LinkedHashMap<Request, List<SeedAtlasStructureMarker>> scanCache =
        new LinkedHashMap<>(8, 0.75f, true);
    private volatile Request requested;
    private ScheduledFuture<?> scheduledScan;

    void request(
        SeedAtlasStructureContext context,
        ResourceKey<Level> level,
        double cameraX,
        double cameraZ,
        double scale,
        int viewportWidth,
        int viewportHeight
    ) {
        SeedAtlasXaeroIntegration.observeMapView(
            level, cameraX, cameraZ, scale, viewportWidth, viewportHeight);
        if (!SeedAtlasXaeroIntegration.isLayerActive()
            || !SeedAtlasClientState.config().structuresEnabled()) {
            clear(context);
            return;
        }
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        OptionalLong seed = dimension == null ? OptionalLong.empty() : SeedAtlasClientState.activeSeed(dimension);
        if (dimension == null || seed.isEmpty()) {
            clear(context);
            return;
        }

        double safeScale = Math.max(0.001953125, scale);
        double estimatedRadius = Math.ceil(
            Math.max(viewportWidth, viewportHeight) / safeScale / 2.0 + MIN_GRID);
        int radius = (int)Math.min(MAX_RADIUS, Math.max(MIN_GRID, estimatedRadius));
        int grid = scanGrid(radius);
        radius = Math.min(MAX_RADIUS, Math.ceilDiv(radius, grid) * grid);
        int centerX = Math.floorDiv((int)Math.floor(cameraX), grid) * grid;
        int centerZ = Math.floorDiv((int)Math.floor(cameraZ), grid) * grid;
        Set<String> configured = SeedAtlasClientState.config().enabledMarkerIds();
        Set<String> enabled = zoomFilteredMarkers(configured, safeScale, radius);
        // Zoom visibility is a view concern: the snapshot stays in memory and the provider
        // filters it, so zooming back in shows markers again without waiting for a rescan.
        context.visibleMarkerIds = enabled;

        Request next = new Request(
            seed.getAsLong(),
            SeedAtlasClientState.config().largeBiomes(),
            SeedAtlasClientState.activeGameVersion(),
            dimension,
            level,
            centerX - radius,
            centerZ - radius,
            centerX + radius,
            centerZ + radius,
            radius,
            enabled,
            configured
        );
        adjustWorkerParallelism();
        Request previous = this.requested;
        if (next.equals(previous)) {
            return;
        }
        this.requested = next;
        if (previous == null || !next.hasSameMarkerIdentity(previous)) {
            context.markers = List.of();
        }
        if (enabled.isEmpty()) {
            return;
        }
        List<SeedAtlasStructureMarker> cached = cached(next);
        if (cached != null) {
            context.markers = mergeWithSkipped(context.markers, cached, next.enabled);
            return;
        }

        long requestedGeneration = this.generation.incrementAndGet();
        synchronized (this.scheduleLock) {
            if (this.scheduledScan != null) {
                this.scheduledScan.cancel(false);
            }
            this.scheduledScan = SCAN_COORDINATOR.schedule(() -> {
                BooleanSupplier current = () -> requestedGeneration == this.generation.get()
                    && next.equals(this.requested);
                if (!current.getAsBoolean()) {
                    return;
                }
                List<SeedAtlasStructureMarker> scanned = scan(next, current);
                if (current.getAsBoolean()) {
                    cache(next, scanned);
                    context.markers = mergeWithSkipped(context.markers, scanned, next.enabled);
                }
            }, SCAN_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void clear(SeedAtlasStructureContext context) {
        context.visibleMarkerIds = Set.of();
        if (this.requested == null && context.markers.isEmpty()) {
            return;
        }
        this.requested = null;
        this.generation.incrementAndGet();
        context.markers = List.of();
        synchronized (this.scheduleLock) {
            if (this.scheduledScan != null) {
                this.scheduledScan.cancel(false);
                this.scheduledScan = null;
            }
        }
    }

    /**
     * Keeps the marker types a scan skipped because the current zoom hides them, so zooming
     * back in shows them immediately instead of waiting for the next viewport scan.
     */
    private static List<SeedAtlasStructureMarker> mergeWithSkipped(
        List<SeedAtlasStructureMarker> previous,
        List<SeedAtlasStructureMarker> scanned,
        Set<String> scannedTypes
    ) {
        if (previous.isEmpty()) {
            return scanned;
        }
        List<SeedAtlasStructureMarker> merged = new ArrayList<>(scanned.size() + previous.size());
        merged.addAll(scanned);
        for (SeedAtlasStructureMarker marker : previous) {
            if (!scannedTypes.contains(marker.type().id())) {
                merged.add(marker);
            }
        }
        return List.copyOf(merged);
    }

    private List<SeedAtlasStructureMarker> cached(Request request) {
        synchronized (this.cacheLock) {
            return this.scanCache.get(request);
        }
    }

    private void cache(Request request, List<SeedAtlasStructureMarker> markers) {
        synchronized (this.cacheLock) {
            this.scanCache.put(request, markers);
            int limit = 8;
            while (this.scanCache.size() > limit) {
                var iterator = this.scanCache.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static void adjustWorkerParallelism() {
        int totalBudget = SeedAtlasXaeroIntegration.configuredWorkerThreads();
        int desired = totalBudget <= 1 ? 1 : Math.min(2, totalBudget / 2);
        desired = Math.min(MAX_STRUCTURE_WORKERS, Math.max(1, desired));
        if (STRUCTURE_WORKERS.getCorePoolSize() != desired) {
            STRUCTURE_WORKERS.setCorePoolSize(desired);
        }
    }

    private static int scanGrid(int radius) {
        int target = Math.clamp(radius / 4, MIN_GRID, MAX_GRID);
        return Integer.highestOneBit(target);
    }

    private static List<SeedAtlasStructureMarker> scan(
        Request request, BooleanSupplier current
    ) {
        // Reuse the shared SeedAtlasNative session. Opening a second engine
        // context per scan was both slow and contended with biome workers.
        BlockBox box = new BlockBox(request.minX, request.minZ, request.maxX, request.maxZ);
        List<Future<List<SeedAtlasStructureMarker>>> futures = new ArrayList<>();
        List<List<SeedAtlasStructureMarker>> immediate = new ArrayList<>();
        for (MarkerType marker : MarkerType.all()) {
            if (!current.getAsBoolean()) {
                break;
            }
            if (!request.enabled.contains(marker.id())
                || !belongsToDimension(marker, request.dimension)) {
                continue;
            }
            try {
                futures.add(STRUCTURE_WORKERS.submit(
                    () -> scanMarker(request, box, marker, current)));
            } catch (RejectedExecutionException exception) {
                // At most one viewport scan is coordinated at a time. If
                // the small bounded queue fills, finish that marker here.
                immediate.add(scanMarker(request, box, marker, current));
            }
        }

        List<SeedAtlasStructureMarker> markers = new ArrayList<>();
        for (List<SeedAtlasStructureMarker> result : immediate) {
            markers.addAll(result);
        }
        for (Future<List<SeedAtlasStructureMarker>> future : futures) {
            if (!current.getAsBoolean()) {
                future.cancel(false);
                continue;
            }
            try {
                markers.addAll(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException | RuntimeException ignored) {
                // One unavailable engine capability must not hide others.
            }
        }
        return current.getAsBoolean() ? List.copyOf(markers) : List.of();
    }

    private static List<SeedAtlasStructureMarker> scanMarker(
        Request request,
        BlockBox box,
        MarkerType marker,
        BooleanSupplier current
    ) {
        if (!current.getAsBoolean()) {
            return List.of();
        }
        try {
            List<SeedAtlasStructureMarker> result = SeedAtlasXaeroIntegration.withSession(request.seed, request.largeBiomes, request.gameVersion,
                session -> scanWithSession(session, request.level, request.dimension, box, marker));
            if (!current.getAsBoolean() || result == null) {
                return List.of();
            }
            return result;
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    static List<SeedAtlasStructureMarker> scanWithSession(
        SeedAtlasNative session, ResourceKey<Level> level, Dimension dimension,
        BlockBox box, MarkerType marker
    ) {
        if (marker == MarkerType.SPAWN) {
            BlockPos spawn = session.spawn(SpawnMode.ESTIMATED);
            if (spawn != null && box.contains(spawn.x(), spawn.z())) {
                return List.of(new SeedAtlasStructureMarker(
                    marker, spawn.x(), spawn.y(), spawn.z(), level, true));
            }
            return List.of();
        }

        int resultLimit = NORMAL_RESULTS_PER_TYPE;
        ScanResult scanResult;
        if (marker == MarkerType.STRONGHOLD) {
            scanResult = session.strongholds(box, resultLimit, true);
        } else if (marker == MarkerType.SLIME_CHUNK) {
            scanResult = session.slimeChunks(box, NORMAL_SLIME_RESULTS);
        } else {
            StructureType structureType = structureType(marker, dimension);
            if (structureType == null) {
                return List.of();
            }
            scanResult = session.structures(structureType, box, resultLimit, true);
        }
        return markersFromScan(marker, level, scanResult, session);
    }

    private static List<SeedAtlasStructureMarker> markersFromScan(
        MarkerType marker, ResourceKey<Level> dimension, ScanResult result, SeedAtlasNative session
    ) {
        if (result == null || result.positions() == null) {
            return List.of();
        }
        List<SeedAtlasStructureMarker> output = new ArrayList<>(result.positions().size());
        for (StructurePosition position : result.positions()) {
            output.add(new SeedAtlasStructureMarker(
                marker,
                position.x(),
                position.y(),
                position.z(),
                dimension,
                position.approximate(),
                position.campBiomeId() >= 0 ? session.biomeName(position.campBiomeId()) : null,
                position.hasSpecialLoot(),
                position.type() == StructureType.ORE_VEIN_COPPER
            ));
        }
        return output;
    }

    private static Set<String> zoomFilteredMarkers(
        Set<String> configured, double scale, int radius
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (MarkerType marker : MarkerType.all()) {
            if (configured.contains(marker.id()) && visibleAtZoom(marker, scale, radius)) {
                result.add(marker.id());
            }
        }
        return Set.copyOf(result);
    }

    private static boolean visibleAtZoom(MarkerType marker, double scale, int radius) {
        return switch (marker) {
            case SLIME_CHUNK -> scale >= 0.5 && radius <= 2048;
            case ORE_VEIN, GEODE, DESERT_WELL, BURIED_TREASURE ->
                scale >= 0.25 && radius <= 8192;
            case MINESHAFT -> scale >= 0.25 && radius <= 12288;
            case ABANDONED_CAMP, VILLAGE, SHIPWRECK, OCEAN_RUINS, RUINED_PORTAL, TRAIL_RUINS,
                 TRIAL_CHAMBERS, PILLAGER_OUTPOST, DESERT_PYRAMID,
                 JUNGLE_TEMPLE, SWAMP_HUT, IGLOO ->
                scale >= 0.125 && radius <= 24576;
            default -> scale >= 0.03125 || isLongRangeLandmark(marker);
        };
    }

    private static boolean isLongRangeLandmark(MarkerType marker) {
        return marker == MarkerType.SPAWN
            || marker == MarkerType.STRONGHOLD
            || marker == MarkerType.WOODLAND_MANSION
            || marker == MarkerType.OCEAN_MONUMENT
            || marker == MarkerType.ANCIENT_CITY
            || marker == MarkerType.NETHER_FORTRESS
            || marker == MarkerType.BASTION
            || marker == MarkerType.END_CITY
            || marker == MarkerType.END_GATEWAY;
    }

    static boolean belongsToDimension(MarkerType marker, Dimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> marker != MarkerType.BASTION
                && marker != MarkerType.NETHER_FORTRESS
                && marker != MarkerType.END_CITY
                && marker != MarkerType.END_SHIP
                && marker != MarkerType.END_GATEWAY;
            case NETHER -> marker == MarkerType.BASTION
                || marker == MarkerType.NETHER_FORTRESS
                || marker == MarkerType.RUINED_PORTAL;
            case END -> marker == MarkerType.END_CITY
                || marker == MarkerType.END_SHIP
                || marker == MarkerType.END_GATEWAY;
        };
    }

    private static StructureType structureType(MarkerType marker, Dimension dimension) {
        if (marker == MarkerType.RUINED_PORTAL && dimension == Dimension.NETHER) {
            return firstStructureType(dimension, "NETHER_RUINED_PORTAL", "RUINED_PORTAL_NETHER");
        }
        String[] candidates = switch (marker) {
            case BASTION -> new String[] {"BASTION", "BASTION_REMNANT"};
            case OCEAN_RUINS -> new String[] {"OCEAN_RUINS", "OCEAN_RUIN"};
            case NETHER_FORTRESS -> new String[] {"NETHER_FORTRESS", "FORTRESS"};
            case GEODE -> new String[] {"GEODE", "AMETHYST_GEODE"};
            case ORE_VEIN -> new String[] {"ORE_VEIN", "ORE_VEIN_COPPER", "ORE_VEIN_IRON"};
            default -> new String[] {marker.name()};
        };
        return firstStructureType(dimension, candidates);
    }

    private static StructureType firstStructureType(Dimension dimension, String... candidates) {
        for (String candidate : candidates) {
            try {
                StructureType type = StructureType.valueOf(candidate.toUpperCase(Locale.ROOT));
                if (type.dimension() == dimension) {
                    return type;
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next compatibility alias.
            }
        }
        return null;
    }

    private record Request(
        long seed,
        boolean largeBiomes,
        GameVersion gameVersion,
        Dimension dimension,
        ResourceKey<Level> level,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        int radius,
        Set<String> enabled,
        Set<String> configured
    ) {
        /** Identity ignores the zoom filter so zoom changes never discard the snapshot. */
        private boolean hasSameMarkerIdentity(Request other) {
            return this.seed == other.seed
                && this.largeBiomes == other.largeBiomes
                && this.gameVersion.equals(other.gameVersion)
                && this.dimension == other.dimension
                && this.level.equals(other.level)
                && this.configured.equals(other.configured);
        }
    }
}

package org.seedatlas.xaero.integration.biome;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.nativeapi.BiomeRegion;
import org.seedatlas.xaero.nativeapi.BiomeSample;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.joml.Matrix4f;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.WorldMapSession;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Adaptive viewport raster rendered underneath Xaero's explored terrain. Native and GPU work
 * stays proportional to screen pixels: a visible tile holds 256x256 close-up or 128x128
 * coarse samples at any zoom.
 */
public final class SeedAtlasBiomeOverlayRenderer extends ElementRenderer<
    SeedAtlasBiomeOverlayRenderer.OverlayProbe,
    SeedAtlasBiomeOverlayRenderer.OverlayContext,
    SeedAtlasBiomeOverlayRenderer
> {
    public static final SeedAtlasBiomeOverlayRenderer INSTANCE =
        new SeedAtlasBiomeOverlayRenderer();

    private static final int FINE_TEXTURE_SIZE = 256;
    private static final int COARSE_TEXTURE_SIZE = 128;
    private static final int MAX_NATIVE_WORKERS = 8;
    private static final int PENDING_LIMIT = 256;
    private static final int CACHE_LIMIT = 160;
    private static final int READY_LIMIT = 192;
    private static final int VIEW_TILE_LIMIT = 1024;
    private static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long VIEW_RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long BACKGROUND_RECHECK_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long UPLOAD_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(6);
    private static final int UPLOADS_PER_FRAME = 8;

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
        2,
        MAX_NATIVE_WORKERS,
        20L,
        TimeUnit.SECONDS,
        new PriorityBlockingQueue<>(),
        task -> {
            Thread thread = new Thread(task, "Seed Atlas viewport tile worker");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    );

    static {
        WORKERS.allowCoreThreadTimeOut(true);
    }

    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong taskSequence = new AtomicLong();
    private final AtomicInteger refinementsRunning = new AtomicInteger();
    private volatile boolean coveragePending;
    private final ConcurrentHashMap<TileKey, TileTask> pending = new ConcurrentHashMap<>();
    private final Set<TileKey> readyKeys = ConcurrentHashMap.newKeySet();
    private final PriorityBlockingQueue<CpuTile> readyForUpload =
        new PriorityBlockingQueue<>(32, Comparator
            .comparingInt(CpuTile::category)
            .thenComparingLong(CpuTile::distance)
            .thenComparingLong(CpuTile::sequence));
    private final ConcurrentHashMap<TileKey, Long> failedUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> biomeNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Integer> biomeColors = new ConcurrentHashMap<>();

    /** Access-ordered and touched only from Minecraft's render/client thread. */
    private final LinkedHashMap<TileKey, GpuTile> textureCache =
        new LinkedHashMap<>(64, 0.75F, true);

    private volatile boolean mapActive;
    private volatile int netherSurfaceY = 64;
    private volatile GenerationSignature generationSignature;
    /** Empty while highlighting is off; otherwise a 256-entry lookup for the tile workers. */
    private volatile boolean[] highlightMask = new boolean[0];
    private volatile ViewRequest currentView;
    private volatile Set<TileKey> viewportDesired = Set.of();
    private volatile Set<TileKey> backgroundDesired = Set.of();
    private volatile long lastRevision = Long.MIN_VALUE;
    private long lastViewEnsureNanos;
    private long lastBackgroundEnsureNanos;
    private BackgroundRequest lastBackgroundRequest;

    private SeedAtlasBiomeOverlayRenderer() {
        this(new OverlayContext(), new ProbeProvider(), new ProbeReader());
    }

    private SeedAtlasBiomeOverlayRenderer(
        OverlayContext context,
        ProbeProvider provider,
        ProbeReader reader
    ) {
        super(context, provider, reader);
    }

    public void setMapActive(boolean active) {
        this.mapActive = active;
        if (!active) {
            this.context.slices = List.of();
            this.currentView = null;
            this.viewportDesired = Set.of();
            cancelQueuedViewportTasks(Set.of());
        }
    }

    /**
     * Hard-idle used when the world map closes or Seed Atlas settings open.
     * Cancels queued native work and drops pending GPU uploads so the GPU/CPU
     * are not kept busy under menus or during normal gameplay.
     */
    public void idleCompletely() {
        this.mapActive = false;
        this.context.slices = List.of();
        this.currentView = null;
        this.lastBackgroundRequest = null;
        this.viewportDesired = Set.of();
        this.backgroundDesired = Set.of();
        cancelAllQueuedTasks();
        this.readyForUpload.clear();
        this.readyKeys.clear();
        // Shrink the pool so idle cores do not keep spinning worker threads.
        if (WORKERS.getCorePoolSize() != 1) {
            WORKERS.setCorePoolSize(1);
        }
        WORKERS.purge();
    }

    /** Called from the client thread whenever the persisted state revision changes. */
    public void onStateRevision(long revision) {
        if (revision == this.lastRevision) {
            return;
        }
        this.lastRevision = revision;
        GenerationSignature next = currentGenerationSignature();
        boolean worldChanged = !next.equals(this.generationSignature);
        this.generationSignature = next;
        this.highlightMask = BiomeHighlighter.mask(
            SeedAtlasClientState.config().biomeHighlightEnabled(),
            SeedAtlasClientState.config().highlightedBiomes()
        );
        adjustParallelism();

        if (worldChanged) {
            this.generation.incrementAndGet();
            cancelAllQueuedTasks();
            this.readyForUpload.clear();
            this.readyKeys.clear();
            this.failedUntil.clear();
            this.biomeNames.clear();
            this.biomeColors.clear();
            this.currentView = null;
            this.lastBackgroundRequest = null;
            this.viewportDesired = Set.of();
            this.backgroundDesired = Set.of();
            runOnClientThread(this::closeAllTextures);
        } else {
            // Performance settings can change the requested LOD/cache budget.
            this.currentView = null;
            runOnClientThread(() -> trimTextureCache(Set.of()));
        }

        if (!SeedAtlasXaeroIntegration.isLayerConfigured()) {
            cancelAllQueuedTasks();
            this.context.slices = List.of();
        }
    }

    /** Schedules a GUI-sized viewport, with complete parent LODs ahead of detail. */
    public void observeView(
        ResourceKey<Level> level,
        double cameraX,
        double cameraZ,
        double scale,
        int viewportWidth,
        int viewportHeight
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !Double.isFinite(scale) || scale <= 0.0) {
            clearView();
            return;
        }
        if (!this.mapActive || !SeedAtlasXaeroIntegration.isLayerActive() || level == null) {
            return;
        }
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        OptionalLong seed = dimension == null ? OptionalLong.empty() : SeedAtlasClientState.activeSeed(dimension);
        if (dimension == null || seed.isEmpty()) {
            return;
        }
        adjustParallelism();

        int sampleStep = chooseSampleStep(scale);
        int textureSize = textureSizeForView(sampleStep, scale);
        int worldSize = Math.multiplyExact(sampleStep, textureSize);
        int sampleY = SeedAtlasXaeroIntegration.yFor(level);
        if (dimension == Dimension.NETHER) {
            var mapSession = WorldMapSession.getCurrentSession();
            if (mapSession != null && mapSession.getMapProcessor() != null) {
                sampleY = netherSurfaceY(sampleY, mapSession.getMapProcessor().getCurrentCaveLayer());
            }
            this.netherSurfaceY = sampleY;
        }

        double halfWidth = viewportWidth / scale / 2.0;
        double halfHeight = viewportHeight / scale / 2.0;
        if (!prepareView(cameraX, cameraZ, halfWidth, halfHeight, worldSize)) {
            return;
        }
        int minTileX = floorTile(cameraX - halfWidth, worldSize);
        int maxTileX = floorTile(cameraX + halfWidth, worldSize);
        int minTileZ = floorTile(cameraZ - halfHeight, worldSize);
        int maxTileZ = floorTile(cameraZ + halfHeight, worldSize);
        ViewRequest next = new ViewRequest(
            seed.getAsLong(),
            SeedAtlasClientState.config().largeBiomes(),
            SeedAtlasClientState.activeGameVersion(),
            dimension,
            level,
            sampleY,
            sampleStep,
            textureSize,
            minTileX,
            maxTileX,
            minTileZ,
            maxTileZ,
            cameraX,
            cameraZ,
            scale,
            viewportWidth,
            viewportHeight
        );

        long now = System.nanoTime();
        ViewRequest previous = this.currentView;
        boolean changed = previous == null || !next.sameGrid(previous);
        this.currentView = next;
        if (changed || now - this.lastViewEnsureNanos >= VIEW_RECHECK_NANOS) {
            this.lastViewEnsureNanos = now;
            scheduleView(next, changed);
        }
    }

    /**
     * Optional low-priority ring around the player, only while the full map is
     * open and Seed Atlas settings are not covering it. Never runs during normal
     * gameplay, where continuous prefetching kept the GPU busy.
     */
    public void tickBackground(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null
            || !this.mapActive || this.currentView == null
            || SeedAtlasXaeroIntegration.isHeavyWorkPaused()
            || !SeedAtlasXaeroIntegration.isLayerConfigured()) {
            clearBackgroundWork();
            return;
        }
        int radius = SeedAtlasClientState.config().prefetchRadius();
        if (radius <= 0) {
            clearBackgroundWork();
            return;
        }
        ResourceKey<Level> level = minecraft.level.dimension();
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        OptionalLong seed = dimension == null ? OptionalLong.empty() : SeedAtlasClientState.activeSeed(dimension);
        if (dimension == null || seed.isEmpty()) {
            return;
        }

        int step = normalizeConfiguredStep(SeedAtlasClientState.config().biomeResolution());
        int textureSize = textureSizeForParent(step);
        int worldSize = Math.multiplyExact(step, textureSize);
        int centerTileX = floorTile(minecraft.player.getX(), worldSize);
        int centerTileZ = floorTile(minecraft.player.getZ(), worldSize);
        BackgroundRequest next = new BackgroundRequest(
            seed.getAsLong(), level, step, textureSize, centerTileX, centerTileZ, radius);
        long now = System.nanoTime();
        if (next.equals(this.lastBackgroundRequest)
            && now - this.lastBackgroundEnsureNanos < BACKGROUND_RECHECK_NANOS) {
            return;
        }
        this.lastBackgroundRequest = next;
        this.lastBackgroundEnsureNanos = now;
        adjustParallelism();

        Set<TileKey> desired = new HashSet<>();
        List<TaskRequest> requests = new ArrayList<>();
        for (int dz = -radius; dz <= radius; ++dz) {
            for (int dx = -radius; dx <= radius; ++dx) {
                TileKey key = tileKey(
                    seed.getAsLong(),
                    SeedAtlasClientState.config().largeBiomes(),
                    SeedAtlasClientState.activeGameVersion(),
                    dimension,
                    level,
                    dimension == Dimension.NETHER ? this.netherSurfaceY : SeedAtlasXaeroIntegration.yFor(level),
                    step,
                    textureSize,
                    centerTileX + dx,
                    centerTileZ + dz
                );
                desired.add(key);
                requests.add(new TaskRequest(key, 3, ringDistance(dx, dz)));
            }
        }
        this.backgroundDesired = Set.copyOf(desired);
        pruneBackgroundWork(this.backgroundDesired);
        long activeGeneration = this.generation.get();
        for (TaskRequest request : requests) {
            schedule(request.key, request.category, request.distance, activeGeneration);
        }
    }

    /** Cached-only lookup used by the interactable overlay probe. */
    public BiomeSample biomeAtCached(
        ResourceKey<Level> level, int blockX, int blockZ
    ) {
        if (!Minecraft.getInstance().isSameThread() || level == null) {
            return null;
        }
        GpuTile best = null;
        for (GpuTile tile : new ArrayList<>(this.textureCache.values())) {
            if (!tile.key.level.equals(level) || !tile.contains(blockX, blockZ)) {
                continue;
            }
            if (best == null || tile.key.sampleStep < best.key.sampleStep) {
                best = tile;
            }
        }
        if (best == null) {
            return null;
        }
        // Touch the access-ordered cache after iteration has finished.
        best = this.textureCache.get(best.key);
        int id = best.biomeIdAt(blockX, blockZ);
        if (id < 0) {
            return null;
        }
        String name = this.biomeNames.get(id);
        if (name == null) {
            name = SeedAtlasXaeroIntegration.withAnySession(session -> session.biomeName(id));
            if (name == null) {
                return null;
            }
            name = sanitizeSingleLine(name);
            this.biomeNames.putIfAbsent(id, name);
        }
        Integer color = this.biomeColors.get(id);
        if (color == null) {
            color = SeedAtlasXaeroIntegration.withAnySession(session -> session.biomeColor(id));
            if (color == null) {
                color = 0;
            }
            this.biomeColors.putIfAbsent(id, color);
        }
        return new BiomeSample(id, name, color);
    }

    /**
     * Draws in Xaero's terrain framebuffer: below explored terrain normally, above it in
     * biome focus mode. The pose already includes FBO zoom and fractional offsets.
     * The two calls are mutually exclusive and use the same viewport and texture cache.
     */
    public void renderBackground(
        PoseStack pose, ResourceKey<Level> dimension,
        double cameraX, double cameraZ, double scale,
        int flooredCameraX, int flooredCameraZ,
        MultiTextureRenderTypeRendererProvider rendererProvider, boolean aboveTerrain
    ) {
        if (!this.mapActive || SeedAtlasXaeroIntegration.isHeavyWorkPaused()
            || !SeedAtlasXaeroIntegration.isLayerActive()) {
            this.context.slices = List.of();
            return;
        }
        // Focus is opaque; the optional explored overlay uses the normal opacity.
        boolean focused = this.highlightMask.length != 0;
        boolean overlayExplored = SeedAtlasClientState.config().overlayExplored();
        if (aboveTerrain != (focused || overlayExplored)) return;
        Minecraft minecraft = Minecraft.getInstance();
        observeView(dimension, cameraX, cameraZ, scale,
            guiWidth(minecraft), guiHeight(minecraft));
        drainUploads();
        ViewRequest view = this.currentView;
        this.context.slices = view == null ? List.of() : buildSlices(view);
        trimTextureCache(protectedTileKeys(this.context.slices));

        MultiTextureRenderTypeRenderer renderer =
            rendererProvider.getRenderer(CustomRenderTypes.GUI_NEAREST);
        Matrix4f matrix = pose.last().pose();
        float alpha = focused ? 1.0F : SeedAtlasClientState.config().opacityFraction();
        float depth = focused || overlayExplored ? 1.0F : -1.0F;
        for (OverlaySlice slice : this.context.slices) {
            float left = (float)(slice.minX - flooredCameraX);
            float right = (float)(slice.maxX - flooredCameraX);
            float top = (float)(slice.minZ - flooredCameraZ);
            float bottom = (float)(slice.maxZ - flooredCameraZ);
            float textureSize = slice.source.key.textureSize;
            float u0 = slice.u0 / textureSize;
            float u1 = slice.u1 / textureSize;
            float v0 = slice.v0 / textureSize;
            float v1 = slice.v1 / textureSize;
            BufferBuilder buffer = renderer.begin(slice.source.texture.getTextureView());
            // Terrain is at Z=0; the focus pass must cover explored terrain as well.
            buffer.addVertex(matrix, left, bottom, depth)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(u0, v1);
            buffer.addVertex(matrix, right, bottom, depth)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(u1, v1);
            buffer.addVertex(matrix, right, top, depth)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(u1, v0);
            buffer.addVertex(matrix, left, top, depth)
                .setColor(1.0F, 1.0F, 1.0F, alpha).setUv(u0, v0);
        }
        // Flush while the terrain framebuffer and its projection are active.
        rendererProvider.draw(renderer);
    }

    @Override
    public void preRender(
        ElementRenderInfo renderInfo,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider,
        boolean shadow
    ) {
        if (!this.mapActive || SeedAtlasXaeroIntegration.isHeavyWorkPaused()
            || !SeedAtlasXaeroIntegration.isLayerActive()) {
            this.context.dimension = null;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        this.context.dimension = renderInfo.mapDimension;
        this.context.cameraX = renderInfo.renderPos.x;
        this.context.cameraZ = renderInfo.renderPos.z;
        this.context.mouseX = renderInfo.mouseX;
        this.context.mouseZ = renderInfo.mouseZ;
        this.context.viewportWidth = guiWidth(minecraft);
        this.context.viewportHeight = guiHeight(minecraft);
    }

    @Override
    public void postRender(
        ElementRenderInfo renderInfo,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider,
        boolean shadow
    ) {
    }

    @Override
    public void renderElementShadow(
        OverlayProbe probe,
        boolean hovered,
        float optionalScale,
        double partialX,
        double partialY,
        ElementRenderInfo renderInfo,
        MapElementGraphics graphics,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider
    ) {
    }

    @Override
    public boolean renderElement(
        OverlayProbe probe,
        boolean hovered,
        double optionalDepth,
        float optionalScale,
        double partialX,
        double partialY,
        ElementRenderInfo renderInfo,
        MapElementGraphics graphics,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider
    ) {
        // This element only supplies the biome tooltip. The raster has already
        // been rendered in the terrain framebuffer by renderBackground.
        return false;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return location == ElementRenderLocation.WORLD_MAP
            && !shadow
            && SeedAtlasXaeroIntegration.isLayerActive();
    }

    @Override
    public boolean shouldRenderHovered(boolean pre) {
        // The tooltip probe has no visual hover pass.
        return false;
    }

    @Override
    public int getOrder() {
        // Let structure and waypoint hover targets take priority over this probe.
        return 100;
    }

    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    private void scheduleView(ViewRequest view, boolean gridChanged) {
        long activeGeneration = this.generation.get();
        Set<TileKey> desired = new HashSet<>();
        List<TaskRequest> requests = new ArrayList<>();
        int centerTileX = floorTile(view.cameraX, view.worldSize());
        int centerTileZ = floorTile(view.cameraZ, view.worldSize());

        // Seed Atlas style: fill the screen with one or two coarse parent LODs
        // first (very few native calls), then refine the current LOD centre-out.
        // Parents stay visible only until the exact cell is ready.
        int parentStep = nextParentStep(view.sampleStep);
        if (parentStep > view.sampleStep) {
            scheduleParentLevel(view, parentStep, 0, desired, requests);
            int grandparentStep = nextParentStep(parentStep);
            if (grandparentStep > parentStep && view.sampleStep <= 16) {
                scheduleParentLevel(view, grandparentStep, 0, desired, requests);
            }
        }

        // Current LOD is always scheduled centre-first. A single overscan ring
        // is lower priority so fast panning usually lands on a warm tile.
        for (int tileZ = view.minTileZ - 1; tileZ <= view.maxTileZ + 1; ++tileZ) {
            for (int tileX = view.minTileX - 1; tileX <= view.maxTileX + 1; ++tileX) {
                boolean visible = tileX >= view.minTileX && tileX <= view.maxTileX
                    && tileZ >= view.minTileZ && tileZ <= view.maxTileZ;
                TileKey key = tileKey(view, view.sampleStep, view.textureSize, tileX, tileZ);
                desired.add(key);
                requests.add(new TaskRequest(key, visible ? 1 : 2,
                    ringDistance(tileX - centerTileX, tileZ - centerTileZ)));
            }
        }

        // Highest priority first so a full pending queue never starves parents.
        requests.sort(Comparator
            .comparingInt(TaskRequest::category)
            .thenComparingLong(TaskRequest::distance));

        this.viewportDesired = Set.copyOf(desired);
        this.coveragePending = requests.stream()
            .anyMatch(request -> request.category < 2 && !this.textureCache.containsKey(request.key));
        cancelQueuedViewportTasks(gridChanged ? Set.of() : this.viewportDesired);
        pruneReadyViewport(this.viewportDesired);
        for (TaskRequest request : requests) {
            schedule(request.key, request.category, request.distance, activeGeneration);
        }
    }

    private void scheduleParentLevel(
        ViewRequest view,
        int parentStep,
        int category,
        Set<TileKey> desired,
        List<TaskRequest> requests
    ) {
        int parentTextureSize = textureSizeForParent(parentStep);
        int parentWorldSize = Math.multiplyExact(parentStep, parentTextureSize);
        int minParentX = Math.floorDiv(
            Math.multiplyExact(view.minTileX, view.worldSize()), parentWorldSize);
        int maxParentX = Math.floorDiv(
            Math.addExact(Math.multiplyExact(view.maxTileX + 1, view.worldSize()), -1),
            parentWorldSize);
        int minParentZ = Math.floorDiv(
            Math.multiplyExact(view.minTileZ, view.worldSize()), parentWorldSize);
        int maxParentZ = Math.floorDiv(
            Math.addExact(Math.multiplyExact(view.maxTileZ + 1, view.worldSize()), -1),
            parentWorldSize);
        int centerParentX = floorTile(view.cameraX, parentWorldSize);
        int centerParentZ = floorTile(view.cameraZ, parentWorldSize);
        for (int tileZ = minParentZ; tileZ <= maxParentZ; ++tileZ) {
            for (int tileX = minParentX; tileX <= maxParentX; ++tileX) {
                TileKey key = tileKey(view, parentStep, parentTextureSize, tileX, tileZ);
                if (!desired.add(key)) {
                    continue;
                }
                requests.add(new TaskRequest(key, category,
                    ringDistance(tileX - centerParentX, tileZ - centerParentZ)));
            }
        }
    }

    private void schedule(TileKey key, int category, long distance, long activeGeneration) {
        GpuTile cached = this.textureCache.get(key);
        // Cheap coverage first; only visible detailed Overworld tiles need a second pass.
        boolean refining = shouldRefine(cached != null, cached != null && cached.complete, category, this.coveragePending);
        if (activeGeneration != this.generation.get() || cached != null && !refining
            || this.readyKeys.contains(key)) {
            return;
        }
        Long retryAt = this.failedUntil.get(key);
        if (retryAt != null) {
            if (retryAt > System.nanoTime()) {
                return;
            }
            this.failedUntil.remove(key, retryAt);
        }
        if (this.pending.size() >= PENDING_LIMIT) {
            return;
        }

        TileTask task = new TileTask(
            key, activeGeneration, category, distance, this.taskSequence.incrementAndGet(), refining);
        TileTask previous = this.pending.putIfAbsent(key, task);
        if (previous != null) {
            if (!previous.started.get() && task.compareTo(previous) < 0
                && WORKERS.getQueue().remove(previous)
                && this.pending.replace(key, previous, task)) {
                WORKERS.execute(task);
            }
            return;
        }
        WORKERS.execute(task);
    }

    static boolean acceptUpload(boolean cached, boolean complete, boolean incomingComplete) {
        return !cached || !complete && incomingComplete;
    }

    static boolean shouldRefine(boolean cached, boolean complete, int category, boolean coveragePending) {
        return cached && !complete && category == 1 && !coveragePending;
    }

    /**
     * Tiles whose terrain costs far more than their biomes appear flat or previewed first and get
     * their terrain in a second pass: the Overworld's vanilla surface, the Nether's cave floor and
     * the End's island heights. Covering the screen first keeps all three dimensions equally quick.
     */
    static boolean needsSurfaceRefinement(Dimension dimension, boolean styled, boolean focused, int step) {
        if (!styled || focused) return false;
        return switch (dimension) {
            case OVERWORLD -> step <= 4;
            case NETHER -> step <= 16;
            case END -> step <= 8;
        };
    }

    static int tilePriority(int category, boolean refining) {
        return refining ? 3 : category >= 3 ? 4 : category;
    }

    private void cancelQueuedViewportTasks(Set<TileKey> desired) {
        for (Runnable queued : new ArrayList<>(WORKERS.getQueue())) {
            if (!(queued instanceof TileTask task) || task.category >= 3
                || desired.contains(task.key)) {
                continue;
            }
            if (WORKERS.getQueue().remove(task)) {
                this.pending.remove(task.key, task);
            }
        }
    }

    private void cancelAllQueuedTasks() {
        for (Runnable queued : new ArrayList<>(WORKERS.getQueue())) {
            if (queued instanceof TileTask task && WORKERS.getQueue().remove(task)) {
                this.pending.remove(task.key, task);
            }
        }
    }

    private void clearBackgroundWork() {
        if (this.backgroundDesired.isEmpty()) {
            return;
        }
        this.backgroundDesired = Set.of();
        this.lastBackgroundRequest = null;
        pruneBackgroundWork(Set.of());
    }

    private void pruneBackgroundWork(Set<TileKey> desired) {
        for (Runnable queued : new ArrayList<>(WORKERS.getQueue())) {
            if (!(queued instanceof TileTask task) || task.category < 3
                || desired.contains(task.key) || this.viewportDesired.contains(task.key)) {
                continue;
            }
            if (WORKERS.getQueue().remove(task)) {
                this.pending.remove(task.key, task);
            }
        }
        for (CpuTile cpu : new ArrayList<>(this.readyForUpload)) {
            if (cpu.category < 3 || desired.contains(cpu.key)
                || this.viewportDesired.contains(cpu.key)) {
                continue;
            }
            if (this.readyForUpload.remove(cpu)) {
                this.readyKeys.remove(cpu.key);
            }
        }
    }

    private void pruneReadyViewport(Set<TileKey> desired) {
        for (CpuTile cpu : new ArrayList<>(this.readyForUpload)) {
            if (cpu.category >= 3 || desired.contains(cpu.key)
                || this.backgroundDesired.contains(cpu.key)) {
                continue;
            }
            if (this.readyForUpload.remove(cpu)) {
                this.readyKeys.remove(cpu.key);
            }
        }
    }

    private void drainUploads() {
        if (!Minecraft.getInstance().isSameThread()
            || !this.mapActive
            || SeedAtlasXaeroIntegration.isHeavyWorkPaused()) {
            return;
        }
        int maximum = UPLOADS_PER_FRAME;
        long budget = UPLOAD_BUDGET_NANOS;
        long started = System.nanoTime();
        int uploaded = 0;
        while (uploaded < maximum && System.nanoTime() - started < budget) {
            CpuTile cpu = this.readyForUpload.poll();
            if (cpu == null) {
                break;
            }
            this.readyKeys.remove(cpu.key);
            GpuTile old = this.textureCache.get(cpu.key);
            if (cpu.generation != this.generation.get() || !acceptUpload(old != null, old != null && old.complete, cpu.complete)) {
                continue;
            }
            DynamicTexture texture = null;
            NativeImage image = null;
            try {
                int size = cpu.key.textureSize;
                image = new NativeImage(size, size, false);
                // Worker threads already converted ARGB -> ABGR. Bulk-copy into
                // NativeImage's native pixel buffer instead of 65k setPixel calls.
                IntBuffer pixels = image.getPixelBytes()
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer();
                pixels.put(0, cpu.abgr, 0, cpu.abgr.length);
                String textureLabel = "Seed Atlas biome tile "
                    + cpu.key.originBlockX + "," + cpu.key.originBlockZ;
                texture = new DynamicTexture(() -> textureLabel, image);
                // DynamicTexture uploaded synchronously in its constructor.
                // Its retained NativeImage is no longer needed for rendering.
                image.close();
                image = null;
                this.textureCache.put(cpu.key, new GpuTile(cpu.key, texture, cpu.biomeIds, cpu.complete));
                if (old != null) old.close();
                texture = null;
                ++uploaded;
            } catch (RuntimeException | LinkageError ignored) {
                if (texture != null) {
                    texture.close();
                }
                if (image != null) {
                    image.close();
                }
                this.failedUntil.put(cpu.key, System.nanoTime() + RETRY_DELAY_NANOS);
            }
        }
    }

    private List<OverlaySlice> buildSlices(ViewRequest view) {
        List<OverlaySlice> result = new ArrayList<>();
        List<GpuTile> snapshot = new ArrayList<>(this.textureCache.values());
        for (int tileZ = view.minTileZ; tileZ <= view.maxTileZ; ++tileZ) {
            for (int tileX = view.minTileX; tileX <= view.maxTileX; ++tileX) {
                long minX = (long)tileX * view.worldSize();
                long minZ = (long)tileZ * view.worldSize();
                long maxX = minX + view.worldSize();
                long maxZ = minZ + view.worldSize();
                TileKey exactKey = tileKey(
                    view, view.sampleStep, view.textureSize, tileX, tileZ);
                GpuTile exact = this.textureCache.get(exactKey);
                if (exact != null) {
                    result.add(slice(exact, minX, minZ, maxX, maxZ));
                    continue;
                }

                appendFallbackSlices(
                    result, snapshot, exactKey, view.sampleStep,
                    minX, minZ, maxX, maxZ, 0);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Fills one current-LOD cell without overlap. A containing parent is used
     * as one coherent fallback. If only old finer tiles exist, the power-of-two
     * cell is subdivided so mixed cached LODs can cover disjoint quadrants.
     */
    private void appendFallbackSlices(
        List<OverlaySlice> output,
        List<GpuTile> candidates,
        TileKey exactKey,
        int requestedStep,
        long minX,
        long minZ,
        long maxX,
        long maxZ,
        int depth
    ) {
        GpuTile containing = null;
        for (GpuTile candidate : candidates) {
            if (!candidate.key.sameWorld(exactKey)
                || !candidate.contains(minX, minZ, maxX, maxZ)) {
                continue;
            }
            if (containing == null
                || betterFallback(candidate, containing, requestedStep)) {
                containing = candidate;
            }
        }
        if (containing != null) {
            this.textureCache.get(containing.key);
            output.add(slice(containing, minX, minZ, maxX, maxZ));
            return;
        }

        if (depth >= 16 || maxX - minX <= 1 || maxZ - minZ <= 1) {
            return;
        }
        boolean hasIntersection = false;
        for (GpuTile candidate : candidates) {
            if (candidate.key.sameWorld(exactKey)
                && candidate.intersects(minX, minZ, maxX, maxZ)) {
                hasIntersection = true;
                break;
            }
        }
        if (!hasIntersection) {
            return;
        }

        long middleX = minX + (maxX - minX) / 2;
        long middleZ = minZ + (maxZ - minZ) / 2;
        appendFallbackSlices(output, candidates, exactKey, requestedStep,
            minX, minZ, middleX, middleZ, depth + 1);
        appendFallbackSlices(output, candidates, exactKey, requestedStep,
            middleX, minZ, maxX, middleZ, depth + 1);
        appendFallbackSlices(output, candidates, exactKey, requestedStep,
            minX, middleZ, middleX, maxZ, depth + 1);
        appendFallbackSlices(output, candidates, exactKey, requestedStep,
            middleX, middleZ, maxX, maxZ, depth + 1);
    }

    private static boolean betterFallback(
        GpuTile candidate, GpuTile current, int requestedStep
    ) {
        boolean candidateCoarse = candidate.key.sampleStep >= requestedStep;
        boolean currentCoarse = current.key.sampleStep >= requestedStep;
        if (candidateCoarse != currentCoarse) {
            return candidateCoarse;
        }
        if (candidate.key.sampleStep != current.key.sampleStep) {
            return candidateCoarse
                ? candidate.key.sampleStep < current.key.sampleStep
                : candidate.key.sampleStep > current.key.sampleStep;
        }
        return candidate.key.worldSize() < current.key.worldSize();
    }

    private static OverlaySlice slice(
        GpuTile source, long minX, long minZ, long maxX, long maxZ
    ) {
        float u0 = (float)(minX - source.key.originBlockX) / source.key.sampleStep;
        float v0 = (float)(minZ - source.key.originBlockZ) / source.key.sampleStep;
        float u1 = (float)(maxX - source.key.originBlockX) / source.key.sampleStep;
        float v1 = (float)(maxZ - source.key.originBlockZ) / source.key.sampleStep;
        return new OverlaySlice(source, minX, minZ, maxX, maxZ, u0, v0, u1, v1);
    }

    private static Set<TileKey> protectedTileKeys(List<OverlaySlice> slices) {
        Set<TileKey> keys = new HashSet<>();
        for (OverlaySlice slice : slices) {
            keys.add(slice.source.key);
        }
        return keys;
    }

    private void trimTextureCache(Set<TileKey> protectedKeys) {
        if (!Minecraft.getInstance().isSameThread()) {
            return;
        }
        int removable = this.textureCache.size() - CACHE_LIMIT;
        if (removable <= 0) {
            return;
        }
        Iterator<Map.Entry<TileKey, GpuTile>> iterator = this.textureCache.entrySet().iterator();
        while (iterator.hasNext() && removable > 0) {
            Map.Entry<TileKey, GpuTile> entry = iterator.next();
            if (protectedKeys.contains(entry.getKey())
                || this.viewportDesired.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            entry.getValue().close();
            --removable;
        }
    }

    private void closeAllTextures() {
        if (!Minecraft.getInstance().isSameThread()) {
            runOnClientThread(this::closeAllTextures);
            return;
        }
        for (GpuTile tile : this.textureCache.values()) {
            tile.close();
        }
        this.textureCache.clear();
        this.context.slices = List.of();
    }

    private void adjustParallelism() {
        if (!this.mapActive || SeedAtlasXaeroIntegration.isHeavyWorkPaused()) {
            if (WORKERS.getCorePoolSize() != 1) {
                WORKERS.setCorePoolSize(1);
            }
            return;
        }
        int workers = SeedAtlasXaeroIntegration.configuredWorkerThreads();
        if (SeedAtlasClientState.config().structuresEnabled() && workers > 2) {
            workers -= 2;
        }
        workers = Math.clamp(workers, 1, MAX_NATIVE_WORKERS);
        if (WORKERS.getCorePoolSize() != workers) {
            WORKERS.setCorePoolSize(workers);
        }
    }

    private static GenerationSignature currentGenerationSignature() {
        var config = SeedAtlasClientState.config();
        return new GenerationSignature(
            SeedAtlasClientState.activeSeed(Dimension.OVERWORLD),
            SeedAtlasClientState.activeSeed(Dimension.NETHER),
            SeedAtlasClientState.activeSeed(Dimension.END),
            config.largeBiomes(),
            SeedAtlasClientState.activeGameVersion(),
            config.overworldY(),
            config.netherY(),
            config.endY(),
            config.highlightSignature(),
            config.terrainStyle()
        );
    }

    private static int chooseSampleStep(double scale) {
        int automatic;
        if (scale > 0.5) {
            automatic = 1;
        } else if (scale > 0.125) {
            automatic = 4;
        } else if (scale > 0.03125) {
            automatic = 16;
        } else if (scale > 0.0078125) {
            automatic = 64;
        } else if (scale > 0.001953125) {
            automatic = 256;
        } else {
            automatic = 1024;
        }
        automatic = coarseSampleStep(automatic, scale);
        automatic = Math.max(
            automatic,
            normalizeConfiguredStep(SeedAtlasClientState.config().biomeResolution()));
        return automatic;
    }

    /** At distant zoom, sample no more finely than one screen pixel; retain native step limits. */
    static int coarseSampleStep(int step, double scale) {
        return step >= 64 && step < 1024 && step * scale < 1.0 ? step * 4 : step;
    }

    /** Xaero 1.47.1 uses MIN_VALUE for Full cave mode: scan below the Nether roof, not biome Y. */
    static int netherSurfaceY(int configuredY, int caveLayer) {
        return caveLayer == Integer.MIN_VALUE ? 127 : configuredY;
    }

    private static int normalizeConfiguredStep(int requested) {
        if (requested <= 1) return 1;
        if (requested <= 2) return 2;
        if (requested <= 4) return 4;
        if (requested <= 8) return 8;
        return 16;
    }

    private static int nextParentStep(int step) {
        if (step < 4) return 4;
        if (step < 16) return 16;
        if (step < 64) return 64;
        if (step < 256) return 256;
        return 1024;
    }

    private static int textureSizeForParent(int sampleStep) {
        return sampleStep < 4 ? FINE_TEXTURE_SIZE : COARSE_TEXTURE_SIZE;
    }

    private static int textureSizeForView(int sampleStep, double scale) {
        if (sampleStep < 4) {
            return FINE_TEXTURE_SIZE;
        }
        // 128 samples are substantially faster for spatially sparse climate
        // lookups. Switch to 256 only near the bottom half of an LOD band so
        // a tile never becomes smaller than roughly 128 physical pixels.
        return sampleStep * scale >= 1.0 ? COARSE_TEXTURE_SIZE : FINE_TEXTURE_SIZE;
    }

    private static int floorTile(double blockCoordinate, int worldSize) {
        long floored = (long)Math.floor(blockCoordinate);
        return (int)Math.floorDiv(floored, worldSize);
    }

    /** Native samples stop at step 1024; unlimited Xaero zoom must not enumerate an unlimited grid. */
    boolean prepareView(double cameraX, double cameraZ, double halfWidth, double halfHeight, int worldSize) {
        double minX = Math.floor((cameraX - halfWidth) / worldSize) - 1;
        double maxX = Math.floor((cameraX + halfWidth) / worldSize) + 1;
        double minZ = Math.floor((cameraZ - halfHeight) / worldSize) - 1;
        double maxZ = Math.floor((cameraZ + halfHeight) / worldSize) + 1;
        // Check before narrowing coordinates, allocating requests, or constructing parent LODs.
        if (Double.isFinite(cameraX) && Double.isFinite(cameraZ)
            && Double.isFinite(halfWidth) && Double.isFinite(halfHeight)
            && halfWidth >= 0 && halfHeight >= 0
            && (maxX - minX + 1) * (maxZ - minZ + 1) <= VIEW_TILE_LIMIT
            && minX * worldSize >= (double)Integer.MIN_VALUE + 2
            && minZ * worldSize >= (double)Integer.MIN_VALUE + 2
            && (maxX + 1) * worldSize - 1 <= Integer.MAX_VALUE
            && (maxZ + 1) * worldSize - 1 <= Integer.MAX_VALUE) {
            return true;
        }
        clearView();
        return false;
    }

    private void clearView() {
        this.currentView = null;
        this.context.slices = List.of();
        this.viewportDesired = Set.of();
        this.backgroundDesired = Set.of();
        this.lastBackgroundRequest = null;
        cancelAllQueuedTasks();
        this.readyForUpload.clear();
        this.readyKeys.clear();
    }

    private static long ringDistance(int dx, int dz) {
        long ax = Math.abs((long)dx);
        long az = Math.abs((long)dz);
        return Math.max(ax, az) * 1_000_000L + ax * ax + az * az;
    }

    private static TileKey tileKey(
        ViewRequest view, int sampleStep, int textureSize, int tileX, int tileZ
    ) {
        return tileKey(
            view.seed,
            view.largeBiomes,
            view.gameVersion,
            view.dimension,
            view.level,
            view.y,
            sampleStep,
            textureSize,
            tileX,
            tileZ
        );
    }

    private static TileKey tileKey(
        long seed,
        boolean largeBiomes,
        GameVersion gameVersion,
        Dimension dimension,
        ResourceKey<Level> level,
        int y,
        int sampleStep,
        int textureSize,
        int tileX,
        int tileZ
    ) {
        int worldSize = Math.multiplyExact(sampleStep, textureSize);
        long originX = (long)tileX * worldSize;
        long originZ = (long)tileZ * worldSize;
        if (originX < Integer.MIN_VALUE || originX > Integer.MAX_VALUE
            || originZ < Integer.MIN_VALUE || originZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Biome tile is outside integer world coordinates");
        }
        return new TileKey(
            seed,
            largeBiomes,
            gameVersion,
            dimension,
            level,
            y,
            sampleStep,
            textureSize,
            (int)originX,
            (int)originZ
        );
    }

    private static int guiWidth(Minecraft minecraft) {
        return Math.max(1, minecraft.getWindow().getWidth());
    }

    private static int guiHeight(Minecraft minecraft) {
        return Math.max(1, minecraft.getWindow().getHeight());
    }

    private static void runOnClientThread(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
    }

    private static String sanitizeSingleLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }


    private final class TileTask implements Runnable, Comparable<TileTask> {
        private final TileKey key;
        private final long generation;
        private final int category;
        private final long distance;
        private final long sequence;
        private final boolean[] highlightMask;
        private final boolean terrainStyle;
        private final boolean refining;
        private final AtomicBoolean started = new AtomicBoolean();

        private TileTask(
            TileKey key, long generation, int category, long distance, long sequence, boolean refining
        ) {
            this.key = key;
            this.generation = generation;
            this.category = category;
            this.distance = distance;
            this.sequence = sequence;
            this.refining = refining;
            this.highlightMask = SeedAtlasBiomeOverlayRenderer.this.highlightMask;
            // Versions before 1.18 have no noise-based terrain to predict: plain biome colours.
            this.terrainStyle = SeedAtlasClientState.config().terrainStyle() && key.gameVersion().hasTerrain();
        }

        @Override
        public int compareTo(TileTask other) {
            int byCategory = Integer.compare(tilePriority(this.category, this.refining), tilePriority(other.category, other.refining));
            if (byCategory != 0) return byCategory;
            int byDistance = Long.compare(this.distance, other.distance);
            return byDistance != 0 ? byDistance : Long.compare(this.sequence, other.sequence);
        }

        @Override
        public void run() {
            this.started.set(true);
            boolean refinementSlot = false;
            try {
                if (!isCurrent()) {
                    return;
                }
                if (this.refining) {
                    // Refinements take seconds; one worker stays free for the coverage of a newly panned view.
                    if (SeedAtlasBiomeOverlayRenderer.this.refinementsRunning.incrementAndGet()
                        > Math.max(1, WORKERS.getCorePoolSize() - 1)) {
                        SeedAtlasBiomeOverlayRenderer.this.refinementsRunning.decrementAndGet();
                        return;
                    }
                    refinementSlot = true;
                }
                float[][] heightsOut = new float[1][];
                OverworldTerrainSampler.SurfaceGrid surface = null;
                if (this.refining && this.terrainStyle && this.highlightMask.length == 0
                    && this.key.dimension == Dimension.OVERWORLD && this.key.sampleStep <= 4) {
                    try {
                        surface = OverworldTerrainSampler.sample(this.key.seed, this.key.largeBiomes,
                            this.key.originBlockX - this.key.sampleStep,
                            this.key.originBlockZ - this.key.sampleStep, this.key.sampleStep,
                            this.key.textureSize + 2, this.key.textureSize + 2, this::isCurrent);
                    } catch (CancellationException cancelled) {
                        return;
                    } catch (RuntimeException | LinkageError ignored) {
                        // Older/incompatible runtime: keep the existing native-height fallback.
                    }
                    if (!isCurrent()) return;
                }
                boolean hasSurface = surface != null;
                BiomeRegion region = SeedAtlasXaeroIntegration.withSession(this.key.seed, this.key.largeBiomes,
                    this.key.gameVersion, session -> {
                    BiomeRegion biomes = session.biomeArea(
                        this.key.dimension,
                        this.key.originBlockX,
                        this.key.originBlockZ,
                        this.key.y,
                        this.key.sampleStep,
                        this.key.textureSize,
                        this.key.textureSize
                    );
                    boolean nether = this.key.dimension == Dimension.NETHER;
                    // Nether floors and End islands take far longer than biomes: the first pass shows
                    // flat biomes at once and the refinement pass adds the terrain.
                    boolean deferred = this.key.dimension != Dimension.OVERWORLD && !this.refining;
                    if (!hasSurface && !deferred && this.terrainStyle && this.highlightMask.length == 0 && this.key.sampleStep < 64
                        && (nether ? session.supportsNetherFloor() && this.key.sampleStep <= 16
                            : (this.key.dimension == Dimension.OVERWORLD
                                || this.key.dimension == Dimension.END && this.key.sampleStep <= 8)
                                && session.supportsHeights())) {
                        // One-sample halo so slopes at tile edges see their neighbours.
                        try {
                            heightsOut[0] = nether
                                ? session.netherFloorArea(
                                    this.key.originBlockX - this.key.sampleStep,
                                    this.key.originBlockZ - this.key.sampleStep,
                                    this.key.y,
                                    this.key.sampleStep,
                                    this.key.textureSize + 2,
                                    this.key.textureSize + 2,
                                    // Carving masks take seconds per coarse tile, so coarser LODs use density only.
                                    session.supportsNetherCarving() && this.key.sampleStep <= 4 ? NetherCaveMask.area(this.key.seed,
                                        this.key.originBlockX - this.key.sampleStep,
                                        this.key.originBlockZ - this.key.sampleStep, this.key.sampleStep,
                                        this.key.textureSize + 2, this.key.textureSize + 2) : null)
                                : session.heightArea(
                                    this.key.dimension,
                                    this.key.originBlockX - this.key.sampleStep,
                                    this.key.originBlockZ - this.key.sampleStep,
                                    this.key.sampleStep,
                                    this.key.textureSize + 2,
                                    this.key.textureSize + 2);
                        } catch (RuntimeException | LinkageError ignored) {
                            heightsOut[0] = null; // biome rendering continues with the noise relief
                        }
                    }
                    return biomes;
                });
                if (region == null) {
                    if (isCurrent()) markFailed();
                    return;
                }
                if (!isCurrent()) {
                    return;
                }
                int count = Math.multiplyExact(this.key.textureSize, this.key.textureSize);
                int[] pixels = new int[count];
                region.copyArgbSamplesTo(pixels, 0);
                short[] ids = new short[count];
                region.copyBiomeIdsToUnsignedShorts(ids, 0);
                // Highlighting rewrites the samples before the upload, exactly like the
                // desktop Seed Atlas renderer does while it builds its biome image.
                BiomeHighlighter.apply(pixels, ids, this.highlightMask);
                // Focus mode stays flat so highlighted biomes remain readable.
                // Subpixel relief/grain adds CPU work but no useful terrain detail at distant zoom.
                if (this.terrainStyle && this.highlightMask.length == 0) {
                    if (surface != null) {
                        BiomeTerrainStyler.applySurface(pixels, ids, this.key.sampleStep, this.key.textureSize,
                            surface.heights(), surface.floors(), surface.colors());
                    } else if (this.key.dimension == Dimension.OVERWORLD) {
                        BiomeTerrainStyler.applyPreview(pixels, ids, this.key.sampleStep, this.key.textureSize,
                            heightsOut[0], previewColors(ids, heightsOut[0]));
                    } else if (this.key.dimension == Dimension.NETHER && heightsOut[0] == null && !this.refining
                        && needsSurfaceRefinement(this.key.dimension, true, false, this.key.sampleStep)) {
                        // Flat preview; the refinement pass adds the cave floor shortly after.
                    } else if (this.key.dimension == Dimension.END) {
                        // Real island heights; before they arrive and at coarse zoom the End biomes stand in.
                        if (heightsOut[0] == null) {
                            BiomeTerrainStyler.applyEndPreview(pixels, ids, this.key.originBlockX, this.key.originBlockZ,
                                this.key.sampleStep, this.key.textureSize);
                        } else {
                            BiomeTerrainStyler.applyEnd(pixels, ids, this.key.originBlockX, this.key.originBlockZ,
                                this.key.sampleStep, this.key.textureSize, heightsOut[0]);
                        }
                    } else if (this.key.dimension == Dimension.NETHER && heightsOut[0] != null && this.key.sampleStep < 64) {
                        // Predicted Nether surface for the selected cave view.
                        BiomeTerrainStyler.applyNether(pixels, ids, this.key.originBlockX, this.key.originBlockZ,
                            this.key.sampleStep, this.key.textureSize, heightsOut[0]);
                    } else if (this.key.sampleStep < 64) {
                        // Coarse Overworld / unavailable sampler: estimated heights or cosmetic fallback.
                        BiomeTerrainStyler.apply(pixels, ids, this.key.originBlockX, this.key.originBlockZ,
                            this.key.sampleStep, this.key.textureSize, this.key.seed, heightsOut[0]);
                    }
                }
                // Convert once on the worker so the render thread only bulk-uploads.
                for (int i = 0; i < count; ++i) {
                    pixels[i] = ARGB.toABGR(pixels[i]);
                }
                if (!isCurrent()) {
                    return;
                }
                if (SeedAtlasBiomeOverlayRenderer.this.readyKeys.size() >= READY_LIMIT) {
                    return;
                }
                if (SeedAtlasBiomeOverlayRenderer.this.readyKeys.add(this.key)) {
                    SeedAtlasBiomeOverlayRenderer.this.readyForUpload.add(
                        new CpuTile(
                            this.key,
                            this.generation,
                            this.category,
                            this.distance,
                            this.sequence,
                            ids,
                            pixels,
                            this.refining || !needsSurfaceRefinement(this.key.dimension, this.terrainStyle,
                                this.highlightMask.length != 0, this.key.sampleStep)));
                }
            } catch (RuntimeException | LinkageError ignored) {
                if (isCurrent()) markFailed();
            } finally {
                if (refinementSlot) SeedAtlasBiomeOverlayRenderer.this.refinementsRunning.decrementAndGet();
                SeedAtlasBiomeOverlayRenderer.this.pending.remove(this.key, this);
            }
        }

        private int[] previewColors(short[] ids, float[] heights) {
            int size = this.key.textureSize;
            int hw = size + 2;
            int[] colors = new int[ids.length];
            String[] names = new String[256];
            for (short raw : ids) {
                int id = raw & 0xffff;
                if (id < names.length && names[id] == null) {
                    names[id] = SeedAtlasBiomeOverlayRenderer.this.biomeNames.computeIfAbsent(id,
                        value -> SeedAtlasXaeroIntegration.withAnySession(session -> session.biomeName(value)));
                }
            }
            boolean hasHeights = heights != null && heights.length == hw * hw;
            int centre = (this.key.sampleStep - 1) / 2;
            for (int z = 0; z < size; ++z) for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                int id = ids[i] & 0xffff;
                colors[i] = id >= names.length ? -1 : OverworldTerrainSampler.previewColor(names[id],
                    this.key.originBlockX + x * this.key.sampleStep + centre,
                    hasHeights ? Math.round(heights[(z + 1) * hw + x + 1]) : 64,
                    this.key.originBlockZ + z * this.key.sampleStep + centre, BiomeTerrainStyler.waterBiome(id),
                    this.key.sampleStep);
            }
            return colors;
        }

        private boolean isCurrent() {
            if (this.generation != SeedAtlasBiomeOverlayRenderer.this.generation.get()
                || !SeedAtlasXaeroIntegration.isLayerConfigured()) {
                return false;
            }
            if (this.refining) {
                ViewRequest view = SeedAtlasBiomeOverlayRenderer.this.currentView;
                if (SeedAtlasBiomeOverlayRenderer.this.coveragePending || view == null
                    || view.sampleStep != this.key.sampleStep || view.textureSize != this.key.textureSize
                    || this.key.originBlockX < (long)view.minTileX * view.worldSize()
                    || this.key.originBlockX > (long)view.maxTileX * view.worldSize()
                    || this.key.originBlockZ < (long)view.minTileZ * view.worldSize()
                    || this.key.originBlockZ > (long)view.maxTileZ * view.worldSize()) return false;
            }
            if (this.category < 3
                && !SeedAtlasBiomeOverlayRenderer.this.viewportDesired.contains(this.key)
                && !SeedAtlasBiomeOverlayRenderer.this.backgroundDesired.contains(this.key)) {
                return false;
            }
            if (this.category >= 3
                && !SeedAtlasBiomeOverlayRenderer.this.backgroundDesired.contains(this.key)
                && !SeedAtlasBiomeOverlayRenderer.this.viewportDesired.contains(this.key)) {
                return false;
            }
            OptionalLong activeSeed = SeedAtlasClientState.activeSeed(this.key.dimension);
            return activeSeed.isPresent()
                && activeSeed.getAsLong() == this.key.seed
                && SeedAtlasClientState.config().largeBiomes() == this.key.largeBiomes
                && SeedAtlasClientState.activeGameVersion().equals(this.key.gameVersion)
                && (this.key.dimension == Dimension.NETHER
                    ? SeedAtlasBiomeOverlayRenderer.this.netherSurfaceY
                    : SeedAtlasXaeroIntegration.yFor(this.key.level)) == this.key.y;
        }

        private void markFailed() {
            if (this.generation == SeedAtlasBiomeOverlayRenderer.this.generation.get()) {
                SeedAtlasBiomeOverlayRenderer.this.failedUntil.put(
                    this.key, System.nanoTime() + RETRY_DELAY_NANOS);
            }
        }
    }

    static final class OverlayContext {
        private volatile List<OverlaySlice> slices = List.of();
        private volatile ResourceKey<Level> dimension;
        private double cameraX;
        private double cameraZ;
        private double mouseX;
        private double mouseZ;
        private int viewportWidth = 1;
        private int viewportHeight = 1;
    }

    static final class OverlayProbe {
        private static final OverlayProbe HOVER = new OverlayProbe();
    }

    private static final class ProbeProvider
        extends ElementRenderProvider<OverlayProbe, OverlayContext> {
        private int index;

        @Override
        public void begin(ElementRenderLocation location, OverlayContext context) {
            this.index = 0;
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, OverlayContext context) {
            return this.index < 1;
        }

        @Override
        public OverlayProbe getNext(ElementRenderLocation location, OverlayContext context) {
            this.index++;
            return OverlayProbe.HOVER;
        }

        @Override
        public void end(ElementRenderLocation location, OverlayContext context) {
            this.index = 1;
        }
    }

    private static final class ProbeReader extends ElementReader<
        OverlayProbe, OverlayContext, SeedAtlasBiomeOverlayRenderer
    > {
        @Override
        public boolean isHidden(OverlayProbe probe, OverlayContext context) {
            return context.dimension == null;
        }

        @Override
        public double getRenderX(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.cameraX;
        }

        @Override
        public double getRenderZ(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.cameraZ;
        }

        @Override
        public int getInteractionBoxLeft(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return -context.viewportWidth / 2;
        }

        @Override
        public int getInteractionBoxRight(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.viewportWidth / 2;
        }

        @Override
        public int getInteractionBoxTop(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return -context.viewportHeight / 2;
        }

        @Override
        public int getInteractionBoxBottom(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.viewportHeight / 2;
        }

        @Override
        public int getRenderBoxLeft(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return -context.viewportWidth / 2;
        }

        @Override
        public int getRenderBoxRight(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.viewportWidth / 2;
        }

        @Override
        public int getRenderBoxTop(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return -context.viewportHeight / 2;
        }

        @Override
        public int getRenderBoxBottom(OverlayProbe probe, OverlayContext context, float partialTicks) {
            return context.viewportHeight / 2;
        }

        @Override
        public int getLeftSideLength(OverlayProbe probe, Minecraft minecraft) {
            return 0;
        }

        @Override
        public String getMenuName(OverlayProbe probe) {
            return "";
        }

        @Override
        public String getFilterName(OverlayProbe probe) {
            return "seedatlas_biomes";
        }

        @Override
        public int getMenuTextFillLeftPadding(OverlayProbe probe) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(OverlayProbe probe) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, OverlayProbe probe) {
            return location == ElementRenderLocation.WORLD_MAP;
        }

        @Override
        public Tooltip getTooltip(OverlayProbe probe, OverlayContext context, boolean overMenu) {
            int blockX = (int)Math.floor(context.mouseX);
            int blockZ = (int)Math.floor(context.mouseZ);
            BiomeSample biome = SeedAtlasXaeroIntegration.biomeAtCached(
                context.dimension, blockX, blockZ);
            if (biome == null) {
                return null;
            }
            int y = SeedAtlasXaeroIntegration.yFor(context.dimension);
            Component text = Component.empty()
                .append(SeedAtlasXaeroIntegration.biomeDisplayName(biome.name()))
                .append(Component.literal(
                    " \u00b7 Y=" + y + " \u00b7 X=" + blockX + " \u00b7 Z=" + blockZ));
            return new Tooltip(text, true);
        }
    }

    private static final class GpuTile implements AutoCloseable {
        private final TileKey key;
        private final DynamicTexture texture;
        private final short[] biomeIds;
        private final boolean complete;

        private GpuTile(TileKey key, DynamicTexture texture, short[] biomeIds, boolean complete) {
            this.key = key;
            this.texture = texture;
            this.biomeIds = biomeIds;
            this.complete = complete;
        }

        private boolean contains(int blockX, int blockZ) {
            return contains(blockX, blockZ, (long)blockX + 1, (long)blockZ + 1);
        }

        private boolean contains(long minX, long minZ, long maxX, long maxZ) {
            return minX >= this.key.originBlockX && minZ >= this.key.originBlockZ
                && maxX <= (long)this.key.originBlockX + this.key.worldSize()
                && maxZ <= (long)this.key.originBlockZ + this.key.worldSize();
        }

        private boolean intersects(long minX, long minZ, long maxX, long maxZ) {
            return minX < (long)this.key.originBlockX + this.key.worldSize()
                && maxX > this.key.originBlockX
                && minZ < (long)this.key.originBlockZ + this.key.worldSize()
                && maxZ > this.key.originBlockZ;
        }

        private int biomeIdAt(int blockX, int blockZ) {
            int sampleX = Math.floorDiv(blockX - this.key.originBlockX, this.key.sampleStep);
            int sampleZ = Math.floorDiv(blockZ - this.key.originBlockZ, this.key.sampleStep);
            int raw = Short.toUnsignedInt(
                this.biomeIds[sampleZ * this.key.textureSize + sampleX]);
            return raw == 0xFFFF ? -1 : raw;
        }

        @Override
        public void close() {
            this.texture.close();
        }
    }

    private record CpuTile(
        TileKey key,
        long generation,
        int category,
        long distance,
        long sequence,
        short[] biomeIds,
        /** NativeImage memory order (ABGR), already converted on the worker. */
        int[] abgr,
        boolean complete
    ) {
    }

    private record TaskRequest(TileKey key, int category, long distance) {
    }

    private record OverlaySlice(
        GpuTile source,
        long minX,
        long minZ,
        long maxX,
        long maxZ,
        float u0,
        float v0,
        float u1,
        float v1
    ) {
    }

    private record TileKey(
        long seed,
        boolean largeBiomes,
        GameVersion gameVersion,
        Dimension dimension,
        ResourceKey<Level> level,
        int y,
        int sampleStep,
        int textureSize,
        int originBlockX,
        int originBlockZ
    ) {
        private int worldSize() {
            return Math.multiplyExact(this.sampleStep, this.textureSize);
        }

        private boolean sameWorld(TileKey other) {
            return this.seed == other.seed
                && this.largeBiomes == other.largeBiomes
                && this.gameVersion.equals(other.gameVersion)
                && this.dimension == other.dimension
                && this.level.equals(other.level)
                && this.y == other.y;
        }
    }

    private record ViewRequest(
        long seed,
        boolean largeBiomes,
        GameVersion gameVersion,
        Dimension dimension,
        ResourceKey<Level> level,
        int y,
        int sampleStep,
        int textureSize,
        int minTileX,
        int maxTileX,
        int minTileZ,
        int maxTileZ,
        double cameraX,
        double cameraZ,
        double scale,
        int viewportWidth,
        int viewportHeight
    ) {
        private int worldSize() {
            return Math.multiplyExact(this.sampleStep, this.textureSize);
        }

        private boolean sameGrid(ViewRequest other) {
            return this.seed == other.seed
                && this.largeBiomes == other.largeBiomes
                && this.gameVersion.equals(other.gameVersion)
                && this.dimension == other.dimension
                && this.level.equals(other.level)
                && this.y == other.y
                && this.sampleStep == other.sampleStep
                && this.textureSize == other.textureSize
                && this.minTileX == other.minTileX
                && this.maxTileX == other.maxTileX
                && this.minTileZ == other.minTileZ
                && this.maxTileZ == other.maxTileZ;
        }
    }

    private record BackgroundRequest(
        long seed,
        ResourceKey<Level> level,
        int sampleStep,
        int textureSize,
        int centerTileX,
        int centerTileZ,
        int radius
    ) {
    }

    private record GenerationSignature(
        OptionalLong overworldSeed,
        OptionalLong netherSeed,
        OptionalLong endSeed,
        boolean largeBiomes,
        GameVersion gameVersion,
        int overworldY,
        int netherY,
        int endY,
        Set<Integer> highlightSignature,
        boolean terrainStyle
    ) {
    }
}

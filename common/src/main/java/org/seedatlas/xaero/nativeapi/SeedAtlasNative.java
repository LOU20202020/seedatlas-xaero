package org.seedatlas.xaero.nativeapi;

import java.lang.ref.Cleaner;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Pure-Java facade for the bundled Seed Atlas native generation ABI.
 * Instances are safe for concurrent reads; close waits for active calls.
 */
public final class SeedAtlasNative implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final int RESULT_STRIDE = 6;
    private static final int RESULT_FLAG_BIOME_CHECKED = 1;
    private static final int RESULT_FLAG_BIOME_VIABLE = 2;
    private static final int RESULT_FLAG_APPROXIMATE = 4;
    private static final int MAX_RESULTS = 100_000;
    private static final int MAX_BIOME_AREA_SAMPLES = 4 * 1024 * 1024;

    private static volatile BindingResult bindingResult;

    private final long seed;
    private final WorldType worldType;
    private final GameVersion gameVersion;
    private final NativeBindings bindings;
    private final NativeState nativeState;
    private final Cleaner.Cleanable cleanable;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private boolean closed;

    private SeedAtlasNative(long seed, WorldType worldType, GameVersion gameVersion,
                            NativeBindings bindings, MemorySegment context) {
        this.seed = seed;
        this.worldType = worldType;
        this.gameVersion = gameVersion;
        this.bindings = bindings;
        this.nativeState = new NativeState(bindings, context);
        this.cleanable = CLEANER.register(this, nativeState);
    }

    /** Generates the newest Minecraft version the engine supports. */
    public static SeedAtlasNative open(long seed, WorldType worldType) {
        return open(seed, worldType, GameVersion.NEWEST);
    }

    /** Generates {@code gameVersion}; throws when the loaded library cannot select it. */
    public static SeedAtlasNative open(long seed, WorldType worldType, GameVersion gameVersion) {
        Objects.requireNonNull(worldType, "worldType");
        Objects.requireNonNull(gameVersion, "gameVersion");
        NativeBindings bindings = bindings();
        MemorySegment context;
        if (gameVersion.isNewest() && !bindings.supportsMcVersions()) {
            context = bindings.create(seed, worldType.nativeFlags());
        } else {
            if (!bindings.supportsMcVersions()) {
                throw new NativeException("Native library cannot generate Minecraft " + gameVersion.name());
            }
            try (Arena arena = Arena.ofConfined()) {
                context = bindings.createVersioned(seed, worldType.nativeFlags(),
                    arena.allocateFrom(gameVersion.name()));
            }
        }
        if (context.equals(MemorySegment.NULL) || context.address() == 0) {
            throw new NativeException("Could not allocate a native Seed Atlas context for Minecraft "
                + gameVersion.name());
        }
        return new SeedAtlasNative(seed, worldType, gameVersion, bindings, context);
    }

    /** True when the loaded engine can generate {@code gameVersion}. */
    public static boolean supports(GameVersion gameVersion) {
        Objects.requireNonNull(gameVersion, "gameVersion");
        BindingResult result = bindingResult();
        if (result.bindings == null) {
            return false;
        }
        if (!result.bindings.supportsMcVersions()) {
            return gameVersion.isNewest();
        }
        try (Arena arena = Arena.ofConfined()) {
            return result.bindings.mcVersionSupported(arena.allocateFrom(gameVersion.name()));
        }
    }

    /** Loads and ABI-checks the library, returning a diagnostic instead of throwing. */
    public static NativeStatus status() {
        BindingResult result = bindingResult();
        if (result.bindings != null) {
            return new NativeStatus(true, engineVersion(result.bindings),
                result.bindings.platform(), "Native Seed Atlas engine is available");
        }
        String platform = System.getProperty("os.name", "unknown") + " / "
            + System.getProperty("os.arch", "unknown");
        return new NativeStatus(false, "", platform, result.failure.getMessage());
    }

    /** Returns the native engine/ABI version, or throws when loading failed. */
    public static String engineVersion() {
        return engineVersion(bindings());
    }

    public long seed() {
        return seed;
    }

    public WorldType worldType() {
        return worldType;
    }

    public GameVersion gameVersion() {
        return gameVersion;
    }

    public BiomeSample biomeAt(Dimension dimension, int x, int y, int z) {
        Objects.requireNonNull(dimension, "dimension");
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment output = arena.allocate(ValueLayout.JAVA_INT);
            checkError(bindings.biomeAt(context, dimension.nativeId(), x, y, z, output),
                "generate biome");
            int id = output.get(ValueLayout.JAVA_INT, 0);
            return new BiomeSample(id, biomeName(id), biomeColor(id));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public BiomeTile biomeTile(Dimension dimension, int chunkX, int chunkZ, int y) {
        return biomeTile(dimension, chunkX, chunkZ, y, 1);
    }

    /**
     * Generates a 16x16 biome tile using one native sample per
     * {@code sampleStep} square. Supported steps are 1, 2, 4, 8, and 16.
     */
    public BiomeTile biomeTile(Dimension dimension, int chunkX, int chunkZ, int y,
                               int sampleStep) {
        Objects.requireNonNull(dimension, "dimension");
        validateSampleStep(sampleStep);
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment idMemory = arena.allocate(ValueLayout.JAVA_INT, BiomeTile.SAMPLE_COUNT);
            MemorySegment colorMemory = arena.allocate(ValueLayout.JAVA_INT, BiomeTile.SAMPLE_COUNT);
            checkError(bindings.biomeChunkSampled(context, dimension.nativeId(),
                chunkX, chunkZ, y, sampleStep, idMemory, colorMemory),
                "generate biome tile");
            int[] ids = new int[BiomeTile.SAMPLE_COUNT];
            int[] colors = new int[BiomeTile.SAMPLE_COUNT];
            for (int i = 0; i < BiomeTile.SAMPLE_COUNT; i++) {
                ids[i] = idMemory.getAtIndex(ValueLayout.JAVA_INT, i);
                colors[i] = colorMemory.getAtIndex(ValueLayout.JAVA_INT, i);
            }
            return new BiomeTile(chunkX, chunkZ, y, ids, colors);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Generates one compact map raster in a single native call. Each output
     * entry represents one {@code sampleStep x sampleStep} block cell.
     */
    public BiomeRegion biomeArea(Dimension dimension, int originBlockX,
                                 int originBlockZ, int y, int sampleStep,
                                 int sampleWidth, int sampleHeight) {
        Objects.requireNonNull(dimension, "dimension");
        validateArea(sampleStep, sampleWidth, sampleHeight);
        if (sampleStep >= 4
            && (originBlockX % sampleStep != 0 || originBlockZ % sampleStep != 0)) {
            throw new IllegalArgumentException(
                "Biome area origins must align to sampleStep for steps of 4 or more");
        }
        long lastX = (long) originBlockX + (long) sampleWidth * sampleStep - 1L;
        long lastZ = (long) originBlockZ + (long) sampleHeight * sampleStep - 1L;
        if (lastX < Integer.MIN_VALUE || lastX > Integer.MAX_VALUE
            || lastZ < Integer.MIN_VALUE || lastZ > Integer.MAX_VALUE
            || (sampleStep <= 2 && (originBlockX < Integer.MIN_VALUE + 2
                || originBlockZ < Integer.MIN_VALUE + 2))) {
            throw new IllegalArgumentException("Biome area exceeds block coordinate limits");
        }
        int sampleCount = Math.multiplyExact(sampleWidth, sampleHeight);
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment idMemory = arena.allocate(ValueLayout.JAVA_INT, sampleCount);
            MemorySegment colorMemory = arena.allocate(ValueLayout.JAVA_INT, sampleCount);
            checkError(bindings.biomeAreaSampled(context, dimension.nativeId(),
                originBlockX, originBlockZ, y, sampleStep, sampleWidth,
                sampleHeight, idMemory, colorMemory, sampleCount),
                "generate biome area");
            int[] ids = idMemory.toArray(ValueLayout.JAVA_INT);
            int[] colors = colorMemory.toArray(ValueLayout.JAVA_INT);
            return BiomeRegion.fromOwnedArrays(
                originBlockX, originBlockZ, y, sampleStep,
                sampleWidth, sampleHeight, ids, colors);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** True when the loaded library can estimate terrain heights (ABI 5+). */
    public boolean supportsHeights() {
        return bindings.supportsHeights();
    }

    /**
     * Approximate Overworld terrain height in blocks per sample, row-major, for
     * display shading only (see sax_height_area_sampled). The origin need not be
     * aligned and may be a halo outside the biome tile.
     */
    public float[] heightArea(Dimension dimension, int originBlockX, int originBlockZ,
                              int sampleStep, int sampleWidth, int sampleHeight) {
        Objects.requireNonNull(dimension, "dimension");
        validateArea(sampleStep, sampleWidth, sampleHeight);
        if (!bindings.supportsHeights()) {
            throw new NativeException("Native library does not provide terrain heights");
        }
        int sampleCount = Math.multiplyExact(sampleWidth, sampleHeight);
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT, sampleCount);
            checkError(bindings.heightAreaSampled(context, dimension.nativeId(),
                originBlockX, originBlockZ, sampleStep, sampleWidth, sampleHeight,
                out, sampleCount), "estimate terrain heights");
            return out.toArray(ValueLayout.JAVA_FLOAT);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** True when the loaded library can compute density-derived Nether floors (ABI 6+). */
    public boolean supportsNetherFloor() {
        return bindings.supportsNetherFloor();
    }

    public boolean supportsNetherCarving() {
        return bindings.supportsNetherCarving();
    }

    /**
     * Density-only Nether cave-floor height at layer y, row-major (see
     * sax_nether_floor_area_sampled). Steps 1..16; the origin may be a halo.
     */
    public float[] netherFloorArea(int originBlockX, int originBlockZ, int y,
                                   int sampleStep, int sampleWidth, int sampleHeight) {
        return netherFloorArea(originBlockX, originBlockZ, y, sampleStep, sampleWidth, sampleHeight, null);
    }

    /** Optional seed-derived carved-air bits: two words per sample covering Y 0..127. */
    public float[] netherFloorArea(int originBlockX, int originBlockZ, int y,
                                  int sampleStep, int sampleWidth, int sampleHeight, long[] carved) {
        validateArea(sampleStep, sampleWidth, sampleHeight);
        if (!bindings.supportsNetherFloor()) {
            throw new NativeException("Native library does not provide the Nether floor");
        }
        int sampleCount = Math.multiplyExact(sampleWidth, sampleHeight);
        if (carved != null) {
            if (carved.length != Math.multiplyExact(sampleCount, 2)) {
                throw new IllegalArgumentException("Nether carving masks require two words per sample");
            }
            if (!bindings.supportsNetherCarving()) {
                throw new NativeException("Native library does not support Nether carving masks");
            }
        }
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT, sampleCount);
            int result = carved == null
                ? bindings.netherFloorAreaSampled(context, originBlockX, originBlockZ, y,
                    sampleStep, sampleWidth, sampleHeight, out, sampleCount)
                : bindings.netherFloorAreaCarvedSampled(context, originBlockX, originBlockZ, y,
                    sampleStep, sampleWidth, sampleHeight, arena.allocateFrom(ValueLayout.JAVA_LONG, carved),
                    carved.length, out, sampleCount);
            checkError(result, "compute the Nether floor");
            return out.toArray(ValueLayout.JAVA_FLOAT);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public ScanResult structures(StructureType type, BlockBox box, int maxResults,
                                 boolean biomeCheck) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(box, "box");
        validateMaxResults(maxResults);
        if (!type.supportsStructureScan()) {
            throw new IllegalArgumentException(type + " uses a dedicated scan method");
        }
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment output = resultBuffer(arena, maxResults);
            int count = bindings.scanStructures(context, type.nativeId(), box,
                biomeCheck, output, maxResults);
            checkError(count, "scan " + type.name().toLowerCase(Locale.ROOT));
            return decodeScan(output, count, maxResults);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public ScanResult strongholds(BlockBox box, int maxResults, boolean biomeCheck) {
        Objects.requireNonNull(box, "box");
        validateMaxResults(maxResults);
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment output = resultBuffer(arena, maxResults);
            int count = bindings.scanStrongholds(context, box, biomeCheck,
                output, maxResults);
            checkError(count, "scan strongholds");
            return decodeScan(output, count, maxResults);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public BlockPos spawn(SpawnMode mode) {
        Objects.requireNonNull(mode, "mode");
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment output = arena.allocate(ValueLayout.JAVA_INT, 3);
            checkError(bindings.spawn(context, mode.detailed(), output), "calculate spawn");
            return new BlockPos(
                output.getAtIndex(ValueLayout.JAVA_INT, 0),
                output.getAtIndex(ValueLayout.JAVA_INT, 1),
                output.getAtIndex(ValueLayout.JAVA_INT, 2));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public ScanResult slimeChunks(BlockBox box, int maxResults) {
        Objects.requireNonNull(box, "box");
        validateMaxResults(maxResults);
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment context = context();
            MemorySegment output = resultBuffer(arena, maxResults);
            int count = bindings.scanSlimeChunks(context, box, output, maxResults);
            checkError(count, "scan slime chunks");
            return decodeScan(output, count, maxResults);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                cleanable.clean();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private MemorySegment context() {
        if (closed) {
            throw new IllegalStateException("Native Seed Atlas context is closed");
        }
        return nativeState.context();
    }

    /** Resolves an engine biome id to a namespaced resource name of this context's version. */
    public String biomeName(int id) {
        lifecycleLock.readLock().lock();
        try (Arena arena = Arena.ofConfined()) {
            boolean own = bindings.supportsContextBiomes();
            MemorySegment context = own ? context() : MemorySegment.NULL;
            int required = own ? bindings.contextBiomeName(context, id, MemorySegment.NULL, 0)
                : bindings.biomeName(id, MemorySegment.NULL, 0);
            if (required <= 0) {
                return "minecraft:unknown_" + id;
            }
            MemorySegment output = arena.allocate(required);
            int written = own ? bindings.contextBiomeName(context, id, output, required)
                : bindings.biomeName(id, output, required);
            if (written <= 0) {
                return "minecraft:unknown_" + id;
            }
            return "minecraft:" + output.getString(0);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** Resolves an engine biome id to an opaque ARGB color of this context's version (0 for unknown). */
    public int biomeColor(int id) {
        if (!bindings.supportsContextBiomes()) {
            return bindings.biomeColor(id);
        }
        lifecycleLock.readLock().lock();
        try {
            return bindings.contextBiomeColor(context(), id);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private static MemorySegment resultBuffer(Arena arena, int maxResults) {
        if (maxResults == 0) {
            return MemorySegment.NULL;
        }
        return arena.allocate(ValueLayout.JAVA_INT, (long) maxResults * RESULT_STRIDE);
    }

    private static ScanResult decodeScan(MemorySegment output, int nativeCount,
                                         int capacity) {
        boolean truncated = nativeCount > capacity;
        int count = Math.min(nativeCount, capacity);
        List<StructurePosition> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long base = (long) i * RESULT_STRIDE;
            StructureType type = StructureType.fromNativeId(
                output.getAtIndex(ValueLayout.JAVA_INT, base));
            int x = output.getAtIndex(ValueLayout.JAVA_INT, base + 1);
            int y = output.getAtIndex(ValueLayout.JAVA_INT, base + 2);
            int z = output.getAtIndex(ValueLayout.JAVA_INT, base + 3);
            int flags = output.getAtIndex(ValueLayout.JAVA_INT, base + 4);
            int detail = output.getAtIndex(ValueLayout.JAVA_INT, base + 5);
            Viability viability = (flags & RESULT_FLAG_BIOME_CHECKED) != 0
                && (flags & RESULT_FLAG_BIOME_VIABLE) != 0
                    ? Viability.VIABLE : Viability.NOT_CHECKED;
            positions.add(new StructurePosition(type, x, y, z, viability,
                (flags & RESULT_FLAG_APPROXIMATE) != 0, detail));
        }
        return new ScanResult(positions, truncated);
    }

    private static void validateMaxResults(int maxResults) {
        if (maxResults < 0 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException("maxResults must be between 0 and " + MAX_RESULTS);
        }
    }

    private static void validateSampleStep(int sampleStep) {
        if (sampleStep != 1 && sampleStep != 2 && sampleStep != 4
            && sampleStep != 8 && sampleStep != 16) {
            throw new IllegalArgumentException("sampleStep must be one of 1, 2, 4, 8, or 16");
        }
    }

    private static void validateArea(int sampleStep, int sampleWidth,
                                     int sampleHeight) {
        if (sampleStep < 1 || sampleStep > 1024
            || (sampleStep & (sampleStep - 1)) != 0) {
            throw new IllegalArgumentException(
                "sampleStep must be a power of two between 1 and 1024");
        }
        if (sampleWidth <= 0 || sampleHeight <= 0) {
            throw new IllegalArgumentException("Biome area dimensions must be positive");
        }
        long sampleCount = (long) sampleWidth * sampleHeight;
        if (sampleCount > MAX_BIOME_AREA_SAMPLES) {
            throw new IllegalArgumentException(
                "Biome area contains more than " + MAX_BIOME_AREA_SAMPLES + " samples");
        }
        if (sampleStep == 2 && sampleCount * 4 > MAX_BIOME_AREA_SAMPLES) {
            throw new IllegalArgumentException(
                "Step-2 biome areas may contain at most "
                    + (MAX_BIOME_AREA_SAMPLES / 4) + " samples");
        }
    }

    private static void checkError(int code, String operation) {
        if (code >= 0) {
            return;
        }
        String reason = switch (code) {
            case -1 -> "invalid argument";
            case -2 -> "native allocation failed";
            case -3 -> "unsupported operation";
            case -4 -> "bounding box is too large; split it into smaller tiles";
            case -5 -> "world generation failed";
            default -> "unknown native error";
        };
        throw new NativeException("Could not " + operation + ": " + reason, code);
    }

    private static NativeBindings bindings() {
        BindingResult result = bindingResult();
        if (result.bindings == null) {
            if (result.failure instanceof NativeException nativeException) {
                throw nativeException;
            }
            throw new NativeException("Native Seed Atlas engine is unavailable", result.failure);
        }
        return result.bindings;
    }

    private static BindingResult bindingResult() {
        BindingResult result = bindingResult;
        if (result != null) {
            return result;
        }
        synchronized (SeedAtlasNative.class) {
            result = bindingResult;
            if (result == null) {
                try {
                    NativeBindings loaded = NativeBindings.load();
                    int required = 0x1ff;
                    if ((loaded.capabilities() & required) != required) {
                        throw new NativeException("Native library lacks required capabilities");
                    }
                    result = new BindingResult(loaded, null);
                } catch (RuntimeException | LinkageError failure) {
                    result = new BindingResult(null, failure);
                }
                bindingResult = result;
            }
        }
        return result;
    }

    private static String engineVersion(NativeBindings bindings) {
        int required = bindings.engineVersion(MemorySegment.NULL, 0);
        if (required <= 0) {
            return "unknown";
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment output = arena.allocate(required);
            bindings.engineVersion(output, required);
            return output.getString(0);
        }
    }

    private record BindingResult(NativeBindings bindings, Throwable failure) {}

    private static final class NativeState implements Runnable {
        private final NativeBindings bindings;
        private MemorySegment context;

        private NativeState(NativeBindings bindings, MemorySegment context) {
            this.bindings = bindings;
            this.context = context;
        }

        synchronized MemorySegment context() {
            if (context == null) {
                throw new IllegalStateException("Native Seed Atlas context is closed");
            }
            return context;
        }

        @Override
        public synchronized void run() {
            if (context != null) {
                try {
                    bindings.destroy(context);
                } catch (RuntimeException ignored) {
                    // Cleaner actions cannot report failures safely.
                } finally {
                    context = null;
                }
            }
        }
    }
}

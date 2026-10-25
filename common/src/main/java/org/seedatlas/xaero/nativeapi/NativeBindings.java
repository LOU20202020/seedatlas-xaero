package org.seedatlas.xaero.nativeapi;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class NativeBindings {
    static final int EXPECTED_ABI = 6;
    /** ABI 4/5 libraries still load for biomes/structures but lack newer terrain data. */
    static final int MIN_ABI = 4;
    static final int CAP_HEIGHTS = 1 << 9;
    static final int CAP_NETHER_FLOOR = 1 << 10;
    static final int CAP_NETHER_CARVING = 1 << 11;
    static final int CAP_MC_VERSIONS = 1 << 12;

    private final NativeLibraryLoader.LoadedNative library;
    private final MethodHandle abiVersion;
    private final MethodHandle capabilities;
    private final MethodHandle engineVersion;
    private final MethodHandle create;
    private final MethodHandle createVersioned;
    private final MethodHandle mcVersionSupported;
    private final MethodHandle contextBiomeName;
    private final MethodHandle contextBiomeColor;
    private final MethodHandle destroy;
    private final MethodHandle biomeAt;
    private final MethodHandle biomeChunk;
    private final MethodHandle biomeChunkSampled;
    private final MethodHandle biomeAreaSampled;
    private final MethodHandle heightAreaSampled;
    private final MethodHandle netherFloorAreaSampled;
    private final MethodHandle netherFloorAreaCarvedSampled;
    private final MethodHandle biomeName;
    private final MethodHandle biomeColor;
    private final MethodHandle scanStructures;
    private final MethodHandle scanStrongholds;
    private final MethodHandle spawn;
    private final MethodHandle scanSlimeChunks;

    private NativeBindings(NativeLibraryLoader.LoadedNative library) {
        this.library = library;
        SymbolLookup symbols = library.symbols();
        abiVersion = downcall(symbols, "sax_abi_version", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        capabilities = downcall(symbols, "sax_capabilities", FunctionDescriptor.of(ValueLayout.JAVA_INT));
        engineVersion = downcall(symbols, "sax_engine_version", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        create = downcall(symbols, "sax_create", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        destroy = downcall(symbols, "sax_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        biomeAt = downcall(symbols, "sax_biome_at", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS));
        biomeChunk = downcall(symbols, "sax_biome_chunk", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        biomeChunkSampled = downcall(symbols, "sax_biome_chunk_sampled", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        biomeAreaSampled = downcall(symbols, "sax_biome_area_sampled", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        biomeName = downcall(symbols, "sax_biome_name", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT));
        biomeColor = downcall(symbols, "sax_biome_color", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        scanStructures = downcall(symbols, "sax_scan_structures", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT));
        scanStrongholds = downcall(symbols, "sax_scan_strongholds", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        spawn = downcall(symbols, "sax_spawn", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS));
        scanSlimeChunks = downcall(symbols, "sax_scan_slime_chunks", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        // Optional: absent from ABI 4 libraries, and only used when the capability bit is set.
        heightAreaSampled = symbols.find("sax_height_area_sampled")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
            .orElse(null);
        netherFloorAreaSampled = symbols.find("sax_nether_floor_area_sampled")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
            .orElse(null);
        netherFloorAreaCarvedSampled = symbols.find("sax_nether_floor_area_carved_sampled")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
            .orElse(null);
        createVersioned = symbols.find("sax_create_versioned")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
            .orElse(null);
        contextBiomeName = symbols.find("sax_context_biome_name")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
            .orElse(null);
        contextBiomeColor = symbols.find("sax_context_biome_color")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)))
            .orElse(null);
        mcVersionSupported = symbols.find("sax_mc_version_supported")
            .map(symbol -> Linker.nativeLinker().downcallHandle(symbol,
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
            .orElse(null);
    }

    static NativeBindings load() {
        NativeBindings bindings = new NativeBindings(NativeLibraryLoader.load());
        int abi = bindings.abiVersion();
        if (abi < MIN_ABI || abi > EXPECTED_ABI) {
            throw new NativeException("Native ABI mismatch: Java expects " + MIN_ABI + ".."
                + EXPECTED_ABI
                + " but library provides " + abi);
        }
        return bindings;
    }

    boolean supportsHeights() {
        return heightAreaSampled != null && abiVersion() >= 5
            && (capabilities() & CAP_HEIGHTS) != 0;
    }

    boolean supportsNetherFloor() {
        return netherFloorAreaSampled != null && abiVersion() >= 6
            && (capabilities() & CAP_NETHER_FLOOR) != 0;
    }

    boolean supportsNetherCarving() {
        return supportsNetherFloor() && netherFloorAreaCarvedSampled != null
            && (capabilities() & CAP_NETHER_CARVING) != 0;
    }

    /** False for libraries built before version selection; they only generate their own version. */
    boolean supportsMcVersions() {
        return createVersioned != null && mcVersionSupported != null
            && (capabilities() & CAP_MC_VERSIONS) != 0;
    }

    /** Biome names and colours of the context's own version; false for libraries before version selection. */
    boolean supportsContextBiomes() {
        return contextBiomeName != null && contextBiomeColor != null;
    }

    int contextBiomeName(MemorySegment context, int id, MemorySegment out, int capacity) {
        try {
            return (int) contextBiomeName.invokeExact(context, id, out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_context_biome_name", error);
        }
    }

    int contextBiomeColor(MemorySegment context, int id) {
        try {
            return (int) contextBiomeColor.invokeExact(context, id);
        } catch (Throwable error) {
            throw callFailure("sax_context_biome_color", error);
        }
    }

    boolean mcVersionSupported(MemorySegment version) {
        if (mcVersionSupported == null) {
            return false;
        }
        try {
            return (int) mcVersionSupported.invokeExact(version) != 0;
        } catch (Throwable error) {
            throw callFailure("sax_mc_version_supported", error);
        }
    }

    MemorySegment createVersioned(long seed, int flags, MemorySegment version) {
        if (createVersioned == null) {
            throw new NativeException("Native library cannot select a Minecraft version");
        }
        try {
            return (MemorySegment) createVersioned.invokeExact(seed, flags, version);
        } catch (Throwable error) {
            throw callFailure("sax_create_versioned", error);
        }
    }

    int netherFloorAreaCarvedSampled(MemorySegment context, int originBlockX, int originBlockZ,
                                     int y, int sampleStep, int sampleWidth, int sampleHeight,
                                     MemorySegment carved, int carvedCapacity,
                                     MemorySegment heights, int capacity) {
        if (netherFloorAreaCarvedSampled == null) {
            throw new NativeException("Native library does not support Nether carving masks");
        }
        try {
            return (int) netherFloorAreaCarvedSampled.invokeExact(context, originBlockX, originBlockZ,
                y, sampleStep, sampleWidth, sampleHeight, carved, carvedCapacity, heights, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_nether_floor_area_carved_sampled", error);
        }
    }

    int netherFloorAreaSampled(MemorySegment context, int originBlockX, int originBlockZ,
                               int y, int sampleStep, int sampleWidth, int sampleHeight,
                               MemorySegment heights, int capacity) {
        if (netherFloorAreaSampled == null) {
            throw new NativeException("Native library does not provide the Nether floor");
        }
        try {
            return (int) netherFloorAreaSampled.invokeExact(context, originBlockX, originBlockZ,
                y, sampleStep, sampleWidth, sampleHeight, heights, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_nether_floor_area_sampled", error);
        }
    }

    int heightAreaSampled(MemorySegment context, int dimension, int originBlockX,
                          int originBlockZ, int sampleStep, int sampleWidth,
                          int sampleHeight, MemorySegment heights, int capacity) {
        if (heightAreaSampled == null) {
            throw new NativeException("Native library does not provide terrain heights");
        }
        try {
            return (int) heightAreaSampled.invokeExact(context, dimension, originBlockX,
                originBlockZ, sampleStep, sampleWidth, sampleHeight, heights, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_height_area_sampled", error);
        }
    }

    String platform() {
        return library.platform();
    }

    int abiVersion() {
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable error) {
            throw callFailure("sax_abi_version", error);
        }
    }

    int capabilities() {
        try {
            return (int) capabilities.invokeExact();
        } catch (Throwable error) {
            throw callFailure("sax_capabilities", error);
        }
    }

    int engineVersion(MemorySegment out, int capacity) {
        try {
            return (int) engineVersion.invokeExact(out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_engine_version", error);
        }
    }

    MemorySegment create(long seed, int flags) {
        try {
            return (MemorySegment) create.invokeExact(seed, flags);
        } catch (Throwable error) {
            throw callFailure("sax_create", error);
        }
    }

    void destroy(MemorySegment context) {
        try {
            destroy.invokeExact(context);
        } catch (Throwable error) {
            throw callFailure("sax_destroy", error);
        }
    }

    int biomeAt(MemorySegment context, int dimension, int x, int y, int z,
                MemorySegment out) {
        try {
            return (int) biomeAt.invokeExact(context, dimension, x, y, z, out);
        } catch (Throwable error) {
            throw callFailure("sax_biome_at", error);
        }
    }

    int biomeChunk(MemorySegment context, int dimension, int chunkX, int chunkZ,
                   int y, MemorySegment ids, MemorySegment colors) {
        try {
            return (int) biomeChunk.invokeExact(
                context, dimension, chunkX, chunkZ, y, ids, colors);
        } catch (Throwable error) {
            throw callFailure("sax_biome_chunk", error);
        }
    }

    int biomeChunkSampled(MemorySegment context, int dimension, int chunkX, int chunkZ,
                          int y, int sampleStep, MemorySegment ids,
                          MemorySegment colors) {
        try {
            return (int) biomeChunkSampled.invokeExact(
                context, dimension, chunkX, chunkZ, y, sampleStep, ids, colors);
        } catch (Throwable error) {
            throw callFailure("sax_biome_chunk_sampled", error);
        }
    }

    int biomeAreaSampled(MemorySegment context, int dimension, int originBlockX,
                         int originBlockZ, int y, int sampleStep, int sampleWidth,
                         int sampleHeight, MemorySegment ids, MemorySegment colors,
                         int capacity) {
        try {
            return (int) biomeAreaSampled.invokeExact(
                context, dimension, originBlockX, originBlockZ, y, sampleStep,
                sampleWidth, sampleHeight, ids, colors, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_biome_area_sampled", error);
        }
    }

    int biomeName(int id, MemorySegment out, int capacity) {
        try {
            return (int) biomeName.invokeExact(id, out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_biome_name", error);
        }
    }

    int biomeColor(int id) {
        try {
            return (int) biomeColor.invokeExact(id);
        } catch (Throwable error) {
            throw callFailure("sax_biome_color", error);
        }
    }

    int scanStructures(MemorySegment context, int type, BlockBox box,
                       boolean biomeCheck, MemorySegment out, int capacity) {
        try {
            return (int) scanStructures.invokeExact(context, type,
                box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                biomeCheck ? 1 : 0, out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_scan_structures", error);
        }
    }

    int scanStrongholds(MemorySegment context, BlockBox box, boolean biomeCheck,
                        MemorySegment out, int capacity) {
        try {
            return (int) scanStrongholds.invokeExact(context,
                box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                biomeCheck ? 1 : 0, out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_scan_strongholds", error);
        }
    }

    int spawn(MemorySegment context, boolean detailed, MemorySegment out) {
        try {
            return (int) spawn.invokeExact(context, detailed ? 1 : 0, out);
        } catch (Throwable error) {
            throw callFailure("sax_spawn", error);
        }
    }

    int scanSlimeChunks(MemorySegment context, BlockBox box,
                        MemorySegment out, int capacity) {
        try {
            return (int) scanSlimeChunks.invokeExact(context,
                box.minX(), box.minZ(), box.maxX(), box.maxZ(), out, capacity);
        } catch (Throwable error) {
            throw callFailure("sax_scan_slime_chunks", error);
        }
    }

    private static MethodHandle downcall(SymbolLookup symbols, String name,
                                         FunctionDescriptor descriptor) {
        MemorySegment symbol = symbols.find(name)
            .orElseThrow(() -> new NativeException("Native symbol is missing: " + name));
        return Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }

    private static NativeException callFailure(String function, Throwable error) {
        if (error instanceof NativeException nativeException) {
            return nativeException;
        }
        return new NativeException("Native call failed: " + function, error);
    }
}

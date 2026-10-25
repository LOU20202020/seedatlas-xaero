package org.seedatlas.xaero.integration.biome;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/** Seed-only vanilla base terrain; never reads a world, constructs a chunk, or adds vegetation. */
public final class OverworldTerrainSampler {
    private static final int TILE_SIZE = 16;
    private static final int MAX_DIMENSION = 262;
    // 16x16 samples each, ~3 KB: enough to keep a dozen detailed map tiles for zooming and panning back.
    private static final int MAX_CACHED_TILES = 4096;
    // Density is sampled top-down in slabs of this many blocks, a multiple of the 8-block noise cell,
    // and stops once every column has its floor; columns rarely need the deep half of the world.
    private static final int SLAB_HEIGHT = 64;
    // The Overworld top slide fixes density to -0.078125 from 64 blocks below the world top up, and
    // fluid only fills air below sea level, so every block from there up is air.
    private static final int TOP_SLIDE_END_DEPTH = 64;
    private static final int MAX_CONTEXTS = 2;
    private static final LinkedHashMap<TileKey, SurfaceGrid> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<WorldKey, Context> CONTEXTS = new LinkedHashMap<>(4, 0.75f, true);
    private static final ConcurrentHashMap<String, Holder<Biome>> PREVIEW_BIOMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TileKey, CompletableFuture<SurfaceGrid>> GENERATING = new ConcurrentHashMap<>();

    private OverworldTerrainSampler() { }

    /**
     * Row-major arrays, sampled at origin + index * step + (step - 1) / 2, including any caller halo.
     * Heights are the highest non-air base block Y (including fluid); floors are the highest solid Y.
     * These match base columns, not surface rules, carvers, structures, features, ice or snow layers.
     * Colours infer surface material from the base block, surface biome and temperature, not exact blocks.
     * Grass and leaves use the biome's vanilla tint; forest crowns are decoration, not predicted trees.
     * Minecraft must already be bootstrapped. Steps 1/2/4/8/16 and dimensions 1..262 are supported;
     * coarse steps still evaluate real vanilla density and are substantially more expensive per sample.
     */
    public static SurfaceGrid sample(long seed, boolean largeBiomes, int originX, int originZ,
                                     int step, int width, int height) {
        return sample(seed, largeBiomes, originX, originZ, step, width, height, () -> true);
    }

    /** Cancels stale work between output rows and before each tile it would generate. Safe to call from several threads. */
    public static SurfaceGrid sample(long seed, boolean largeBiomes, int originX, int originZ,
                                     int step, int width, int height, BooleanSupplier stillCurrent) {
        if (step < 1 || step > 16 || (step & (step - 1)) != 0) {
            throw new IllegalArgumentException("step must be one of 1, 2, 4, 8, or 16");
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Surface dimensions must be between 1 and " + MAX_DIMENSION);
        }
        if ((long) originX + (long) width * step - 1 > Integer.MAX_VALUE
                || (long) originZ + (long) height * step - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Surface area exceeds block coordinate limits");
        }
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        var world = new WorldKey(seed, largeBiomes);
        var result = new SurfaceGrid(new float[width * height], new float[width * height], new int[width * height]);
        int span = TILE_SIZE * step;
        for (int z = 0; z < height; ++z) {
            requireCurrent(stillCurrent);
            int blockZ = originZ + z * step + (step - 1) / 2;
            for (int x = 0; x < width; ++x) {
                int blockX = originX + x * step + (step - 1) / 2;
                var key = new TileKey(world, step, Math.floorDiv(blockX, span), Math.floorDiv(blockZ, span),
                        Math.floorMod(blockX, step), Math.floorMod(blockZ, step));
                var tile = tile(key, stillCurrent);
                int source = (Math.floorMod(blockZ, span) / step) * TILE_SIZE + Math.floorMod(blockX, span) / step;
                int target = z * width + x;
                result.heights[target] = tile.heights[source];
                result.floors[target] = tile.floors[source];
                result.colors[target] = tile.colors[source];
            }
        }
        return result;
    }

    public record SurfaceGrid(float[] heights, float[] floors, int[] colors) { }

    /** Cheap LOD preview using the same material palette, without evaluating terrain density. */
    static int previewColor(String name, int x, int y, int z, boolean water, int step) {
        if (name == null) return -1;
        var biome = PREVIEW_BIOMES.computeIfAbsent(name, value -> Vanilla.LOOKUP.lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, Identifier.parse(value))).orElse(null));
        return biome == null ? -1 : surfaceColor(biome, x, y, z, water, 63, step);
    }

    private static SurfaceGrid tile(TileKey key, BooleanSupplier stillCurrent) {
        synchronized (CACHE) {
            var cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        requireCurrent(stillCurrent);
        // RandomState is shared by vanilla's own parallel chunk generation, so workers generate
        // different tiles at once; one that another worker is already generating is awaited instead.
        var mine = new CompletableFuture<SurfaceGrid>();
        var running = GENERATING.putIfAbsent(key, mine);
        if (running != null) return await(running);
        try {
            SurfaceGrid generated;
            synchronized (CACHE) {
                generated = CACHE.get(key);
            }
            if (generated == null) {
                generated = context(key.world).generate(key);
                synchronized (CACHE) {
                    if (CACHE.size() == MAX_CACHED_TILES) CACHE.remove(CACHE.keySet().iterator().next());
                    CACHE.put(key, generated);
                }
            }
            mine.complete(generated);
            return generated;
        } catch (RuntimeException | Error error) {
            mine.completeExceptionally(error);
            throw error;
        } finally {
            GENERATING.remove(key, mine);
        }
    }

    private static SurfaceGrid await(CompletableFuture<SurfaceGrid> running) {
        try {
            return running.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) throw cause;
            if (wrapped.getCause() instanceof Error cause) throw cause;
            throw wrapped;
        }
    }

    private static Context context(WorldKey world) {
        synchronized (CONTEXTS) {
            var context = CONTEXTS.get(world);
            if (context == null) {
                context = new Context(world);
                if (CONTEXTS.size() == MAX_CONTEXTS) CONTEXTS.remove(CONTEXTS.keySet().iterator().next());
                CONTEXTS.put(world, context);
            }
            return context;
        }
    }

    private static void requireCurrent(BooleanSupplier stillCurrent) {
        if (!stillCurrent.getAsBoolean()) throw new CancellationException("Overworld sample is no longer current");
    }

    private record WorldKey(long seed, boolean largeBiomes) { }
    private record TileKey(WorldKey world, int step, int x, int z, int phaseX, int phaseZ) { }

    private static final class Context {
        private final NoiseGeneratorSettings settings;
        private final RandomState state;
        private final Aquifer.FluidPicker fluids;

        private Context(WorldKey key) {
            settings = Vanilla.LOOKUP.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(
                    key.largeBiomes ? NoiseGeneratorSettings.LARGE_BIOMES : NoiseGeneratorSettings.OVERWORLD).value();
            state = RandomState.create(Vanilla.LOOKUP.lookupOrThrow(Registries.NOISE), key.seed, settings);
            var water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
            var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            // Same built-in Overworld global picker as NoiseBasedChunkGenerator (without debug fluid disabling).
            fluids = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : water;
        }

        private SurfaceGrid generate(TileKey key) {
            var noiseSettings = settings.noiseSettings();
            int minY = noiseSettings.minY();
            // The aquifer covers the whole column, as in vanilla; density comes in slabs from the top.
            var volume = new DensityVolume(TILE_SIZE, noiseSettings.height(), TILE_SIZE,
                    key.x * TILE_SIZE * key.step + key.phaseX, minY,
                    key.z * TILE_SIZE * key.step + key.phaseZ, key.step, 1, key.step);
            int columns = TILE_SIZE * TILE_SIZE;
            int[] tops = new int[columns];
            int[] floors = new int[columns];
            boolean[] fluid = new boolean[columns];
            boolean[] done = new boolean[columns];
            Arrays.fill(tops, minY - 1);
            Arrays.fill(floors, minY - 1);
            int remaining = columns;
            try (var noise = new NoiseChunk(state, null, settings, fluids, Blender.empty(), volume)) {
                var finalDensity = noise.cachingSamplers().get(settings.noiseRouter().finalDensity());
                int slabBottom;
                for (int slabTop = minY + noiseSettings.height() - TOP_SLIDE_END_DEPTH; slabTop > minY && remaining > 0;
                     slabTop = slabBottom) {
                    // Bottoms on cell boundaries keep vanilla's interpolation, and so every density, bit-identical.
                    slabBottom = Math.max(minY, slabTop - SLAB_HEIGHT);
                    // Only the rectangle of columns still looking for their floor, e.g. the sea part of a coast.
                    int minX = TILE_SIZE, maxX = -1, minZ = TILE_SIZE, maxZ = -1;
                    for (int index = 0; index < columns; ++index) {
                        if (done[index]) continue;
                        minX = Math.min(minX, index % TILE_SIZE);
                        maxX = Math.max(maxX, index % TILE_SIZE);
                        minZ = Math.min(minZ, index / TILE_SIZE);
                        maxZ = Math.max(maxZ, index / TILE_SIZE);
                    }
                    var slab = new DensityVolume(maxX - minX + 1, slabTop - slabBottom, maxZ - minZ + 1,
                            volume.blockX(minX), slabBottom, volume.blockZ(minZ), key.step, 1, key.step);
                    try (var density = finalDensity.sampleVolume(slab)) {
                        for (int z = minZ; z <= maxZ; ++z) {
                            int blockZ = volume.blockZ(z);
                            for (int x = minX; x <= maxX; ++x) {
                                int index = z * TILE_SIZE + x;
                                if (done[index]) continue;
                                int blockX = volume.blockX(x);
                                for (int y = slab.sizeY() - 1; y >= 0; --y) {
                                    int blockY = slab.blockY(y);
                                    var block = noise.aquifer().computeSubstance(blockX, blockY, blockZ,
                                            density.get(slab.indexUnchecked(x - minX, y, z - minZ)));
                                    if (block == null) block = settings.defaultBlock();
                                    if (block.isAir()) continue;
                                    if (tops[index] < minY) {
                                        tops[index] = blockY;
                                        fluid[index] = !block.getFluidState().isEmpty();
                                    }
                                    if (block.getFluidState().isEmpty()) {
                                        floors[index] = blockY;
                                        done[index] = true;
                                        --remaining;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var result = new SurfaceGrid(new float[columns], new float[columns], new int[columns]);
            var resolver = Vanilla.BIOMES.createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
            for (int z = 0; z < TILE_SIZE; ++z) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < TILE_SIZE; ++x) {
                    int blockX = volume.blockX(x);
                    int index = z * TILE_SIZE + x;
                    int top = tops[index];
                    result.heights[index] = top;
                    result.floors[index] = floors[index];
                    var biome = resolver.getNoiseBiome(blockX >> 2, top >> 2, blockZ >> 2);
                    result.colors[index] = surfaceColor(biome, blockX, top, blockZ, fluid[index], settings.seaLevel(), key.step);
                }
            }
            return result;
        }
    }

    /** Surface colour of one sample; see {@link BiomeSurfacePalette}. */
    static int surfaceColor(Holder<Biome> biome, int x, int y, int z, boolean fluid, int seaLevel, int step) {
        return BiomeSurfacePalette.surfaceColor(biome, x, y, z, fluid, seaLevel, step);
    }

    private static final class Vanilla {
        private static final HolderLookup.Provider LOOKUP = VanillaRegistries.createWorldLookup();
        private static final MultiNoiseBiomeSource BIOMES = MultiNoiseBiomeSource.createFromPreset(
                LOOKUP.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
    }
}

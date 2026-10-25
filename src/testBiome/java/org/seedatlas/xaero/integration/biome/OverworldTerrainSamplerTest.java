package org.seedatlas.xaero.integration.biome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.seedatlas.xaero.integration.biome.OverworldTerrainSampler.SurfaceGrid;

/** Standalone vanilla 26.3 base-column checks; no game, world or chunks are constructed. */
public final class OverworldTerrainSamplerTest {
    private static final long SEED = 2842062689604764280L;
    private static HolderLookup.Provider lookup;
    private static MultiNoiseBiomeSource biomes;
    private static int parityColumns;

    public static void main(String[] args) throws Exception {
        invalidAreas();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(SharedConstants.getCurrentVersion().name().equals("26.3"), "Requires Minecraft 26.3");
        lookup = VanillaRegistries.createWorldLookup();
        biomes = MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        baseColumnParity();
        materialCategories();
        previewPalette();
        samplingAndHalos();
        cancellation();
        isolationAndConcurrentReads();
        boundedCaches();
        savedWorldSpots();
        System.out.println("Overworld sampler: 9 test groups passed; " + parityColumns
                + " columns match vanilla getBaseColumn/getBaseHeight (normal + large biomes)");
        if (Arrays.asList(args).contains("--benchmark")) benchmark();
    }

    private static void invalidAreas() {
        for (int step : new int[]{Integer.MIN_VALUE, -1, 0, 3, 32, Integer.MAX_VALUE}) {
            rejects(() -> sample(SEED, false, 0, 0, step, 1, 1), "Invalid step " + step);
        }
        for (int size : new int[]{-1, 0, 263, Integer.MAX_VALUE}) {
            rejects(() -> sample(SEED, false, 0, 0, 1, size, 1), "Invalid width " + size);
            rejects(() -> sample(SEED, false, 0, 0, 1, 1, size), "Invalid height " + size);
        }
        rejects(() -> sample(SEED, false, Integer.MAX_VALUE, 0, 2, 1, 1), "X extent overflow");
        rejects(() -> sample(SEED, false, 0, Integer.MAX_VALUE, 4, 1, 1), "Z extent overflow");
        rejects(() -> sample(SEED, false, Integer.MAX_VALUE - 15, 0, 16, 2, 1), "Last cell overflow");
        cancelled(() -> OverworldTerrainSampler.sample(SEED, false, 0, 0, 1, 1, 1, () -> false));
    }

    private static void previewPalette() {
        String[] names = {"plains", "swamp", "desert", "stony_shore", "snowy_beach", "ocean", "beach", "mangrove_swamp", "cherry_grove"};
        int[] ids = {1, 6, 2, 25, 26, 0, 16, 184, 185};
        for (int i = 0; i < names.length; ++i) {
            boolean water = BiomeTerrainStyler.waterBiome(ids[i]);
            float[] floors = new float[9], top = new float[9];
            Arrays.fill(floors, water ? 48 : 64);
            Arrays.fill(top, water ? 62 : 64);
            int color = OverworldTerrainSampler.previewColor("minecraft:" + names[i], -41, 64, -266, water, 1);
            check(color == OverworldTerrainSampler.previewColor(names[i], -41, 64, -266, water, 1),
                "Java namespaced biome names and native bare names resolve identically");
            check(color >= 0, "Native biome name resolves to the vanilla preview palette: " + names[i]);
            int[] fullColors = new int[9];
            Arrays.fill(fullColors, color);
            int[] detailed = {0xa000ffff};
            short[] biomeIds = {(short) ids[i]};
            BiomeTerrainStyler.applySurface(detailed, biomeIds, 1, 1, top, floors, fullColors);
            for (int step : new int[]{1, 4, 16, 64, 256, 1024}) {
                int[] preview = {0xa000ffff};
                BiomeTerrainStyler.applyPreview(preview, biomeIds, step, 1, floors, new int[]{color});
                check(preview[0] == detailed[0], "Flat preview/detail colours match at LOD " + step + " biome=" + names[i]);
            }
        }
        check(OverworldTerrainSampler.previewColor("not_a_vanilla_biome", 0, 64, 0, false, 1) == -1,
            "Unknown biomes do not invent preview materials");
        int[] unknown = {0xa0123456};
        BiomeTerrainStyler.applyPreview(unknown, new short[]{(short) 65535}, 64, 1, null, new int[]{-1});
        check(unknown[0] == 0xa0123456, "Unknown preview samples preserve colour and alpha");
    }

    private static Reference reference(long seed, boolean large) {
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(
                large ? NoiseGeneratorSettings.LARGE_BIOMES : NoiseGeneratorSettings.OVERWORLD);
        return new Reference(new NoiseBasedChunkGenerator(biomes, settings),
                RandomState.create(lookup.lookupOrThrow(Registries.NOISE), seed, settings.value()),
                LevelHeightAccessor.create(settings.value().noiseSettings().minY(), settings.value().noiseSettings().height()));
    }

    private static void baseColumnParity() {
        var random = new Random(0x26_03);
        for (long seed : new long[]{SEED, 0, -73593}) for (boolean large : new boolean[]{false, true}) {
            var reference = reference(seed, large);
            for (int step : new int[]{1, 2, 4, 8, 16}) {
                int originX = random.nextInt(20001) - 10000;
                int originZ = random.nextInt(20001) - 10000;
                var area = sample(seed, large, originX, originZ, step, 3, 2);
                for (int z = 0; z < 2; ++z) for (int x = 0; x < 3; ++x) {
                    parity(reference, area, z * 3 + x,
                            originX + x * step + (step - 1) / 2, originZ + z * step + (step - 1) / 2);
                }
            }
        }
        // Find named biome categories independently of the implementation, then compare their real columns.
        var reference = reference(SEED, false);
        var resolver = biomes.createResolver(reference.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
        var found = new LinkedHashMap<String, int[]>();
        for (int attempt = 0; attempt < 20000 && found.size() < 4; ++attempt) {
            int x = random.nextInt(20001) - 10000;
            int z = random.nextInt(20001) - 10000;
            var biome = resolver.getNoiseBiome(x >> 2, 16, z >> 2);
            String id = biome.unwrapKey().orElseThrow().identifier().getPath();
            String category = id.contains("ocean") ? "ocean" : id.contains("beach") ? "beach"
                    : id.contains("swamp") ? "swamp" : id.contains("peaks") ? "mountains" : null;
            if (category != null && !found.containsKey(category)) {
                var area = sample(SEED, false, x, z, 1, 1, 1);
                parity(reference, area, 0, x, z);
                found.put(category, new int[]{x, z});
                System.out.printf("Category parity: %s biome=%s x=%d z=%d top=%.0f floor=%.0f%n",
                        category, id, x, z, area.heights()[0], area.floors()[0]);
            }
        }
        check(found.size() == 4, "Parity includes ocean, beach, swamp and mountain biome columns");
    }

    private static void parity(Reference reference, SurfaceGrid area, int index, int x, int z) {
        var column = reference.generator.getBaseColumn(x, z, reference.height, reference.state);
        int top = reference.height.getMinY() - 1;
        int floor = top;
        for (int y = reference.height.getMaxY(); y >= reference.height.getMinY(); --y) {
            var block = column.getBlock(y);
            if (block.isAir()) continue;
            if (top < reference.height.getMinY()) top = y;
            if (block.getFluidState().isEmpty()) { floor = y; break; }
        }
        check(area.heights()[index] == top && area.floors()[index] == floor,
                "Base column parity at " + x + "," + z + " expected=" + top + "/" + floor
                        + " actual=" + area.heights()[index] + "/" + area.floors()[index]);
        int baseHeight = reference.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                reference.height, reference.state);
        check(area.heights()[index] == baseHeight - 1, "Base-height first-air conversion at " + x + "," + z);
        // OCEAN_FLOOR_WG needs datapack block tags; this offline bootstrap checks solid column blocks directly.
        if (!column.getBlock(top).getFluidState().isEmpty()) {
            var resolver = biomes.createResolver(reference.state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
            int waterColor = resolver.getNoiseBiome(x >> 2, top >> 2, z >> 2).value().getWaterColor() & 0xffffff;
            check(area.colors()[index] == BiomeSurfacePalette.water(waterColor),
                    "Actual base fluid uses surface-biome water tint");
        }
        check(floor <= top, "Ground cannot exceed visible surface");
        for (int y = reference.height.getMaxY() - 63; y <= reference.height.getMaxY(); ++y) {
            check(column.getBlock(y).isAir(), "Only air above the top slide at " + x + "," + y + "," + z);
        }
        ++parityColumns;
    }

    private static void materialCategories() {
        var registry = lookup.lookupOrThrow(Registries.BIOME);
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.DESERT), 0, 70, 0, false, 63, 1)
                == 0xd8cf9b, "Desert sand is cream, not grass");
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.BEACH), 0, 64, 0, false, 63, 1)
                == 0xd8cf9b, "Beach sand is cream");
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.STONY_SHORE), 0, 64, 0, false, 63, 1)
                == 0x858585, "Stony shore is stone, not sand");
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.SNOWY_PLAINS), 0, 70, 0, false, 63, 1)
                == 0xe8eff0, "Cold land gets inferred snow");
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.SNOWY_TAIGA), 0, 90, 0, false, 63, 1)
                == 0xe8eff0, "Snow covers the crowns of snowy forests");
        // Without client resources the colormap triangle stands in for grass.png: plains (0.8, 0.4).
        int plains = OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.PLAINS), 0, 70, 0, false, 63, 1);
        check(plains == BiomeSurfacePalette.grass(0x8cbd57), "Plains grass follows the vanilla colormap: "
                + Integer.toHexString(plains));
        // Savanna (2.0, 0.0) is the hot-dry corner, so the network-codec downfall reached the lookup.
        check(BiomeSurfacePalette.ground(registry.getOrThrow(Biomes.SAVANNA), 0, 70, 0, false, 63)
                == BiomeSurfacePalette.grass(0xbfb755), "Savanna grass is the dry olive corner");
        var swamp = registry.getOrThrow(Biomes.SWAMP);
        for (int x : new int[]{-512, 0, 512}) {
            check(BiomeSurfacePalette.ground(swamp, x, 63, -266, false, 63)
                            == BiomeSurfacePalette.grass(swamp.value().getGrassColor(x, -266) & 0xffffff),
                    "Swamp ground uses the vanilla grass modifier");
            check(OverworldTerrainSampler.surfaceColor(swamp, x, 62, -266, true, 63, 1)
                            == BiomeSurfacePalette.water(swamp.value().getWaterColor()), "Actual fluid gets vanilla biome water tint");
        }
        check(OverworldTerrainSampler.surfaceColor(registry.getOrThrow(Biomes.SNOWY_PLAINS), 0, 62, 0, true, 63, 1)
                        == BiomeSurfacePalette.water(registry.getOrThrow(Biomes.SNOWY_PLAINS).value().getWaterColor()),
                "Frozen-biome water remains base fluid, not a claim of generated ice");
        biomeDistinction(registry);
        crownCoverage(registry);
    }

    /** Common land biomes must stay distinguishable at every zoom, not collapse into one green. */
    private static void biomeDistinction(HolderGetter<Biome> registry) {
        var keys = List.of(Biomes.PLAINS, Biomes.MEADOW, Biomes.FOREST, Biomes.BIRCH_FOREST, Biomes.DARK_FOREST,
                Biomes.TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.JUNGLE, Biomes.SAVANNA, Biomes.SWAMP,
                Biomes.CHERRY_GROVE, Biomes.PALE_GARDEN, Biomes.MANGROVE_SWAMP, Biomes.FLOWER_FOREST);
        for (int step : new int[]{1, 16, 1024}) {
            var seen = new HashMap<Integer, String>();
            for (var key : keys) {
                int color = average(registry.getOrThrow(key), step);
                String previous = seen.put(color, key.identifier().getPath());
                check(previous == null, "Biomes share one colour at step " + step + ": " + previous + " / " + key);
            }
        }
        int savanna = average(registry.getOrThrow(Biomes.SAVANNA), 16);
        int jungle = average(registry.getOrThrow(Biomes.JUNGLE), 16);
        int taiga = average(registry.getOrThrow(Biomes.TAIGA), 16);
        int cherry = average(registry.getOrThrow(Biomes.CHERRY_GROVE), 16);
        check(red(savanna) - green(savanna) > red(jungle) - green(jungle), "Savanna reads drier than jungle");
        check(blue(taiga) * 255 / Math.max(1, green(taiga)) > blue(jungle) * 255 / Math.max(1, green(jungle)),
                "Taiga reads bluer than jungle");
        check(red(cherry) > red(jungle) + 20, "Cherry groves show pink crowns");
    }

    private static void crownCoverage(HolderGetter<Biome> registry) {
        var forest = registry.getOrThrow(Biomes.FOREST);
        int ground = BiomeSurfacePalette.ground(forest, 0, 70, 0, false, 63);
        int leaf = BiomeSurfacePalette.leaf(forest);
        check(leaf >= 0 && leaf != ground, "Forests have their own leaf colour");
        check(BiomeSurfacePalette.leaf(registry.getOrThrow(Biomes.PLAINS)) == -1, "Plains have no crowns");
        int crowns = 0, total = 0;
        for (int z = -300; z < 300; ++z) for (int x = -300; x < 300; ++x) {
            int color = BiomeSurfacePalette.crowns(ground, leaf, 0.55F, x, z, 1);
            check(color == BiomeSurfacePalette.crowns(ground, leaf, 0.55F, x, z, 1), "Crowns are deterministic");
            if (color != ground) ++crowns;
            ++total;
        }
        double coverage = crowns / (double) total;
        check(coverage > 0.4 && coverage < 0.7, "Crown coverage follows the forest density: " + coverage);
        check(BiomeSurfacePalette.crowns(ground, leaf, 0.55F, 7, 9, 16) == BiomeSurfacePalette.mix(ground, leaf, 0.55F),
                "Coarse samples blend leaves by coverage instead of dotting");
    }

    /** Mean colour over a block area at one LOD, which is what a viewer perceives. */
    private static int average(Holder<Biome> biome, int step) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int z = 0; z < 160; z += Math.min(step, 8)) for (int x = 0; x < 160; x += Math.min(step, 8)) {
            int color = OverworldTerrainSampler.surfaceColor(biome, x, 70, z, false, 63, step);
            r += red(color); g += green(color); b += blue(color); ++n;
        }
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static int red(int color) { return color >> 16 & 255; }
    private static int green(int color) { return color >> 8 & 255; }
    private static int blue(int color) { return color & 255; }

    private static void samplingAndHalos() {
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            int width = 5, height = 3, originX = -41, originZ = -266;
            var area = sample(SEED, false, originX, originZ, step, width, height);
            var halo = sample(SEED, false, originX - step, originZ - step, step, width + 2, height + 2);
            for (int z = 0; z < height; ++z) for (int x = 0; x < width; ++x) {
                var block = sample(SEED, false, originX + x * step + (step - 1) / 2,
                        originZ + z * step + (step - 1) / 2, 1, 1, 1);
                // Samples of 8+ blocks blend forest crowns by coverage, so only their columns match a block.
                int index = z * width + x;
                check(area.heights()[index] == block.heights()[0] && area.floors()[index] == block.floors()[0]
                        && (step >= 8 || area.colors()[index] == block.colors()[0]),
                        "LOD lower-centre coordinate step=" + step);
                sameSample(area, z * width + x, halo, (z + 1) * (width + 2) + x + 1,
                        "Negative-origin halo consistency step=" + step);
            }
        }
        var reference = reference(SEED, false);
        for (int phaseZ = 0; phaseZ < 4; ++phaseZ) for (int phaseX = 0; phaseX < 4; ++phaseX) {
            var phased = sample(SEED, false, -48 + phaseX, -272 + phaseZ, 4, 1, 1);
            parity(reference, phased, 0, -47 + phaseX, -271 + phaseZ);
        }
    }

    private static void isolationAndConcurrentReads() throws Exception {
        var expected = sample(SEED, false, -64, -272, 4, 13, 9);
        var large = sample(SEED, true, -64, -272, 4, 13, 9);
        check(!Arrays.equals(expected.heights(), large.heights()), "Large-biome noise settings affect actual terrain");
        check(!Arrays.equals(expected.floors(), sample(SEED + 1, false, -64, -272, 4, 13, 9).floors()),
                "Cache distinguishes seeds");
        var mutable = sample(SEED, false, -64, -272, 4, 13, 9);
        Arrays.fill(mutable.heights(), -999);
        Arrays.fill(mutable.floors(), -999);
        Arrays.fill(mutable.colors(), -999);
        sameGrid(expected, sample(SEED, false, -64, -272, 4, 13, 9), "Caller cannot mutate cached arrays");
        clearCaches();
        var jobs = new ArrayList<Callable<Void>>();
        for (int thread = 0; thread < 4; ++thread) {
            final boolean mode = (thread & 1) != 0;
            jobs.add(() -> {
                for (int repeat = 0; repeat < 5; ++repeat) {
                    sameGrid(mode ? large : expected, sample(SEED, mode, -64, -272, 4, 13, 9),
                            "Concurrent cold generation and warm reads");
                }
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(jobs)) result.get();
        }
    }

    private static void cancellation() throws Exception {
        clearCaches();
        var checks = new AtomicInteger();
        cancelled(() -> OverworldTerrainSampler.sample(SEED, false, 0, 0, 1, 33, 2,
                () -> checks.incrementAndGet() < 3));
        check(checks.get() == 3 && cache("CACHE").size() == 1, "Cancellation stops before the next density batch");
        checks.set(0);
        cancelled(() -> OverworldTerrainSampler.sample(SEED, false, 0, 0, 1, 1, 2,
                () -> checks.incrementAndGet() < 2));
        check(checks.get() == 2 && cache("CACHE").size() == 1, "Cached reads also check cancellation each row");
    }

    private static void boundedCaches() throws Exception {
        clearCaches();
        int bound = constant("MAX_CACHED_TILES");
        var expected = sample(SEED, false, -200, 14, 1, 1, 1);
        for (int tile = 0; tile <= bound; ++tile) sample(SEED, false, tile * 16, 0, 1, 1, 1);
        check(cache("CACHE").size() == bound, "Spatial cache bounded at " + bound + " tiles");
        sameGrid(expected, sample(SEED, false, -200, 14, 1, 1, 1), "Eviction regenerates identically");
        for (int seed = 0; seed < 3; ++seed) sample(seed, false, 0, 0, 1, 1, 1);
        check(cache("CACHE").size() == bound, "All seeds and modes share the tile bound");
        check(cache("CONTEXTS").size() == 2, "Mutable vanilla generation contexts bounded at two");
        var max = sample(SEED, false, -262, -262, 1, 262, 262);
        check(max.heights().length == 262 * 262 && max.floors().length == max.colors().length,
                "Maximum halo dimensions preserve row-major array lengths");
    }

    private static void savedWorldSpots() {
        var reference = reference(SEED, false);
        // Read-only parent observations are context, not post-features parity assertions.
        for (int[] spot : new int[][]{{-41, -266, 63}, {-32, -256, 64}, {-64, -256, 68},
                {0, -256, 62}, {-170, 14, 67}, {0, 0, 62}}) {
            var area = sample(SEED, false, spot[0], spot[1], 1, 1, 1);
            parity(reference, area, 0, spot[0], spot[1]);
            System.out.printf("Saved-world spot (%d,%d): base top=%.0f floor=%.0f RGB=%06x; observed ground/water Y=%d%n",
                    spot[0], spot[1], area.heights()[0], area.floors()[0], area.colors()[0], spot[2]);
        }
    }

    private static void benchmark() throws Exception {
        System.out.printf("Sampler benchmark: MC=26.3 seed=%d largeBiomes=false size=258x258"
                        + " Java=%s arch=%s cache=%d tiles contexts=2%n",
                SEED, System.getProperty("java.version"), System.getProperty("os.arch"), constant("MAX_CACHED_TILES"));
        // Spawn coast and ocean, then the frozen peaks found by the parity check.
        for (int[] origin : new int[][]{{-256, -256}, {-6912, -4352}}) {
            for (int step : new int[]{1, 2, 4}) {
                clearCaches();
                long start = System.nanoTime();
                var cold = sample(SEED, false, origin[0], origin[1], step, 258, 258);
                double coldMs = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime();
                var warm = sample(SEED, false, origin[0], origin[1], step, 258, 258);
                double warmMs = (System.nanoTime() - start) / 1e6;
                sameGrid(cold, warm, "Benchmark determinism");
                System.out.printf(Locale.ROOT, "origin=(%d,%d) step=%d cold=%.1fms warm=%.1fms tiles=%d"
                                + " heightsHash=%d floorsHash=%d colorsHash=%d%n",
                        origin[0], origin[1], step, coldMs, warmMs, cache("CACHE").size(), Arrays.hashCode(cold.heights()),
                        Arrays.hashCode(cold.floors()), Arrays.hashCode(cold.colors()));
            }
        }
        // Six neighbouring map tiles at once, as six renderer workers request them; shared halos are deduplicated.
        for (int step : new int[]{1, 2, 4}) {
            clearCaches();
            int span = 256 * step;
            var jobs = new ArrayList<Callable<SurfaceGrid>>();
            for (int tile = 0; tile < 6; ++tile) {
                int originX = -256 + (tile % 3) * span - step, originZ = -256 + (tile / 3) * span - step;
                jobs.add(() -> sample(SEED, false, originX, originZ, step, 258, 258));
            }
            long start = System.nanoTime();
            try (var executor = Executors.newFixedThreadPool(6)) {
                for (var result : executor.invokeAll(jobs)) result.get();
            }
            System.out.printf(Locale.ROOT, "step=%d six tiles on six threads=%.1fms%n", step, (System.nanoTime() - start) / 1e6);
        }
    }

    private static int constant(String name) throws Exception {
        var field = OverworldTerrainSampler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static SurfaceGrid sample(long seed, boolean large, int x, int z, int step, int width, int height) {
        return OverworldTerrainSampler.sample(seed, large, x, z, step, width, height);
    }

    private static void sameGrid(SurfaceGrid a, SurfaceGrid b, String message) {
        check(Arrays.equals(a.heights(), b.heights()) && Arrays.equals(a.floors(), b.floors())
                && Arrays.equals(a.colors(), b.colors()), message);
    }

    private static void sameSample(SurfaceGrid a, int ai, SurfaceGrid b, int bi, String message) {
        check(a.heights()[ai] == b.heights()[bi] && a.floors()[ai] == b.floors()[bi]
                && a.colors()[ai] == b.colors()[bi], message);
    }

    private static Map<?, ?> cache(String name) throws Exception {
        var field = OverworldTerrainSampler.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static void clearCaches() throws Exception {
        cache("CACHE").clear();
        cache("CONTEXTS").clear();
    }

    private record Reference(NoiseBasedChunkGenerator generator, RandomState state, LevelHeightAccessor height) { }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void cancelled(Runnable action) {
        try { action.run(); } catch (CancellationException expected) { return; }
        throw new AssertionError("Stale sampling must throw CancellationException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package org.seedatlas.xaero.integration.biome;

import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.seedatlas.xaero.nativeapi.BiomeRegion;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.WorldType;

/**
 * Times the work one map tile costs before it can appear, per dimension and zoom step, mirroring the
 * renderer's first pass (and the Overworld's later refinement). Single-threaded; distinct origins per
 * run so seed-derived caches stay cold. Run with {@code ./gradlew dimensionTileBenchmark}.
 */
public final class DimensionTileBenchmark {
    private static final long SEED = 2842062689604764280L;
    private static final int RUNS = 4;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        System.out.printf(Locale.ROOT, "Tile benchmark: MC=%s seed=%d runs=%d Java=%s%n",
            SharedConstants.getCurrentVersion().name(), SEED, RUNS, System.getProperty("java.version"));
        try (SeedAtlasNative atlas = SeedAtlasNative.open(SEED, WorldType.NORMAL)) {
            // Fine view steps use 256-sample tiles, coarse ones and their parents 128.
            int[][] levels = {{1, 256}, {2, 256}, {4, 128}, {4, 256}, {16, 128}};
            for (int[] level : levels) {
                int step = level[0], size = level[1];
                System.out.printf(Locale.ROOT, "step=%2d size=%d  overworld=%7.1fms  (refine %7.1fms)"
                        + "  nether=%7.1fms  end=%7.1fms%n", step, size,
                    time(run -> overworld(atlas, step, size, run)),
                    step <= 4 ? time(run -> overworldRefine(step, size, run)) : 0.0,
                    time(run -> nether(atlas, step, size, run)),
                    time(run -> end(atlas, step, size, run)));
                System.out.printf(Locale.ROOT, "            nether parts: biomes=%6.1fms carving=%7.1fms floor=%7.1fms"
                        + "  end parts: biomes=%6.1fms heights=%7.1fms%n",
                    time(run -> atlas.biomeArea(Dimension.NETHER, origin(step, size, run), origin(step, size, run), 64, step, size, size)),
                    step <= 4 ? time(run -> NetherCaveMask.area(SEED, origin(step, size, run) + 1, origin(step, size, run), step, size + 2, size + 2)) : 0.0,
                    time(run -> atlas.netherFloorArea(origin(step, size, run), origin(step, size, run), 64, step, size + 2, size + 2)),
                    time(run -> atlas.biomeArea(Dimension.END, 2048 + origin(step, size, run), 2048, 64, step, size, size)),
                    step <= 8 ? time(run -> atlas.heightArea(Dimension.END, 2048 + origin(step, size, run), 2048, step, size + 2, size + 2)) : 0.0);
            }
        }
    }

    private interface Tile {
        void build(int run);
    }

    private static double time(Tile tile) {
        tile.build(-1); // warm up class loading and JIT for this path
        long total = 0;
        for (int run = 0; run < RUNS; ++run) {
            long start = System.nanoTime();
            tile.build(run);
            total += System.nanoTime() - start;
        }
        return total / 1e6 / RUNS;
    }

    private static int origin(int step, int size, int run) {
        return (run + 3) * 7 * step * size;
    }

    private static void overworld(SeedAtlasNative atlas, int step, int size, int run) {
        int x = origin(step, size, run), z = -x;
        BiomeRegion region = atlas.biomeArea(Dimension.OVERWORLD, x, z, 64, step, size, size);
        float[] heights = atlas.heightArea(Dimension.OVERWORLD, x - step, z - step, step, size + 2, size + 2);
        int[] pixels = new int[size * size];
        short[] ids = new short[size * size];
        region.copyArgbSamplesTo(pixels, 0);
        region.copyBiomeIdsToUnsignedShorts(ids, 0);
        int[] colors = new int[ids.length];
        for (int i = 0; i < ids.length; ++i) {
            colors[i] = OverworldTerrainSampler.previewColor(atlas.biomeName(ids[i] & 0xffff),
                x + (i % size) * step, Math.round(heights[(i / size + 1) * (size + 2) + i % size + 1]),
                z + (i / size) * step, BiomeTerrainStyler.waterBiome(ids[i] & 0xffff), step);
        }
        if (step <= 4) {
            BiomeTerrainStyler.applyPreview(pixels, ids, step, size, heights, colors);
        } else {
            BiomeTerrainStyler.apply(pixels, ids, x, z, step, size, SEED, heights);
        }
    }

    private static void overworldRefine(int step, int size, int run) {
        int x = origin(step, size, run), z = -x;
        OverworldTerrainSampler.sample(SEED, false, x - step, z - step, step, size + 2, size + 2);
    }

    private static void nether(SeedAtlasNative atlas, int step, int size, int run) {
        int x = origin(step, size, run), z = x;
        BiomeRegion region = atlas.biomeArea(Dimension.NETHER, x, z, 64, step, size, size);
        long[] carved = step <= 4 ? NetherCaveMask.area(SEED, x - step, z - step, step, size + 2, size + 2) : null;
        float[] heights = atlas.netherFloorArea(x - step, z - step, 64, step, size + 2, size + 2, carved);
        int[] pixels = new int[size * size];
        short[] ids = new short[size * size];
        region.copyArgbSamplesTo(pixels, 0);
        region.copyBiomeIdsToUnsignedShorts(ids, 0);
        BiomeTerrainStyler.applyNether(pixels, ids, x, z, step, size, heights);
    }

    private static void end(SeedAtlasNative atlas, int step, int size, int run) {
        // Outer islands start beyond 1000 blocks; keep every run there.
        int x = 2048 + origin(step, size, run), z = 2048;
        BiomeRegion region = atlas.biomeArea(Dimension.END, x, z, 64, step, size, size);
        float[] heights = step <= 8
            ? atlas.heightArea(Dimension.END, x - step, z - step, step, size + 2, size + 2) : null;
        int[] pixels = new int[size * size];
        short[] ids = new short[size * size];
        region.copyArgbSamplesTo(pixels, 0);
        region.copyBiomeIdsToUnsignedShorts(ids, 0);
        BiomeTerrainStyler.applyEnd(pixels, ids, x, z, step, size, heights);
    }
}

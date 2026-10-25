package org.seedatlas.xaero.integration.biome;

import java.util.LinkedHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.CarverOutput;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.WorldCarver;

/** Vanilla Nether carver geometry from the seed alone; never loads or generates world chunks. */
public final class NetherCaveMask {
    private static final int MAX_DIMENSION = 262;
    private static final int MAX_CACHED_CHUNKS = 512;
    private static final LinkedHashMap<Key, long[]> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private NetherCaveMask() { }

    /**
     * Two words per row-major sample: bits 0..63, then 64..127. Set bits are carver
     * geometry, not a complete air/material prediction. Steps 1/2/4 use the lower
     * block at the native fractional centre; steps 8/16 use its nearest 4-block cell.
     * Dimensions must be 1..262 (including a tile's halo). Minecraft must already be bootstrapped.
     */
    public static long[] area(long seed, int originX, int originZ, int step, int width, int height) {
        if (step < 1 || step > 16 || (step & (step - 1)) != 0) {
            throw new IllegalArgumentException("step must be one of 1, 2, 4, 8, or 16");
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Mask dimensions must be between 1 and " + MAX_DIMENSION);
        }
        if ((long) originX + (long) width * step - 1 > Integer.MAX_VALUE
                || (long) originZ + (long) height * step - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mask area exceeds block coordinate limits");
        }
        long[] result = new long[width * height * 2];
        for (int z = 0; z < height; ++z) {
            int blockZ = sampleCoordinate(originZ, z, step);
            for (int x = 0; x < width; ++x) {
                int blockX = sampleCoordinate(originX, x, step);
                long[] chunk = chunkMask(new Key(seed, blockX >> 4, blockZ >> 4));
                int source = ((blockZ & 15) * 16 + (blockX & 15)) * 2;
                int target = (z * width + x) * 2;
                result[target] = chunk[source];
                result[target + 1] = chunk[source + 1];
            }
        }
        return result;
    }

    private static int sampleCoordinate(int origin, int index, int step) {
        long block = (long) origin + (long) index * step;
        return (int) (step >= 8 ? Math.floorDiv(block + step / 2 + 2, 4L) * 4
                : block + (step - 1) / 2);
    }

    private static synchronized long[] chunkMask(Key key) {
        long[] mask = CACHE.get(key);
        if (mask != null) return mask;
        mask = generate(key);
        if (CACHE.size() == MAX_CACHED_CHUNKS) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(key, mask);
        return mask;
    }

    private static long[] generate(Key key) {
        long[] mask = new long[16 * 16 * 2];
        var target = new ChunkPos(key.chunkX, key.chunkZ);
        var random = new WorldgenRandom(new LegacyRandomSource(0));
        var output = new CarverOutput() {
            @Override public int minY() { return 1; }
            @Override public int maxY() { return 120; }
            @Override public void carve(int x, int y, int z) {
                mask[((z & 15) * 16 + (x & 15)) * 2 + (y >>> 6)] |= 1L << (y & 63);
            }
        };
        for (int dx = -8; dx <= 8; ++dx) {
            for (int dz = -8; dz <= 8; ++dz) {
                var source = new ChunkPos(key.chunkX + dx, key.chunkZ + dz);
                int index = 0;
                for (var holder : Vanilla.CARVERS) {
                    var carver = holder.value();
                    random.setLargeFeatureSeed(key.seed + index++, source.x(), source.z());
                    if (carver.isStartChunk(random)) carver.carve(Vanilla.CONTEXT, random, target, source, output);
                }
            }
        }
        return mask;
    }

    private record Key(long seed, int chunkX, int chunkZ) { }

    private static final class Vanilla {
        private static final HolderLookup.Provider LOOKUP = VanillaRegistries.createWorldLookup();
        // All five built-in Nether biomes share these carvers; no live biome source is needed.
        private static final Iterable<Holder<WorldCarver>> CARVERS = LOOKUP.lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.NETHER_WASTES).value().getGenerationSettings().getCarvers();
        private static final WorldGenerationContext CONTEXT = new WorldGenerationContext(
                new NoiseBasedChunkGenerator(new FixedBiomeSource(LOOKUP.lookupOrThrow(Registries.BIOME)
                        .getOrThrow(Biomes.NETHER_WASTES)),
                        LOOKUP.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER)),
                LevelHeightAccessor.create(0, 256));
    }
}

package org.seedatlas.xaero.integration.biome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.carver.CaveWorldCarver;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.WorldType;

/** Offline 26.3 carver and native-floor checks; no world, chunk or client startup. */
public final class NetherCaveMaskTest {
    private static final long SEED = 2842062689604764280L;

    public static void main(String[] args) throws Exception {
        invalidAreas(); // Invalid inputs must fail even before Minecraft registries exist.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(SharedConstants.getCurrentVersion().name().equals("26.3"), "Requires Minecraft 26.3");
        identicalVanillaCarvers();
        knownColumnAndNativeFloor();
        samplingAndHaloConsistency();
        seedAndResultIsolation();
        concurrentReads();
        boundedCache();
        System.out.println("Nether cave masks: 7 test groups passed (vanilla 26.3 + native integration)");
        if (Arrays.asList(args).contains("--benchmark")) benchmark();
    }

    private static void invalidAreas() {
        for (int step : new int[]{Integer.MIN_VALUE, -1, 0, 3, 32, 1024, Integer.MAX_VALUE}) {
            rejects(() -> NetherCaveMask.area(SEED, 0, 0, step, 1, 1), "Unsupported step " + step);
        }
        for (int size : new int[]{Integer.MIN_VALUE, -1, 0, 263, Integer.MAX_VALUE}) {
            rejects(() -> NetherCaveMask.area(SEED, 0, 0, 1, size, 1), "Invalid width " + size);
            rejects(() -> NetherCaveMask.area(SEED, 0, 0, 1, 1, size), "Invalid height " + size);
        }
        rejects(() -> NetherCaveMask.area(SEED, Integer.MAX_VALUE, 0, 2, 1, 1), "X extent overflow");
        rejects(() -> NetherCaveMask.area(SEED, 0, Integer.MAX_VALUE, 4, 1, 1), "Z extent overflow");
        rejects(() -> NetherCaveMask.area(SEED, Integer.MAX_VALUE - 15, 0, 16, 2, 1), "Last cell overflow");
    }

    private static void identicalVanillaCarvers() {
        var biomes = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        var expected = new ArrayList<Holder<WorldCarver>>();
        biomes.getOrThrow(Biomes.NETHER_WASTES).value().getGenerationSettings().getCarvers().forEach(expected::add);
        check(!expected.isEmpty() && expected.stream().allMatch(h -> h.value() instanceof CaveWorldCarver),
                "Built-in Nether uses actual CaveWorldCarver instances");
        for (var biome : List.of(Biomes.NETHER_WASTES, Biomes.SOUL_SAND_VALLEY, Biomes.CRIMSON_FOREST,
                Biomes.WARPED_FOREST, Biomes.BASALT_DELTAS)) {
            var actual = new ArrayList<Holder<WorldCarver>>();
            biomes.getOrThrow(biome).value().getGenerationSettings().getCarvers().forEach(actual::add);
            check(expected.equals(actual), "Same ordered carvers for " + biome);
        }
    }

    private static void knownColumnAndNativeFloor() {
        long[] mask = NetherCaveMask.area(SEED, -200, 14, 1, 1, 1);
        check(mask.length == 2, "Two words per sample");
        check(bit(mask, 102) && bit(mask, 103), "Reported cave contains carved air at Y 102/103");
        check(!bit(mask, 101), "Reported floor at Y 101 is not carved");
        try (var atlas = SeedAtlasNative.open(SEED, WorldType.NORMAL)) {
            if (!atlas.supportsNetherCarving()) {
                System.out.println("Native carving integration: blocked (bundled library lacks optional capability)");
                if ("1".equals(System.getenv("SAX_REQUIRE_HEIGHTS"))) {
                    throw new AssertionError("Fresh native library must support Nether carving");
                }
                return;
            }
            check(atlas.netherFloorArea(-200, 14, 127, 1, 1, 1)[0] == 62.0F, "Density-only regression fixture");
            check(atlas.netherFloorArea(-200, 14, 127, 1, 1, 1, mask)[0] == 101.0F,
                    "Actual Java mask corrects Full native floor from 62 to saved floor 101");
            check(atlas.netherFloorArea(-200, 14, 127, 1, 1, 1, new long[2])[0] == 62.0F,
                    "Empty geometry preserves density-only floor");
            System.out.println("Native carving integration: verified floor 62 -> 101 with actual Java mask");
        }
    }

    private static void samplingAndHaloConsistency() {
        int width = 11, height = 7;
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            for (int shift = 0; shift < 4; ++shift) {
                int originX = -217 + shift, originZ = -33 + shift;
                long[] whole = NetherCaveMask.area(SEED, originX, originZ, step, width, height);
                long[] halo = NetherCaveMask.area(SEED, originX - step, originZ - step, step, width + 2, height + 2);
                check(Arrays.equals(whole, NetherCaveMask.area(SEED, originX, originZ, step, width, height)),
                        "Deterministic negative origin, step " + step);
                for (int z = 0; z < height; ++z) {
                    for (int x = 0; x < width; ++x) {
                        int bx = originX + x * step, bz = originZ + z * step;
                        if (step >= 8) {
                            bx = Math.floorDiv(bx + step / 2 + 2, 4) * 4;
                            bz = Math.floorDiv(bz + step / 2 + 2, 4) * 4;
                        } else {
                            bx += (step - 1) / 2;
                            bz += (step - 1) / 2;
                        }
                        long[] block = NetherCaveMask.area(SEED, bx, bz, 1, 1, 1);
                        for (int word = 0; word < 2; ++word) {
                            int index = (z * width + x) * 2 + word;
                            check(whole[index] == block[word], "Native block/cell centre, step " + step);
                            check(whole[index] == halo[((z + 1) * (width + 2) + x + 1) * 2 + word],
                                    "Whole/halo consistency, step " + step);
                        }
                    }
                }
            }
        }
        long[] maximum = NetherCaveMask.area(SEED, -262, -262, 1, 262, 262);
        check(maximum.length == 262 * 262 * 2, "Maximum halo dimensions are supported");
        for (int i = 0; i < maximum.length; i += 2) {
            check((maximum[i] & 1L) == 0 && (maximum[i + 1] >>> 57) == 0,
                    "Carving stays within vanilla output bounds Y 1..120");
        }
    }

    private static void seedAndResultIsolation() {
        long[] expected = NetherCaveMask.area(SEED, -208, 0, 1, 16, 16);
        check(!Arrays.equals(expected, NetherCaveMask.area(SEED + 1, -208, 0, 1, 16, 16)),
                "Cache keys distinguish seeds");
        long[] modified = NetherCaveMask.area(SEED, -208, 0, 1, 16, 16);
        Arrays.fill(modified, -1L);
        check(Arrays.equals(expected, NetherCaveMask.area(SEED, -208, 0, 1, 16, 16)),
                "Callers cannot mutate cached chunk masks");
    }

    private static void concurrentReads() throws Exception {
        long[] expected = NetherCaveMask.area(SEED, -217, -33, 4, 11, 7);
        cache().clear();
        var jobs = new ArrayList<Callable<Void>>();
        for (int thread = 0; thread < 4; ++thread) jobs.add(() -> {
            for (int repeat = 0; repeat < 20; ++repeat) {
                check(Arrays.equals(expected, NetherCaveMask.area(SEED, -217, -33, 4, 11, 7)),
                        "Concurrent cold generation and repeated cached reads");
            }
            return null;
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(jobs)) result.get();
        }
    }

    private static void boundedCache() throws Exception {
        cache().clear();
        long[] expected = NetherCaveMask.area(SEED, -200, 14, 1, 1, 1);
        for (int chunk = 0; chunk < 513; ++chunk) NetherCaveMask.area(SEED, chunk * 16, 0, 1, 1, 1);
        check(cache().size() == 512, "Cache remains bounded at 512 chunk masks");
        check(Arrays.equals(expected, NetherCaveMask.area(SEED, -200, 14, 1, 1, 1)),
                "Evicted masks regenerate identically");
        check(cache().size() == 512, "Regeneration preserves the cache bound");
    }

    private static void benchmark() throws Exception {
        System.out.printf("Mask benchmark: seed=%d origin=(-256,-256) size=256x256 Java=%s arch=%s cache=512%n",
                SEED, System.getProperty("java.version"), System.getProperty("os.arch"));
        for (int step : new int[]{1, 4, 16}) {
            cache().clear();
            long start = System.nanoTime();
            NetherCaveMask.area(SEED, -256, -256, step, 256, 256);
            double coldMs = (System.nanoTime() - start) / 1e6;
            start = System.nanoTime();
            NetherCaveMask.area(SEED, -256, -256, step, 256, 256);
            double repeatMs = (System.nanoTime() - start) / 1e6;
            System.out.printf(Locale.ROOT, "step=%d cold-cache=%.1fms repeated=%.1fms retained-chunks=%d%n",
                    step, coldMs, repeatMs, cache().size());
            if (step == 4 && Math.max(coldMs, repeatMs) * 16 > 10_000) {
                System.out.println("step=16 not_run: 16x step-4 chunk footprint projects over 10s; bounded benchmark stop");
                break;
            }
        }
    }

    private static Map<?, ?> cache() throws Exception {
        var field = NetherCaveMask.class.getDeclaredField("CACHE");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static boolean bit(long[] mask, int y) {
        return (mask[y >>> 6] & (1L << (y & 63))) != 0;
    }

    private static void rejects(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

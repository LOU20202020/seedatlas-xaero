import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.seedatlas.xaero.nativeapi.BiomeRegion;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.WorldType;

/** Manual FFM throughput benchmark; compile against build/classes/java/client. */
public final class BiomeAreaFfmBenchmark {
    private static final int SIDE = 256;
    private static final int SAMPLES = SIDE * SIDE;
    private static final int[] LOD_STEPS = {1, 4, 16, 64, 256, 512, 1024};

    public static void main(String[] args) throws Exception {
        try (SeedAtlasNative atlas = SeedAtlasNative.open(8371904829L, WorldType.NORMAL)) {
            for (int step : LOD_STEPS) {
                atlas.biomeArea(Dimension.OVERWORLD, alignedOrigin(step),
                    alignedOrigin(step), 255, step, SIDE, SIDE);
                double[] timings = new double[11];
                long digest = 0;
                for (int round = 0; round < timings.length; round++) {
                    long started = System.nanoTime();
                    BiomeRegion region = atlas.biomeArea(Dimension.OVERWORLD,
                        alignedOrigin(step), alignedOrigin(step), 255,
                        step, SIDE, SIDE);
                    timings[round] = (System.nanoTime() - started) / 1_000_000.0;
                    digest ^= region.argbAtSample(round, round);
                }
                Arrays.sort(timings);
                double median = timings[timings.length / 2];
                System.out.printf(
                    "FFM 256x256 1:%d median %.3f ms, %.3f Msample/s (%x)%n",
                    step, median, SAMPLES / median / 1000.0, digest);
            }

            benchmarkSingleSize(atlas, 1024, 128);

            benchmarkParallel(atlas, 4);
            benchmarkParallel(atlas, 8);
        }
    }

    private static void benchmarkSingleSize(SeedAtlasNative atlas, int step,
                                            int side) {
        int samples = side * side;
        atlas.biomeArea(Dimension.OVERWORLD, alignedOrigin(step),
            alignedOrigin(step), 255, step, side, side);
        double[] timings = new double[11];
        long digest = 0;
        for (int round = 0; round < timings.length; round++) {
            long started = System.nanoTime();
            BiomeRegion region = atlas.biomeArea(Dimension.OVERWORLD,
                alignedOrigin(step), alignedOrigin(step), 255,
                step, side, side);
            timings[round] = (System.nanoTime() - started) / 1_000_000.0;
            digest ^= region.argbAtSample(round, round);
        }
        Arrays.sort(timings);
        double median = timings[timings.length / 2];
        System.out.printf(
            "FFM %dx%d 1:%d median %.3f ms, %.3f Msample/s (%x)%n",
            side, side, step, median, samples / median / 1000.0, digest);
    }

    private static void benchmarkParallel(SeedAtlasNative atlas, int workers)
            throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            runParallelRound(atlas, executor, workers);
            double[] timings = new double[7];
            for (int round = 0; round < timings.length; round++) {
                timings[round] = runParallelRound(atlas, executor, workers);
            }
            Arrays.sort(timings);
            double median = timings[timings.length / 2];
            double throughput = (double) workers * SAMPLES / median / 1000.0;
            System.out.printf(
                "FFM %d parallel 256x256 step-1 tiles: median %.3f ms batch, "
                    + "%.3f Msample/s%n",
                workers, median, throughput);
        }
    }

    private static double runParallelRound(SeedAtlasNative atlas,
                                            ExecutorService executor,
                                            int workers) throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>(workers);
        for (int index = 0; index < workers; index++) {
            int tileX = index;
            calls.add(() -> atlas.biomeArea(Dimension.OVERWORLD,
                -65536 + tileX * SIDE, -65536, 255,
                1, SIDE, SIDE).argbAtSample(tileX, 0));
        }
        long started = System.nanoTime();
        List<Future<Integer>> futures = executor.invokeAll(calls);
        for (Future<Integer> future : futures) {
            future.get();
        }
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static int alignedOrigin(int step) {
        return -65536 / step * step;
    }
}

package org.seedatlas.xaero.integration.biome;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;

/** Deterministic unlimited-zoom bounds checks; no Minecraft window or native work. */
public final class BiomeViewportTest {
    public static void run() {
        SeedAtlasBiomeOverlayRenderer renderer = SeedAtlasBiomeOverlayRenderer.INSTANCE;
        renderer.setMapActive(true);
        for (int category : new int[]{0, 1, 2}) {
            check(SeedAtlasBiomeOverlayRenderer.tilePriority(category, false)
                    < SeedAtlasBiomeOverlayRenderer.tilePriority(1, true),
                "Coverage previews run before expensive density refinement");
        }
        check(SeedAtlasBiomeOverlayRenderer.tilePriority(1, true)
                < SeedAtlasBiomeOverlayRenderer.tilePriority(3, false), "Visible refinement precedes background work");
        check(SeedAtlasBiomeOverlayRenderer.acceptUpload(false, false, false), "Preview fills an empty tile");
        check(SeedAtlasBiomeOverlayRenderer.acceptUpload(true, false, true), "Detail replaces preview without a coverage gap");
        check(!SeedAtlasBiomeOverlayRenderer.acceptUpload(true, true, false), "Late preview cannot downgrade detail");
        check(!SeedAtlasBiomeOverlayRenderer.acceptUpload(true, true, true), "Duplicate detail is not reuploaded");
        check(!SeedAtlasBiomeOverlayRenderer.acceptUpload(true, false, false), "Duplicate preview is not reuploaded");
        check(SeedAtlasBiomeOverlayRenderer.shouldRefine(true, false, 1, false), "Visible preview refines after coverage is ready");
        check(!SeedAtlasBiomeOverlayRenderer.shouldRefine(true, false, 1, true), "New coverage delays detail even within the same LOD");
        check(!SeedAtlasBiomeOverlayRenderer.shouldRefine(false, false, 1, false), "Missing tile gets coverage before refinement");
        check(!SeedAtlasBiomeOverlayRenderer.shouldRefine(true, true, 1, false), "Completed detail is reused");
        for (int category : new int[]{0, 2, 3}) {
            check(!SeedAtlasBiomeOverlayRenderer.shouldRefine(true, false, category, false), "Parents/overscan/background stay cheap previews");
        }
        for (Dimension dimension : Dimension.values()) for (int step : new int[]{1, 4, 16, 64, 1024}) {
            int deepest = switch (dimension) { case OVERWORLD -> 4; case NETHER -> 16; case END -> 8; };
            check(SeedAtlasBiomeOverlayRenderer.needsSurfaceRefinement(dimension, true, false, step) == (step <= deepest),
                "Terrain is refined after coverage wherever it costs more than biomes: " + dimension + " step " + step);
            check(!SeedAtlasBiomeOverlayRenderer.needsSurfaceRefinement(dimension, false, false, step),
                "Terrain-off never schedules density refinement");
            check(!SeedAtlasBiomeOverlayRenderer.needsSurfaceRefinement(dimension, true, true, step),
                "Biome focus never schedules density refinement");
        }
        check(SeedAtlasBiomeOverlayRenderer.netherSurfaceY(64, Integer.MIN_VALUE) == 127,
            "Xaero Full cave mode scans from below the roof instead of the biome Y setting");
        check(SeedAtlasBiomeOverlayRenderer.netherSurfaceY(80, 5) == 80,
            "Non-Full cave modes retain the configured Nether Y");
        check(SeedAtlasBiomeOverlayRenderer.netherSurfaceY(64, Integer.MAX_VALUE) == 64,
            "Surface mode retains the configured Nether Y");
        check(SeedAtlasBiomeOverlayRenderer.coarseSampleStep(16, 0.04) == 16, "Normal zoom is unchanged");
        check(SeedAtlasBiomeOverlayRenderer.coarseSampleStep(64, 0.02) == 64, "Resolved coarse samples stay unchanged");
        check(SeedAtlasBiomeOverlayRenderer.coarseSampleStep(64, 0.01) == 256, "Subpixel coarse samples use a coarser LOD");
        check(SeedAtlasBiomeOverlayRenderer.coarseSampleStep(256, 0.003) == 1024, "Distant subpixel samples use the native limit");
        check(SeedAtlasBiomeOverlayRenderer.coarseSampleStep(1024, 1e-9) == 1024, "Native limit and crash protection remain");
        // Same seed-independent viewport workload at origin 0, scale .01, 1920x1080.
        long before = samples(64, 256, .01), after = samples(256, 128, .01);
        check(after < before / 4, "Coarse LOD cuts native samples by more than four times");
        System.out.println("Coarse viewport samples at scale .01: " + before + " -> " + after);
        check(view(renderer, 0, 0, 1.0 / 512, 1920, 1080, 1024, 256), "Ordinary coarse zoom is supported");
        check(view(renderer, 0, 0, 1.0 / 4096, 1920, 1080, 1024, 256), "Intermediate zoom remains supported");
        check(!view(renderer, 0, 0, 1.0 / 65536, 1920, 1080, 1024, 256), "Reject 131859 overscan tiles before enumeration");
        check(!view(renderer, 0, 0, 1.0 / 1048576, 1920, 1080, 1024, 256), "Reject 33213609 overscan tiles before enumeration");
        check(!view(renderer, 0, 0, Double.MIN_VALUE, 1920, 1080, 1024, 256), "Division overflowing to infinity is rejected");
        check(!view(renderer, Double.NaN, 0, 1, 1920, 1080, 1, 256), "Invalid cameras cannot wrap into tile coordinates");
        check(!view(renderer, Double.MAX_VALUE, 0, 1, 1920, 1080, 1, 256), "Huge finite cameras cannot wrap into tile coordinates");
        check(!view(renderer, Integer.MAX_VALUE, 0, 1, 1920, 1080, 1, 256), "Positive coordinate overflow is rejected");
        check(!view(renderer, Integer.MIN_VALUE, 0, 1, 1920, 1080, 1, 256), "Negative coordinate overflow is rejected");
        check(!view(renderer, 0, Integer.MAX_VALUE, 1, 1920, 1080, 1, 256), "Z coordinate overflow is rejected");
        check(view(renderer, -30_000_000, 30_000_000, 1.0 / 512, 1920, 1080, 1024, 256), "World-border panning stays supported");
        check(renderer.prepareView(0, 0, 14.5 * 262144, 14.5 * 262144, 262144), "Exactly 1024 overscan tiles are accepted");
        check(!renderer.prepareView(0, 0, 15.5 * 262144, 15.5 * 262144, 262144), "More than 1024 overscan tiles are rejected");

        // Every existing LOD band stays supported at 1080p and 4K, including negative panning.
        double[] scales = {4, 1, 0.5001, 0.5, 0.1251, 0.125, 0.03126, 0.03125, 0.00782, 0.0078125, 0.00196, 0.001953125, 1.0 / 2048};
        for (double scale : scales) {
            int step = scale > 0.5 ? 1 : scale > 0.125 ? 4 : scale > 0.03125 ? 16
                : scale > 0.0078125 ? 64 : scale > 0.001953125 ? 256 : 1024;
            int size = step < 4 || step * scale < 1 ? 256 : 128;
            for (int width : new int[]{1920, 3840}) {
                check(view(renderer, -12345, 67890, scale, width, width * 9 / 16, step, size),
                    "Existing LOD band remains supported at scale " + scale + " width " + width);
            }
        }

        // An over-budget transition clears old slices and work without disabling the map.
        try {
            // Reproduce the old render-thread exception directly, without enumerating millions of requests.
            var tileKey = renderer.getClass().getDeclaredMethod("tileKey", long.class, boolean.class, GameVersion.class,
                Dimension.class, ResourceKey.class, int.class, int.class, int.class, int.class, int.class);
            tileKey.setAccessible(true);
            try {
                tileKey.invoke(null, 1L, false, GameVersion.NEWEST, Dimension.OVERWORLD, null, 64, 1024, 256, -15360, 0);
                throw new AssertionError("Expected the old integer-coordinate failure");
            } catch (InvocationTargetException expected) {
                check(expected.getCause() instanceof IllegalArgumentException
                    && expected.getCause().getMessage().contains("outside integer world coordinates"),
                    "Reproduced the old extreme-zoom coordinate exception");
            }
            check(!view(renderer, 0, 0, 1.0 / 4194304, 1920, 1080, 1024, 256), "Reject the crashing grid before tileKey");

            Field contextField = field("context", renderer.getClass().getSuperclass());
            Object context = contextField.get(renderer);
            Field slices = field("slices", context.getClass());
            slices.set(context, List.of(new Object()));
            field("viewportDesired", renderer.getClass()).set(renderer, Set.of(new Object()));
            field("backgroundDesired", renderer.getClass()).set(renderer, Set.of(new Object()));
            @SuppressWarnings("unchecked")
            Set<Object> readyKeys = (Set<Object>)field("readyKeys", renderer.getClass()).get(renderer);
            readyKeys.add(new Object());
            Class<?> viewClass = Arrays.stream(renderer.getClass().getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("ViewRequest")).findFirst().orElseThrow();
            var viewConstructor = viewClass.getDeclaredConstructors()[0];
            viewConstructor.setAccessible(true);
            field("currentView", renderer.getClass()).set(renderer, viewConstructor.newInstance(
                1L, false, GameVersion.NEWEST, Dimension.OVERWORLD, null, 64, 1024, 256,
                0, 1, 0, 1, 0.0, 0.0, 1.0, 1920, 1080));
            check(!view(renderer, 0, 0, 1e-9, 1920, 1080, 1024, 256), "Extreme view is suspended");
            check(((List<?>)slices.get(context)).isEmpty(), "No stale overlay remains visible");
            for (String name : List.of("viewportDesired", "backgroundDesired", "readyKeys")) {
                check(((Set<?>)field(name, renderer.getClass()).get(renderer)).isEmpty(), name + " cleared");
            }
            check(field("currentView", renderer.getClass()).get(renderer) == null, "Old grid cannot reach buildSlices");
            check(field("mapActive", renderer.getClass()).getBoolean(renderer), "Xaero map stays active");
            check(view(renderer, 0, 0, 1, 1920, 1080, 1, 256), "Zooming back in is accepted without reopening the map");
            for (double invalidScale : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
                slices.set(context, List.of(new Object()));
                renderer.observeView(null, 0, 0, invalidScale, 1920, 1080);
                check(((List<?>)slices.get(context)).isEmpty(), "Invalid scale clears stale slices: " + invalidScale);
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        } finally {
            renderer.setMapActive(false);
        }
        System.out.println("Biome viewport unlimited-zoom bounds and recovery tests passed");
    }

    private static boolean view(SeedAtlasBiomeOverlayRenderer renderer, double x, double z,
                                double scale, int width, int height, int step, int size) {
        return renderer.prepareView(x, z, width / scale / 2, height / scale / 2, step * size);
    }

    private static long samples(int step, int size, double scale) {
        double world = step * size;
        long columns = (long)Math.floor(1920 / scale / 2 / world) - (long)Math.floor(-1920 / scale / 2 / world) + 3;
        long rows = (long)Math.floor(1080 / scale / 2 / world) - (long)Math.floor(-1080 / scale / 2 / world) + 3;
        return columns * rows * size * size;
    }

    private static Field field(String name, Class<?> type) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

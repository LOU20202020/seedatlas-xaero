package org.seedatlas.xaero.integration.marker;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.MarkerType;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.nativeapi.BiomeRegion;
import org.seedatlas.xaero.nativeapi.BlockBox;
import org.seedatlas.xaero.nativeapi.Dimension;

/** Blocking nearest-structure/biome search around a position; call off the render thread. */
public final class NearestFinder {
    public record Result(int x, int z, int distance) { }

    private static final int[] STRUCTURE_RADII = {1024, 4096, 16384};
    // {sampleStep, radius}: each pass is 1024x1024 samples, within the native 4M-sample limit.
    // Small, cheap passes first: most searches finish in the first one or two.
    private static final int[][] BIOME_PASSES = {{8, 1024}, {16, 4096}, {32, 16384}, {64, 32768}};

    private NearestFinder() { }

    public static boolean appliesTo(MarkerType type, ResourceKey<Level> level) {
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        return dimension != null && SeedAtlasStructureMarkerSource.belongsToDimension(type, dimension);
    }

    public static Result structure(MarkerType type, ResourceKey<Level> level, int px, int pz, boolean skipCompleted) {
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        if (dimension == null) return null;
        for (int i = 0; i < STRUCTURE_RADII.length; i++) {
            int r = STRUCTURE_RADII[i];
            BlockBox box = new BlockBox(px - r, pz - r, px + r, pz + r);
            List<SeedAtlasStructureMarker> found = SeedAtlasXaeroIntegration.withSession(dimension,
                s -> SeedAtlasStructureMarkerSource.scanWithSession(s, level, dimension, box, type));
            Result best = null;
            if (found != null) {
                for (SeedAtlasStructureMarker m : found) {
                    if (skipCompleted && SeedAtlasStructureState.key(m) != null
                        && SeedAtlasStructureState.isCompleted(m)) continue;
                    int d = distance(m.x(), m.z(), px, pz);
                    if (best == null || d < best.distance) best = new Result(m.x(), m.z(), d);
                }
            }
            // A hit outside the inscribed circle may lose to an unscanned closer one.
            if (best != null && (best.distance <= r || i == STRUCTURE_RADII.length - 1)) return best;
        }
        return null;
    }

    public static Result biome(int biomeId, ResourceKey<Level> level, int px, int pz) {
        Dimension dimension = SeedAtlasXaeroIntegration.nativeDimension(level);
        if (dimension == null) return null;
        int y = SeedAtlasXaeroIntegration.yFor(level);
        for (int i = 0; i < BIOME_PASSES.length; i++) {
            int step = BIOME_PASSES[i][0];
            int r = BIOME_PASSES[i][1];
            int ox = Math.floorDiv(px - r, step) * step;
            int oz = Math.floorDiv(pz - r, step) * step;
            int size = 2 * r / step;
            BiomeRegion region = SeedAtlasXaeroIntegration.withSession(dimension,
                s -> s.biomeArea(dimension, ox, oz, y, step, size, size));
            if (region == null) return null;
            Result best = null;
            for (int sz = 0; sz < size; sz++) {
                for (int sx = 0; sx < size; sx++) {
                    if (region.biomeIdAtSample(sx, sz) != biomeId) continue;
                    int bx = ox + sx * step + step / 2;
                    int bz = oz + sz * step + step / 2;
                    int d = distance(bx, bz, px, pz);
                    if (best == null || d < best.distance) best = new Result(bx, bz, d);
                }
            }
            if (best != null && (best.distance <= r || i == BIOME_PASSES.length - 1)) return best;
        }
        return null;
    }

    private static int distance(int x, int z, int px, int pz) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(Math.hypot((double) x - px, (double) z - pz)));
    }
}

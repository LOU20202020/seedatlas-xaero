package org.seedatlas.xaero.biome;

import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.seedatlas.xaero.integration.StateRevisionTracker;
import org.seedatlas.xaero.integration.biome.BiomeHighlighter;
import org.seedatlas.xaero.integration.biome.BiomeTerrainStyler;
import org.seedatlas.xaero.integration.biome.BiomeViewportTest;
import org.seedatlas.xaero.integration.biome.EndTerrainStylerTest;

/** Verifies the Seed Atlas style highlight colours without opening a Minecraft client. */
public final class BiomeHighlightTest {
    public static void main(String[] args) {
        BiomeViewportTest.run();
        EndTerrainStylerTest.run();
        surfaceStyling();
        int[] argb = {0xFF3E6B3E, 0xFF2C4C6B, 0xFF808080};
        short[] ids = {1, 4, 1};

        BiomeHighlighter.apply(argb, ids, BiomeHighlighter.mask(true, Set.of(1)));
        check(argb[0] == 0xFF568356, "Highlighted biome keeps its colour and brightens by 24");
        check(argb[1] == 0xFF202020, "Unselected biome turns into the dark Seed Atlas grey");
        check(argb[2] == 0xFF989898, "Every highlighted sample is brightened");

        int[] untouched = {0xFF3E6B3E};
        BiomeHighlighter.apply(untouched, new short[]{1}, BiomeHighlighter.mask(false, Set.of(1)));
        check(untouched[0] == 0xFF3E6B3E, "Disabled highlighting keeps the plain biome colours");

        check(BiomeHighlighter.mask(true, Set.of()).length == 0, "Empty selection yields an empty mask");
        boolean[] mask = BiomeHighlighter.mask(true, Set.of(3, 200, 999, -5));
        check(mask.length == BiomeHighlighter.BIOME_ID_LIMIT, "Mask covers every biome id");
        check(mask[3] && mask[200] && !mask[2], "Mask marks exactly the selected ids");

        int[] alpha = {0x803E6B3E};
        BiomeHighlighter.apply(alpha, new short[]{1}, BiomeHighlighter.mask(true, Set.of(1)));
        check((alpha[0] & 0xFF000000) == 0x80000000, "Overlay alpha is preserved");

        // Reproduce selecting biomes in a paused settings screen: ticks must not consume
        // those revisions, and closing the screen must apply the latest selection once.
        var tracker = new StateRevisionTracker();
        var applied = new ArrayList<Long>();
        tracker.synchronize(1, false, applied::add);
        tracker.synchronize(2, true, applied::add);
        tracker.synchronize(3, true, applied::add);
        check(applied.equals(List.of(1L)), "Menus must not start tile work");
        tracker.synchronize(3, false, applied::add);
        tracker.synchronize(3, false, applied::add);
        check(applied.equals(List.of(1L, 3L)), "Resume must apply the newest pending selection exactly once");
        try {
            tracker.synchronize(4, false, revision -> { throw new IllegalStateException("retry"); });
        } catch (IllegalStateException expected) { }
        tracker.synchronize(4, false, applied::add);
        check(applied.equals(List.of(1L, 3L, 4L)), "Failed updates must not be acknowledged");

        int[] saturated = {0xFFFFFFF0, 0x406AB135};
        BiomeHighlighter.apply(saturated, new short[]{4, (short) 65535}, BiomeHighlighter.mask(true, Set.of(4)));
        check(saturated[0] == 0xFFFFFFFF, "Brightening saturates without wrapping");
        check((saturated[1] >>> 24) == 0x40 && ((saturated[1] >>> 16) & 255) == (saturated[1] & 255),
            "Unknown biome samples stay neutral and preserve alpha");
        // Terrain styler: deterministic, seam-free across tiles (negative coords), alpha/unknown preserved.
        int n = 24;
        short[] fid = new short[n * n];
        Arrays.fill(fid, (short) 4);
        int[] a = new int[n * n];
        Arrays.fill(a, 0xC03E6B3E);
        int[] b = a.clone();
        BiomeTerrainStyler.apply(a, fid, -20, -36, 2, n, 99L);
        BiomeTerrainStyler.apply(b, fid, -20, -36, 2, n, 99L);
        check(Arrays.equals(a, b), "Terrain styling is deterministic");
        int[] left = new int[n * n];
        Arrays.fill(left, 0xC03E6B3E);
        BiomeTerrainStyler.apply(left, fid, -20 - n * 2, -36, 2, n, 99L);
        int[] whole = new int[n * 2 * n * 2];
        short[] wid = new short[n * 2 * n * 2];
        Arrays.fill(wid, (short) 4);
        Arrays.fill(whole, 0xC03E6B3E);
        BiomeTerrainStyler.apply(whole, wid, -20 - n * 2, -36, 2, n * 2, 99L);
        boolean seam = true;
        for (int z = 6; z < n - 6; ++z) for (int x = 6; x < n - 6; ++x) seam &= left[z * n + x] == whole[z * n * 2 + x];
        check(seam, "Tile interiors match a larger tile at the same world position");
        boolean alphaKept = true;
        for (int v : a) alphaKept &= (v >>> 24) == 0xC0;
        check(alphaKept, "Terrain styling preserves alpha");
        int[] unknown = {0xFF123456};
        BiomeTerrainStyler.apply(unknown, new short[]{(short) 65535}, 0, 0, 1, 1, 1L);
        check(unknown[0] == 0xFF123456, "Unknown biome samples keep their colour");
        // Real heights: with light from the north-west, ground rising toward the south-east is bright.
        int hn = 8, hw = hn + 2;
        float[] ramp = new float[hw * hw];
        for (int z = 0; z < hw; ++z) for (int x = 0; x < hw; ++x) ramp[z * hw + x] = 63 + (x + z) * 6;
        short[] hid = new short[hn * hn];
        Arrays.fill(hid, (short) 4);
        int[] lit = new int[hn * hn];
        Arrays.fill(lit, 0xC03E6B3E);
        BiomeTerrainStyler.apply(lit, hid, 0, 0, 4, hn, 5L, ramp);
        int[] shaded = new int[hn * hn];
        Arrays.fill(shaded, 0xC03E6B3E);
        float[] rampAway = new float[hw * hw];
        for (int i = 0; i < ramp.length; ++i) rampAway[i] = 2 * 63 - ramp[i];
        BiomeTerrainStyler.apply(shaded, hid, 0, 0, 4, hn, 5L, rampAway);
        long litSum = 0, shadedSum = 0;
        for (int i = 0; i < hn * hn; ++i) { litSum += (lit[i] >> 8) & 255; shadedSum += (shaded[i] >> 8) & 255; }
        check(litSum > shadedSum, "Slopes facing the north-west light are brighter than slopes facing away");
        check((lit[0] >>> 24) == 0xC0, "Height shading preserves alpha");
        int[] fallbackA = new int[hn * hn], fallbackB = new int[hn * hn];
        Arrays.fill(fallbackA, 0xC03E6B3E);
        Arrays.fill(fallbackB, 0xC03E6B3E);
        BiomeTerrainStyler.apply(fallbackA, hid, 0, 0, 4, hn, 5L);
        BiomeTerrainStyler.apply(fallbackB, hid, 0, 0, 4, hn, 5L, new float[3]);
        check(Arrays.equals(fallbackA, fallbackB), "A wrong-sized height grid falls back to the noise relief");
        System.out.println("Biome highlight colour and deferred revision tests passed");
        // End: island slopes are shaded, height 0 becomes void, bad grids are ignored.
        int en = 6, ew = en + 2;
        float[] island = new float[ew * ew];
        for (int z = 0; z < ew; ++z) for (int x = 0; x < ew; ++x) island[z * ew + x] = (x >= 1 && z >= 1 && x <= 6 && z <= 6) ? 40 + (x + z) * 2 : 0;
        short[] eid = new short[en * en];
        Arrays.fill(eid, (short) 9);
        int[] ecol = new int[en * en];
        Arrays.fill(ecol, 0xFFD9D99A);
        BiomeTerrainStyler.applyEnd(ecol, eid, 0, 0, 4, en, island);
        check(ecol[2 * en + 2] != 0xFFD9D99A, "End island samples are shaded");
        float[] voidGrid = new float[ew * ew];
        int[] vcol = new int[en * en];
        Arrays.fill(vcol, 0xFFD9D99A);
        BiomeTerrainStyler.applyEnd(vcol, eid, 0, 0, 4, en, voidGrid);
        check(vcol[0] == 0xFF1A1A2E && vcol[en * en - 1] == 0xFF1A1A2E, "End void replaces the biome colour");
        int[] badCol = new int[en * en];
        Arrays.fill(badCol, 0xFFD9D99A);
        BiomeTerrainStyler.applyEnd(badCol, eid, 0, 0, 4, en, new float[3]);
        BiomeTerrainStyler.applyEnd(badCol, eid, 0, 0, 4, en, null);
        check(badCol[0] == 0xFFD9D99A, "A missing or wrong-sized End grid leaves the tile flat");
        check((ecol[2 * en + 2] >>> 24) == 0xFF, "End shading preserves alpha");
        // Nether: seabed columns (<31) turn lava-orange, ground is slope shaded, bad grids are ignored.
        float[] ng = new float[ew * ew];
        for (int z = 0; z < ew; ++z) for (int x = 0; x < ew; ++x) ng[z * ew + x] = x < 4 ? 20 : 60 + x * 3;
        int[] ncol = new int[en * en];
        Arrays.fill(ncol, 0xFF601010);
        BiomeTerrainStyler.applyNether(ncol, eid, 0, 0, 4, en, ng);
        check(ncol[0] != 0xFF601010 && (ncol[0] & 0xFF0000) > 0xB00000, "Nether seabed becomes lava");
        check(ncol[5] != 0xFF601010 && (ncol[5] >>> 24) == 0xFF, "Nether ground is shaded and keeps alpha");
        int[] nbad = new int[en * en];
        Arrays.fill(nbad, 0xFF601010);
        BiomeTerrainStyler.applyNether(nbad, eid, 0, 0, 4, en, null);
        check(nbad[0] == 0xFF601010, "A missing Nether grid leaves the tile untouched");
    }

    private static void surfaceStyling() {
        int size = 4, width = size + 2;
        float[] ground = new float[width * width];
        Arrays.fill(ground, 64);
        int[] colors = new int[ground.length];
        Arrays.fill(colors, 0x6A7039);
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, 0xA000FFFF);
        short[] ids = new short[pixels.length];
        Arrays.fill(ids, (short) 6);
        BiomeTerrainStyler.applySurface(pixels, ids, 1, size, ground, ground, colors);
        for (int pixel : pixels) {
            check(pixel == pixels[0], "Flat swamp surfaces have no fake canopy or per-pixel grain");
            check((pixel >>> 24) == 0xA0 && (pixel & 255) < ((pixel >>> 8) & 255),
                "Surface colours replace cyan with muted swamp green while preserving alpha");
        }
        check(colors[width + 1] == 0x6A7039 && ground[width + 1] == 64,
            "Surface styling does not mutate cached height or colour grids");
        int[] waterColors = colors.clone();
        Arrays.fill(waterColors, 0x3F76E4);
        float[] shallow = ground.clone(), deep = ground.clone();
        Arrays.fill(shallow, 62);
        Arrays.fill(deep, 30);
        int[] shallowPixels = pixels.clone(), deepPixels = pixels.clone();
        BiomeTerrainStyler.applySurface(shallowPixels, ids, 1, size, ground, shallow, waterColors);
        BiomeTerrainStyler.applySurface(deepPixels, ids, 1, size, ground, deep, waterColors);
        check((deepPixels[0] & 255) < (shallowPixels[0] & 255), "Deeper real water columns shade darker");
        int[] preserved = pixels.clone();
        ids[0] = (short) 65535;
        BiomeTerrainStyler.applySurface(preserved, ids, 1, size, ground, ground, waterColors);
        check(preserved[0] == pixels[0], "Unknown biome samples stay unchanged");
        int[] bad = pixels.clone();
        BiomeTerrainStyler.applySurface(bad, ids, 1, size, ground, ground, new int[3]);
        check(Arrays.equals(bad, pixels), "Incomplete surface grids do not partially recolour tiles");
        float[] invalid = ground.clone();
        invalid[width + 2] = Float.NaN;
        BiomeTerrainStyler.applySurface(bad, ids, 1, size, invalid, ground, waterColors);
        check(bad[1] == pixels[1], "Invalid surface heights do not corrupt pixels");

        float[] ramp = ground.clone(), reverse = ground.clone();
        for (int z = 0; z < width; ++z) for (int x = 0; x < width; ++x) {
            ramp[z * width + x] = 70 + x + z;
            reverse[z * width + x] = 70 - x - z;
        }
        int[] lit = pixels.clone(), shade = pixels.clone();
        Arrays.fill(ids, (short) 4);
        BiomeTerrainStyler.applySurface(lit, ids, 1, size, ramp, ramp, colors);
        BiomeTerrainStyler.applySurface(shade, ids, 1, size, reverse, reverse, colors);
        check((lit[0] & 255) > (shade[0] & 255), "Real surface slopes face the north-west light");
        check(lit[0] == lit[lit.length - 1], "Halo gives tile-edge slopes the same shading as interiors");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

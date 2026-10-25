package org.seedatlas.xaero.integration.biome;

import java.util.Arrays;

/** Regression checks for biome-coloured halos around density-based End islands. */
public final class EndTerrainStylerTest {
    public static void run() {
        // All End biomes can overlap either ground or void. A biome boundary must
        // not leave yellow terraces in empty space or purple strips on solid ground.
        int[] biomeColors = {0xD9D99A, 0x4B4BAB, 0xC9C959, 0xB5B536, 0x7070CC};
        short[] biomeIds = {9, 40, 41, 42, 43};
        float[] flat = new float[9];
        Arrays.fill(flat, 64);
        int expected = 0;
        for (int i = 0; i < biomeIds.length; i++) {
            int[] land = {0x80000000 | biomeColors[i]};
            short[] ids = {biomeIds[i]};
            BiomeTerrainStyler.applyEnd(land, ids, -2168, -9116, 4, 1, flat);
            if (i == 0) expected = land[0];
            check(land[0] == expected, "Identical ground has the same colour in every End biome");
            check((land[0] >>> 24) == 0x80 && ids[0] == biomeIds[i], "Alpha and biome identity survive terrain styling");

            int[] voidPixel = {0x80000000 | biomeColors[i]};
            BiomeTerrainStyler.applyEnd(voidPixel, ids, -2168, -9116, 4, 1, new float[9]);
            check(voidPixel[0] == 0x801A1A2E, "Void removes the biome's land colour");
        }

        for (float absent : new float[]{0, Float.NaN, Float.POSITIVE_INFINITY}) {
            float[] edge = flat.clone();
            edge[0] = edge[8] = absent;
            int[] pixel = {0x804B4BAB};
            BiomeTerrainStyler.applyEnd(pixel, new short[]{40}, -2168, -9116, 4, 1, edge);
            check(pixel[0] == expected, "Absent neighbours do not cast a false slope onto flat island edges");
        }

        int[] unknown = {0x80123456};
        BiomeTerrainStyler.applyEnd(unknown, new short[]{(short) 65535}, 0, 0, 1, 1, flat);
        check(unknown[0] == 0x80123456, "Unknown samples keep their colour");
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            float[] heights = flat.clone();
            heights[4] = invalid;
            int[] pixel = {0x80123456};
            BiomeTerrainStyler.applyEnd(pixel, new short[]{9}, 0, 0, 1, 1, heights);
            check(pixel[0] == 0x80123456, "Invalid centre heights keep their colour");
        }

        // A sloping island crossing negative-coordinate tile boundaries must render
        // exactly like one larger tile, including the coastline and its halo.
        for (int step : new int[]{1, 2, 4, 8}) {
            int size = 12, half = size / 2, originX = -size * step, originZ = -2 * size * step;
            int[] whole = render(originX, originZ, step, size);
            for (int tz = 0; tz < 2; tz++) for (int tx = 0; tx < 2; tx++) {
                int[] tile = render(originX + tx * half * step, originZ + tz * half * step, step, half);
                for (int z = 0; z < half; z++) for (int x = 0; x < half; x++) {
                    check(tile[z * half + x] == whole[(tz * half + z) * size + tx * half + x],
                        "Island contours and shading are continuous across tiles at step " + step);
                }
            }
        }
        // Without heights the End biomes outline the islands in the refined palette.
        short[] previewIds = {42, 41, 40, 43, 9, 9};
        int[] preview = new int[6];
        java.util.Arrays.fill(preview, 0xFF7F3FB2);
        BiomeTerrainStyler.applyEndPreview(preview, previewIds, -16, 3000, 8, 1);
        check(preview[0] != 0xFF7F3FB2 && preview[1] == 0xFF7F3FB2, "A 1x1 tile only touches its own sample");
        int[] row = new int[6];
        java.util.Arrays.fill(row, 0xFF7F3FB2);
        BiomeTerrainStyler.applyEndPreview(row, previewIds, 2000, 2000, 16, 2);
        check(row[0] != 0xFF7F3FB2 && row[1] != 0xFF7F3FB2, "Highlands and midlands become end stone");
        check((row[2] & 0xFFFFFF) == 0x1A1A2E && (row[3] & 0xFFFFFF) == 0x1A1A2E, "Small islands and barrens become void");
        int[] centre = {0xFF7F3FB2, 0xFF7F3FB2, 0xFF7F3FB2, 0xFF7F3FB2};
        BiomeTerrainStyler.applyEndPreview(centre, new short[]{9, 9, 9, 9}, -64, -64, 64, 2);
        check((centre[0] & 0xFFFFFF) != 0x1A1A2E, "The main island shows at the centre");
        int[] outer = {0xFF7F3FB2};
        BiomeTerrainStyler.applyEndPreview(outer, new short[]{9}, 600, 600, 16, 1);
        check((outer[0] & 0xFFFFFF) == 0x1A1A2E, "The rest of the central biome is void");
        System.out.println("End terrain contour, palette and tile seam tests passed");
    }

    private static int[] render(int originX, int originZ, int step, int size) {
        int width = size + 2;
        float[] heights = new float[width * width];
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int wx = originX + (x - 1) * step, wz = originZ + (z - 1) * step;
            heights[z * width + x] = Math.floorMod(wx + wz, 23 * step) < 9 * step
                ? 0 : 55 + Math.floorMod(wx, 7) + Math.floorMod(wz, 11);
        }
        int[] pixels = new int[size * size];
        short[] ids = new short[pixels.length];
        Arrays.fill(pixels, 0xC07070CC);
        Arrays.fill(ids, (short) 43);
        BiomeTerrainStyler.applyEnd(pixels, ids, originX, originZ, step, size, heights);
        return pixels;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

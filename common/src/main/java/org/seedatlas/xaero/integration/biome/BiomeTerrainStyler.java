package org.seedatlas.xaero.integration.biome;

import java.util.Arrays;

/**
 * Terrain look for seed-only tiles: surface colours, hill shading and water depth tones.
 * Detailed Overworld tiles use vanilla base terrain; fast previews share their palette with
 * approximate native heights. Neither predicts trees or structures.
 */
public final class BiomeTerrainStyler {
    private static final int LIMIT = 256;
    private static final byte WATER = 1, DEEP = 2, LAND = 3;
    private static final int END_STONE = 0xD9D99A;
    private static final int END_VOID = 0x1A1A2E;
    // Engine biome ids of the End.
    private static final int THE_END = 9;
    private static final int SMALL_END_ISLANDS = 40;
    private static final int END_MIDLANDS = 41;
    private static final int END_HIGHLANDS = 42;
    private static final int END_BARRENS = 43;
    private static final long MAIN_ISLAND_RADIUS_SQUARED = 100L * 100L;
    /** Relief strength per biome id (0 = flat). */
    private static final int[] RELIEF = new int[LIMIT];
    private static final byte[] KIND = new byte[LIMIT];
    static {
        Arrays.fill(KIND, LAND);
        Arrays.fill(RELIEF, 14);
        for (int id : new int[]{0, 10, 44, 45, 46, 47}) KIND[id] = WATER;
        for (int id : new int[]{24, 48, 49, 50}) KIND[id] = DEEP;
        for (int id : new int[]{7, 11}) KIND[id] = WATER;
        for (int id : new int[]{2, 17, 130, 35, 36, 163, 164, 38, 39, 37, 165, 166, 167}) RELIEF[id] = 20;
        for (int id : new int[]{3, 20, 34, 131, 162, 177, 178, 179, 180, 181, 182, 12, 13, 140}) RELIEF[id] = 38;
        for (int id : new int[]{180, 181, 182, 3, 34, 131, 162}) RELIEF[id] = 52;
        for (int id : new int[]{4, 5, 18, 19, 21, 22, 23, 27, 28, 29, 30, 31, 32, 33, 132, 133, 155, 156, 157, 158, 160, 161, 168, 169, 185, 184, 186, 188, 6, 134}) {
            RELIEF[id] = 22;
        }
        for (int id : new int[]{16, 25, 26}) RELIEF[id] = 6; // beaches and shores stay flat
    }

    private BiomeTerrainStyler() { }

    static boolean waterBiome(int id) {
        return id >= 0 && id < LIMIT && KIND[id] != LAND;
    }

    /** Same surface palette/shading at every Overworld LOD; only preview geometry is approximate. */
    static void applyPreview(int[] argb, short[] ids, int step, int size, float[] floors, int[] colors) {
        if (size <= 0 || step <= 0 || argb.length < size * size || ids.length < size * size
            || colors.length < size * size) return;
        int hw = size + 2;
        boolean heights = floors != null && floors.length == hw * hw;
        for (int z = 0; z < size; ++z) for (int x = 0; x < size; ++x) {
            int i = z * size + x;
            if ((ids[i] & 0xffff) >= LIMIT || colors[i] < 0) continue;
            int delta = -8;
            if (heights) {
                if (waterBiome(ids[i] & 0xffff)) {
                    float depth = Math.max(0, 62 - floors[(z + 1) * hw + x + 1]);
                    delta -= Math.round(Math.min(24, depth * 0.65F));
                } else {
                    float slope = (floors[(z + 2) * hw + x + 2] - floors[z * hw + x]) / (2.0F * step);
                    if (Float.isFinite(slope)) delta += Math.round(Math.max(-28, Math.min(28, slope * 65)));
                }
            }
            argb[i] = shade((argb[i] & 0xff000000) | colors[i], delta);
        }
    }

    /** Vanilla-backed surface colours and heights, all with the same one-sample halo. */
    public static void applySurface(int[] argb, short[] ids, int step, int size,
                                    float[] heights, float[] floors, int[] colors) {
        int hw = size + 2;
        int count = size * size;
        if (size <= 0 || step <= 0 || heights == null || floors == null || colors == null
            || heights.length != hw * hw || floors.length != hw * hw || colors.length != hw * hw
            || argb.length < count || ids.length < count) return;
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                int h = (z + 1) * hw + x + 1;
                if ((ids[i] & 0xFFFF) >= LIMIT || colors[h] < 0
                    || !Float.isFinite(heights[h]) || !Float.isFinite(floors[h])) continue;
                float depth = heights[h] - floors[h];
                int delta = -8;
                if (depth > 0.5F) {
                    delta -= Math.round(Math.min(24.0F, depth * 0.65F));
                } else {
                    float slope = (heights[(z + 2) * hw + x + 2] - heights[z * hw + x]) / (2.0F * step);
                    if (Float.isFinite(slope)) delta += Math.round(Math.max(-28.0F, Math.min(28.0F, slope * 65.0F)));
                }
                argb[i] = shade((argb[i] & 0xFF000000) | (colors[h] & 0xFFFFFF), delta);
            }
        }
    }

    /**
     * End terrain: density heights determine both the island outline and its surface colour.
     * Biome boundaries do not describe solid ground, so they must not colour the terrain.
     * {@code heights} is the (size+2)^2 grid with a one-sample halo; 0 means void.
     * A missing or wrong-sized grid leaves the tile flat. Alpha and biome ids are never changed.
     */
    public static void applyEnd(int[] argb, short[] ids, int originX, int originZ, int step, int size, float[] heights) {
        int hw = size + 2;
        if (size <= 0 || step <= 0 || heights == null || heights.length != hw * hw
            || argb.length < size * size || ids.length < size * size) return;
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                float h = heights[(z + 1) * hw + x + 1];
                if ((ids[i] & 0xFFFF) >= LIMIT || !Float.isFinite(h)) continue;
                int alpha = argb[i] & 0xFF000000;
                if (h <= 0.0F) {
                    argb[i] = alpha | END_VOID;
                    continue;
                }
                // Void has no surface height: do not turn the drop to zero into a bright/dark rim.
                float nw = heights[z * hw + x];
                float se = heights[(z + 2) * hw + x + 2];
                if (!Float.isFinite(nw) || nw <= 0.0F) nw = h;
                if (!Float.isFinite(se) || se <= 0.0F) se = h;
                float slope = (se - nw) / (2.0F * step);
                int delta = Math.round(Math.max(-40.0F, Math.min(40.0F, slope * 120.0F)));
                delta += (int) (hash(originX / step + x, originZ / step + z, 0x5EED5L) % 7) - 3; // grain
                argb[i] = shade(alpha | END_STONE, delta);
            }
        }
    }

    /**
     * End look without density heights: distant views, and the moment before the heights arrive.
     * Highlands and midlands carry the islands, the other End biomes are mostly void, so the
     * preview already matches the refined palette instead of flashing raw biome colours.
     */
    public static void applyEndPreview(int[] argb, short[] ids, int originX, int originZ, int step, int size) {
        if (size <= 0 || step <= 0 || argb.length < size * size || ids.length < size * size) return;
        int centre = step / 2;
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                int id = ids[i] & 0xFFFF;
                int alpha = argb[i] & 0xFF000000;
                int grain = (int) (hash(originX / step + x, originZ / step + z, 0x5EED5L) % 7) - 3;
                long bx = (long) originX + (long) x * step + centre;
                long bz = (long) originZ + (long) z * step + centre;
                argb[i] = switch (id) {
                    case END_HIGHLANDS -> shade(alpha | END_STONE, grain - 4);
                    case END_MIDLANDS -> shade(alpha | END_STONE, grain - 16);
                    // The main island sits inside the central the_end biome, which is otherwise void.
                    case THE_END -> bx * bx + bz * bz < MAIN_ISLAND_RADIUS_SQUARED
                        ? shade(alpha | END_STONE, grain - 4) : alpha | END_VOID;
                    case SMALL_END_ISLANDS, END_BARRENS -> alpha | END_VOID;
                    default -> argb[i];
                };
            }
        }
    }

    /**
     * Nether cave-mode surface: heights are the predicted surface at the map layer (see
     * sax_nether_floor_area_sampled). Columns below the lava sea (y 31) become lava; other ground is
     * shaded by slope with the north-west light. Same {@code (size+2)^2} halo grid as the End.
     */
    public static void applyNether(int[] argb, short[] ids, int originX, int originZ, int step, int size, float[] heights) {
        int hw = size + 2;
        if (heights == null || heights.length != hw * hw || argb.length < size * size || ids.length < size * size) return;
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                if ((ids[i] & 0xFFFF) >= LIMIT) continue;
                float h = heights[(z + 1) * hw + x + 1];
                int grain = (int) (hash(originX / step + x, originZ / step + z, 0x4E7BL) % 5) - 2;
                if (h < 31.0F) {
                    argb[i] = shade((argb[i] & 0xFF000000) | 0xE0600A, grain * 2);
                    continue;
                }
                float slope = (heights[(z + 2) * hw + x + 2] - heights[z * hw + x]) / (2.0F * step);
                int delta = Math.round(Math.max(-45.0F, Math.min(45.0F, slope * 60.0F)));
                argb[i] = shade(argb[i], delta + grain - 10);
            }
        }
    }

    /** Cosmetic-noise relief; see {@link #apply(int[], short[], int, int, int, int, long, float[])}. */
    public static void apply(int[] argb, short[] ids, int originX, int originZ, int step, int size, long seed) {
        apply(argb, ids, originX, originZ, step, size, seed, null);
    }

    /**
     * Rewrites row-major ARGB samples in place; alpha and biome ids are never changed.
     * {@code heights} is an optional (size+2)^2 block-height grid with a one-sample halo
     * (its [1,1] entry is sample [0,0]); a missing or wrong-sized grid selects the noise fallback.
     */
    public static void apply(int[] argb, short[] ids, int originX, int originZ, int step, int size, long seed,
                             float[] heights) {
        int count = Math.min(Math.min(argb.length, ids.length), size * size);
        if (count < size * size) return;
        int hw = size + 2;
        boolean real = heights != null && heights.length == hw * hw;
        // The engine's biomes are 4x4-block cells, so edges look like stairs. Warp each sample's
        // lookup by smooth noise to make edges organic (about +-5 blocks; none once cells are tiny).
        float warp = 5.0F / step;
        int[] colour = new int[count];
        short[] id = new short[count];
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int sx = x, sz = z;
                if (warp >= 0.6F) {
                    int wx = originX + x * step, wz = originZ + z * step;
                    sx = Math.max(0, Math.min(size - 1, x + Math.round(noise(wx / 9.0F, wz / 9.0F, seed + 7) * warp)));
                    sz = Math.max(0, Math.min(size - 1, z + Math.round(noise(wx / 9.0F, wz / 9.0F, seed + 8) * warp)));
                }
                colour[z * size + x] = argb[sz * size + sx];
                id[z * size + x] = ids[sz * size + sx];
            }
        }
        int d = Math.max(2, step);
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                int i = z * size + x;
                int bid = id[i] & 0xFFFF;
                if (bid >= LIMIT || (ids[i] & 0xFFFF) >= LIMIT) {
                    continue; // unknown samples keep their original colour
                }
                int wx = originX + x * step;
                int wz = originZ + z * step;
                byte kind = KIND[bid];
                int delta;
                if (kind == LAND) {
                    if (real) {
                        // Light from the north-west: slopes facing it (ground rising toward the south-east) are bright.
                        float slope = (heights[(z + 2) * hw + x + 2] - heights[z * hw + x]) / (2.0F * step);
                        delta = Math.round(Math.max(-35.0F, Math.min(35.0F, slope * 90.0F)));
                        // Gentle elevation tint around sea level (about y=63).
                        delta += Math.round(Math.max(-12.0F, Math.min(12.0F, (heights[(z + 1) * hw + x + 1] - 63.0F) * 0.12F)));
                    } else {
                        int amp = RELIEF[bid];
                        // Light from the north-west: slope toward it is bright, away is dark.
                        float lit = height(wx - d, wz - d, seed) - height(wx + d, wz + d, seed);
                        delta = Math.round(lit * amp * 3.2F) + Math.round(height(wx, wz, seed) * amp * 0.5F);
                    }
                    delta += (int) (hash(wx / step, wz / step, seed) % 9) - 4; // per-block grain
                    delta -= 14; // Xaero's explored land reads darker and more muted
                } else {
                    if (real) {
                        // Water: darker where the estimated floor is further below sea level.
                        float below = Math.max(0.0F, Math.min(70.0F, 63.0F - heights[(z + 1) * hw + x + 1]));
                        delta = -16 - Math.round(below * 16.0F / 70.0F) - (kind == DEEP ? 10 : 0);
                    } else {
                        // Water: broad depth bands plus faint ripples.
                        float depth = height(wx * 2, wz * 2, seed ^ 0xABCDEFL);
                        delta = Math.round(depth * (kind == DEEP ? 14 : 9)) - (kind == DEEP ? 10 : 0);
                        delta -= 22;
                    }
                    delta += (int) (hash(wx / step, wz / step, seed) % 5) - 2;
                }
                // Biome-edge outline: lighter on the north-west side, darker on the south-east.
                if (x > 0 && z > 0 && (id[i - size - 1] & 0xFFFF) != bid) delta += 14;
                if (x + 1 < size && z + 1 < size && (id[i + size + 1] & 0xFFFF) != bid) delta -= 14;
                argb[i] = shade(colour[i], delta);
            }
        }
    }
    /** Smooth multi-octave noise in about [-1, 1]. */
    private static float height(int x, int z, long seed) {
        return 0.55F * noise(x / 40.0F, z / 40.0F, seed)
            + 0.30F * noise(x / 15.0F, z / 15.0F, seed + 1)
            + 0.15F * noise(x / 6.0F, z / 6.0F, seed + 2);
    }

    private static float noise(float x, float z, long seed) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        float fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        float a = lattice(x0, z0, seed), b = lattice(x0 + 1, z0, seed);
        float c = lattice(x0, z0 + 1, seed), e = lattice(x0 + 1, z0 + 1, seed);
        return (a + (b - a) * fx) + ((c + (e - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static float lattice(int x, int z, long seed) {
        return (hash(x, z, seed) % 2001) / 1000.0F - 1.0F;
    }

    private static int shade(int colour, int percent) {
        int r = channel(colour >> 16, percent);
        int g = channel(colour >> 8, percent);
        int b = channel(colour, percent);
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int value, int percent) {
        return Math.max(0, Math.min(255, (value & 0xFF) * (100 + percent) / 100));
    }

    static long hash(int x, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 32; h *= 0xD6E8FEB86659FD93L; h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }
}

package org.seedatlas.xaero.integration.biome;

import java.util.Set;

/**
 * Seed Atlas style biome focus.
 *
 * Selected biomes keep their own colour and are brightened slightly, while every other biome
 * is reduced to a dark neutral grey so the highlighted shapes stay readable on the map. The
 * values match the desktop Seed Atlas renderer, which uses the same Chunkbase-style focus.
 */
public final class BiomeHighlighter {
    /** Number of engine biome ids a mask covers. */
    public static final int BIOME_ID_LIMIT = 256;
    /** Brightening applied to highlighted biomes. */
    public static final int BRIGHTEN = 24;
    /** Base value of the grey every other biome is reduced to. */
    public static final int GREY_BASE = 18;
    /** Divisor of the luminance used for that grey. */
    public static final int GREY_DIVISOR = 5;

    private static final boolean[] EMPTY = new boolean[0];

    private BiomeHighlighter() { }

    /** Builds a lookup mask, or an empty array when highlighting is off or nothing is selected. */
    public static boolean[] mask(boolean enabled, Set<Integer> highlightedBiomes) {
        if (!enabled || highlightedBiomes.isEmpty()) {
            return EMPTY;
        }
        boolean[] mask = new boolean[BIOME_ID_LIMIT];
        for (int id : highlightedBiomes) {
            if (id >= 0 && id < BIOME_ID_LIMIT) {
                mask[id] = true;
            }
        }
        return mask;
    }

    /** Rewrites ARGB samples in place: highlighted biomes stay vivid, everything else turns grey. */
    public static void apply(int[] argb, short[] biomeIds, boolean[] highlighted) {
        if (highlighted.length == 0) {
            return;
        }
        int count = Math.min(argb.length, biomeIds.length);
        for (int i = 0; i < count; ++i) {
            int colour = argb[i];
            int red = (colour >> 16) & 0xFF;
            int green = (colour >> 8) & 0xFF;
            int blue = colour & 0xFF;
            int id = biomeIds[i] & 0xFFFF;
            if (id < highlighted.length && highlighted[id]) {
                red = Math.min(255, BRIGHTEN + red);
                green = Math.min(255, BRIGHTEN + green);
                blue = Math.min(255, BRIGHTEN + blue);
            } else {
                int grey = ((54 * red + 183 * green + 19 * blue) >> 8) / GREY_DIVISOR + GREY_BASE;
                grey = Math.min(255, grey);
                red = grey;
                green = grey;
                blue = grey;
            }
            argb[i] = (colour & 0xFF000000) | (red << 16) | (green << 8) | blue;
        }
    }
}

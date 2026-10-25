package org.seedatlas.xaero.nativeapi;

public record BlockPos(int x, int y, int z) {
    /** Used when the generation engine only knows an X/Z marker position. */
    public static final int UNKNOWN_Y = Integer.MIN_VALUE;
}

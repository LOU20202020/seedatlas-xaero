package org.seedatlas.xaero.nativeapi;

import java.util.Arrays;

/** Immutable 16x16 block-resolution biome plane for one chunk. */
public final class BiomeTile {
    public static final int SIZE = 16;
    public static final int SAMPLE_COUNT = SIZE * SIZE;

    private final int chunkX;
    private final int chunkZ;
    private final int y;
    private final int[] biomeIds;
    private final int[] argb;

    public BiomeTile(int chunkX, int chunkZ, int y, int[] biomeIds, int[] argb) {
        if (biomeIds.length != SAMPLE_COUNT || argb.length != SAMPLE_COUNT) {
            throw new IllegalArgumentException("Biome tile arrays must contain 256 entries");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.y = y;
        this.biomeIds = biomeIds.clone();
        this.argb = argb.clone();
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int y() {
        return y;
    }

    public int biomeId(int localX, int localZ) {
        return biomeIds[index(localX, localZ)];
    }

    public int argb(int localX, int localZ) {
        return argb[index(localX, localZ)];
    }

    public int[] biomeIds() {
        return biomeIds.clone();
    }

    public int[] argb() {
        return argb.clone();
    }

    private static int index(int localX, int localZ) {
        if (localX < 0 || localX >= SIZE || localZ < 0 || localZ >= SIZE) {
            throw new IndexOutOfBoundsException("Local biome coordinates must be in [0, 15]");
        }
        return localZ * SIZE + localX;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BiomeTile tile)) return false;
        return chunkX == tile.chunkX && chunkZ == tile.chunkZ && y == tile.y
            && Arrays.equals(biomeIds, tile.biomeIds) && Arrays.equals(argb, tile.argb);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(chunkX);
        result = 31 * result + Integer.hashCode(chunkZ);
        result = 31 * result + Integer.hashCode(y);
        result = 31 * result + Arrays.hashCode(biomeIds);
        return 31 * result + Arrays.hashCode(argb);
    }
}

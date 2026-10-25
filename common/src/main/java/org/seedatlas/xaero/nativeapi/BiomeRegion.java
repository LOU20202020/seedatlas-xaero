package org.seedatlas.xaero.nativeapi;

import java.util.Arrays;

/**
 * Immutable compact biome raster generated for a rectangular map area.
 * One array entry represents a {@code sampleStep x sampleStep} block cell.
 */
public final class BiomeRegion {
    private final int originBlockX;
    private final int originBlockZ;
    private final int y;
    private final int sampleStep;
    private final int sampleWidth;
    private final int sampleHeight;
    private final int[] biomeIds;
    private final int[] argbSamples;

    public BiomeRegion(int originBlockX, int originBlockZ, int y, int sampleStep,
                       int sampleWidth, int sampleHeight, int[] biomeIds,
                       int[] argbSamples) {
        this(originBlockX, originBlockZ, y, sampleStep, sampleWidth,
            sampleHeight, biomeIds, argbSamples, true);
    }

    private BiomeRegion(int originBlockX, int originBlockZ, int y, int sampleStep,
                        int sampleWidth, int sampleHeight, int[] biomeIds,
                        int[] argbSamples, boolean copyArrays) {
        if (sampleStep <= 0 || sampleWidth <= 0 || sampleHeight <= 0) {
            throw new IllegalArgumentException("Biome region dimensions must be positive");
        }
        int sampleCount = Math.multiplyExact(sampleWidth, sampleHeight);
        if (biomeIds.length != sampleCount || argbSamples.length != sampleCount) {
            throw new IllegalArgumentException(
                "Biome region arrays must match sampleWidth * sampleHeight");
        }
        this.originBlockX = originBlockX;
        this.originBlockZ = originBlockZ;
        this.y = y;
        this.sampleStep = sampleStep;
        this.sampleWidth = sampleWidth;
        this.sampleHeight = sampleHeight;
        this.biomeIds = copyArrays ? biomeIds.clone() : biomeIds;
        this.argbSamples = copyArrays ? argbSamples.clone() : argbSamples;
    }

    static BiomeRegion fromOwnedArrays(int originBlockX, int originBlockZ, int y,
                                       int sampleStep, int sampleWidth,
                                       int sampleHeight, int[] biomeIds,
                                       int[] argbSamples) {
        return new BiomeRegion(originBlockX, originBlockZ, y, sampleStep,
            sampleWidth, sampleHeight, biomeIds, argbSamples, false);
    }

    public int originBlockX() {
        return originBlockX;
    }

    public int originBlockZ() {
        return originBlockZ;
    }

    public int y() {
        return y;
    }

    public int sampleStep() {
        return sampleStep;
    }

    public int sampleWidth() {
        return sampleWidth;
    }

    public int sampleHeight() {
        return sampleHeight;
    }

    /** Returns one defensive copy suitable for bulk texture upload. */
    public int[] argbSamples() {
        return argbSamples.clone();
    }

    /** Returns one defensive copy suitable for bulk cache/hover processing. */
    public int[] biomeIds() {
        return biomeIds.clone();
    }

    /** Bulk-copies colors directly into a caller-owned texture array. */
    public void copyArgbSamplesTo(int[] target, int targetOffset) {
        if (targetOffset < 0 || targetOffset > target.length - argbSamples.length) {
            throw new IndexOutOfBoundsException("Target cannot hold all biome colors");
        }
        System.arraycopy(argbSamples, 0, target, targetOffset, argbSamples.length);
    }

    /** Bulk-copies biome ids without exposing the immutable backing array. */
    public void copyBiomeIdsTo(int[] target, int targetOffset) {
        if (targetOffset < 0 || targetOffset > target.length - biomeIds.length) {
            throw new IndexOutOfBoundsException("Target cannot hold all biome ids");
        }
        System.arraycopy(biomeIds, 0, target, targetOffset, biomeIds.length);
    }

    /**
     * Copies biome ids directly into a compact unsigned-short cache. Values
     * outside {@code [0, 65534]} become the {@code 0xffff} unknown sentinel.
     */
    public void copyBiomeIdsToUnsignedShorts(short[] target, int targetOffset) {
        if (targetOffset < 0 || targetOffset > target.length - biomeIds.length) {
            throw new IndexOutOfBoundsException("Target cannot hold all biome ids");
        }
        for (int i = 0; i < biomeIds.length; i++) {
            int id = biomeIds[i];
            target[targetOffset + i] = id < 0 || id > 0xfffe
                ? (short) 0xffff : (short) id;
        }
    }

    public int biomeIdAtSample(int sampleX, int sampleZ) {
        return biomeIds[sampleIndex(sampleX, sampleZ)];
    }

    public int argbAtSample(int sampleX, int sampleZ) {
        return argbSamples[sampleIndex(sampleX, sampleZ)];
    }

    public int biomeIdAtBlock(int blockX, int blockZ) {
        return biomeIds[blockIndex(blockX, blockZ)];
    }

    /** Expands one contained chunk; the native smoke test compares it with chunk sampling. */
    public BiomeTile biomeTile(int chunkX, int chunkZ) {
        long blockX = (long) chunkX * BiomeTile.SIZE;
        long blockZ = (long) chunkZ * BiomeTile.SIZE;
        requireContainedChunk(blockX, blockZ);
        int[] ids = new int[BiomeTile.SAMPLE_COUNT];
        int[] colors = new int[BiomeTile.SAMPLE_COUNT];
        int firstX = (int) blockX;
        int firstZ = (int) blockZ;
        for (int localZ = 0; localZ < BiomeTile.SIZE; localZ++) {
            for (int localX = 0; localX < BiomeTile.SIZE; localX++) {
                int outputIndex = localZ * BiomeTile.SIZE + localX;
                int sampleIndex = blockIndex(firstX + localX, firstZ + localZ);
                ids[outputIndex] = biomeIds[sampleIndex];
                colors[outputIndex] = argbSamples[sampleIndex];
            }
        }
        return new BiomeTile(chunkX, chunkZ, y, ids, colors);
    }

    /** Copies one contained chunk into an existing 256-entry texture buffer. */
    public void copyChunkArgb(int chunkX, int chunkZ, int[] target) {
        if (target.length < BiomeTile.SAMPLE_COUNT) {
            throw new IllegalArgumentException("Chunk color target needs at least 256 entries");
        }
        long blockX = (long) chunkX * BiomeTile.SIZE;
        long blockZ = (long) chunkZ * BiomeTile.SIZE;
        requireContainedChunk(blockX, blockZ);
        int firstX = (int) blockX;
        int firstZ = (int) blockZ;
        for (int localZ = 0; localZ < BiomeTile.SIZE; localZ++) {
            for (int localX = 0; localX < BiomeTile.SIZE; localX++) {
                target[localZ * BiomeTile.SIZE + localX] =
                    argbSamples[blockIndex(firstX + localX, firstZ + localZ)];
            }
        }
    }

    private int sampleIndex(int sampleX, int sampleZ) {
        if (sampleX < 0 || sampleX >= sampleWidth ||
            sampleZ < 0 || sampleZ >= sampleHeight) {
            throw new IndexOutOfBoundsException("Biome sample is outside this region");
        }
        return sampleZ * sampleWidth + sampleX;
    }

    private int blockIndex(int blockX, int blockZ) {
        long relativeX = (long) blockX - originBlockX;
        long relativeZ = (long) blockZ - originBlockZ;
        long coveredX = (long) sampleWidth * sampleStep;
        long coveredZ = (long) sampleHeight * sampleStep;
        if (relativeX < 0 || relativeX >= coveredX ||
            relativeZ < 0 || relativeZ >= coveredZ) {
            throw new IndexOutOfBoundsException("Block is outside this biome region");
        }
        int sampleX = (int) (relativeX / sampleStep);
        int sampleZ = (int) (relativeZ / sampleStep);
        return sampleZ * sampleWidth + sampleX;
    }

    private void requireContainedChunk(long blockX, long blockZ) {
        long maxX = blockX + BiomeTile.SIZE - 1L;
        long maxZ = blockZ + BiomeTile.SIZE - 1L;
        long regionMaxX = (long) originBlockX +
            (long) sampleWidth * sampleStep - 1L;
        long regionMaxZ = (long) originBlockZ +
            (long) sampleHeight * sampleStep - 1L;
        if (blockX < originBlockX || blockZ < originBlockZ ||
            maxX > regionMaxX || maxZ > regionMaxZ ||
            blockX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE ||
            blockZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            throw new IndexOutOfBoundsException("Chunk is outside this biome region");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BiomeRegion region)) return false;
        return originBlockX == region.originBlockX
            && originBlockZ == region.originBlockZ
            && y == region.y
            && sampleStep == region.sampleStep
            && sampleWidth == region.sampleWidth
            && sampleHeight == region.sampleHeight
            && Arrays.equals(biomeIds, region.biomeIds)
            && Arrays.equals(argbSamples, region.argbSamples);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(originBlockX);
        result = 31 * result + Integer.hashCode(originBlockZ);
        result = 31 * result + Integer.hashCode(y);
        result = 31 * result + Integer.hashCode(sampleStep);
        result = 31 * result + Integer.hashCode(sampleWidth);
        result = 31 * result + Integer.hashCode(sampleHeight);
        result = 31 * result + Arrays.hashCode(biomeIds);
        return 31 * result + Arrays.hashCode(argbSamples);
    }
}

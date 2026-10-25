package org.seedatlas.xaero.nativeapi;

/** Inclusive X/Z block-coordinate bounding box. */
public record BlockBox(int minX, int minZ, int maxX, int maxZ) {
    public BlockBox {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("BlockBox minimum must not exceed maximum");
        }
    }

    public boolean contains(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }
}

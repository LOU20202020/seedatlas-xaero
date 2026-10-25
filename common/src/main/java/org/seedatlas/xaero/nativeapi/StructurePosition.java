package org.seedatlas.xaero.nativeapi;

/**
 * A structure start or helper marker. Detail is type-specific (currently the
 * stronghold ring, fixed-gateway order, End island radius or camp variant)
 * and zero for ordinary starts.
 */
public record StructurePosition(
    StructureType type,
    int x,
    int y,
    int z,
    Viability viability,
    boolean approximate,
    int detail
) {
    public StructurePosition {
        if (type == null || viability == null) {
            throw new NullPointerException("type and viability are required");
        }
    }

    /** Checked camp biome, or -1 when no variant was determined. */
    public int campBiomeId() {
        return type == StructureType.ABANDONED_CAMP && detail >= 0 ? detail & 0xff : -1;
    }

    public boolean hasSpecialLoot() {
        return campBiomeId() >= 0 && (detail & 0x100) != 0;
    }

    public BlockPos position() {
        return new BlockPos(x, y, z);
    }
}

package org.seedatlas.xaero.nativeapi;

import java.util.HashMap;
import java.util.Map;

/** Structure and helper marker types exposed by the native engine. */
public enum StructureType {
    DESERT_PYRAMID(1, Dimension.OVERWORLD, true),
    JUNGLE_TEMPLE(2, Dimension.OVERWORLD, true),
    SWAMP_HUT(3, Dimension.OVERWORLD, true),
    IGLOO(4, Dimension.OVERWORLD, true),
    VILLAGE(5, Dimension.OVERWORLD, true),
    OCEAN_RUIN(6, Dimension.OVERWORLD, true),
    SHIPWRECK(7, Dimension.OVERWORLD, true),
    OCEAN_MONUMENT(8, Dimension.OVERWORLD, true),
    WOODLAND_MANSION(9, Dimension.OVERWORLD, true),
    PILLAGER_OUTPOST(10, Dimension.OVERWORLD, true),
    RUINED_PORTAL(11, Dimension.OVERWORLD, true),
    NETHER_RUINED_PORTAL(12, Dimension.NETHER, true),
    ANCIENT_CITY(13, Dimension.OVERWORLD, true),
    BURIED_TREASURE(14, Dimension.OVERWORLD, true),
    MINESHAFT(15, Dimension.OVERWORLD, true),
    DESERT_WELL(16, Dimension.OVERWORLD, true),
    AMETHYST_GEODE(17, Dimension.OVERWORLD, true),
    NETHER_FORTRESS(18, Dimension.NETHER, true),
    BASTION_REMNANT(19, Dimension.NETHER, true),
    END_CITY(20, Dimension.END, true),
    END_GATEWAY(21, Dimension.END, true),
    END_ISLAND(22, Dimension.END, true),
    TRAIL_RUINS(23, Dimension.OVERWORLD, true),
    TRIAL_CHAMBERS(24, Dimension.OVERWORLD, true),
    ABANDONED_CAMP(25, Dimension.OVERWORLD, true),

    STRONGHOLD(1001, Dimension.OVERWORLD, false),
    SPAWN(1002, Dimension.OVERWORLD, false),
    SLIME_CHUNK(1003, Dimension.OVERWORLD, false),
    ORE_VEIN_COPPER(1004, Dimension.OVERWORLD, true),
    ORE_VEIN_IRON(1005, Dimension.OVERWORLD, true),
    END_SHIP(1006, Dimension.END, true),
    /** Selects both copper and iron vein markers. Results use a concrete type. */
    ORE_VEIN(1007, Dimension.OVERWORLD, true);

    private static final Map<Integer, StructureType> BY_NATIVE_ID = new HashMap<>();

    static {
        for (StructureType value : values()) {
            BY_NATIVE_ID.put(value.nativeId, value);
        }
    }

    private final int nativeId;
    private final Dimension dimension;
    private final boolean structureScan;

    StructureType(int nativeId, Dimension dimension, boolean structureScan) {
        this.nativeId = nativeId;
        this.dimension = dimension;
        this.structureScan = structureScan;
    }

    int nativeId() {
        return nativeId;
    }

    public Dimension dimension() {
        return dimension;
    }

    boolean supportsStructureScan() {
        return structureScan;
    }

    static StructureType fromNativeId(int nativeId) {
        StructureType type = BY_NATIVE_ID.get(nativeId);
        if (type == null) {
            throw new NativeException("Unknown native structure type " + nativeId);
        }
        return type;
    }
}

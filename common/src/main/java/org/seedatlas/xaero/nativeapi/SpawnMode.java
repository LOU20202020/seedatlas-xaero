package org.seedatlas.xaero.nativeapi;

public enum SpawnMode {
    /** Fast climate-based estimate. */
    ESTIMATED(false),
    /** Slower biome search; grass-block placement still cannot be reproduced. */
    DETAILED_BIOME_SEARCH(true);

    private final boolean detailed;

    SpawnMode(boolean detailed) {
        this.detailed = detailed;
    }

    boolean detailed() {
        return detailed;
    }
}

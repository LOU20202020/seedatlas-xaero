package org.seedatlas.xaero.nativeapi;

/** Vanilla dimensions supported by the deterministic Seed Atlas engine. */
public enum Dimension {
    OVERWORLD(0),
    NETHER(-1),
    END(1);

    private final int nativeId;

    Dimension(int nativeId) {
        this.nativeId = nativeId;
    }

    int nativeId() {
        return nativeId;
    }
}

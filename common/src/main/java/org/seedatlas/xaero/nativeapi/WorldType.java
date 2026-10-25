package org.seedatlas.xaero.nativeapi;

/** Vanilla world-generation presets supported without server-side data. */
public enum WorldType {
    NORMAL(0),
    LARGE_BIOMES(1);

    private final int nativeFlags;

    WorldType(int nativeFlags) {
        this.nativeFlags = nativeFlags;
    }

    int nativeFlags() {
        return nativeFlags;
    }
}

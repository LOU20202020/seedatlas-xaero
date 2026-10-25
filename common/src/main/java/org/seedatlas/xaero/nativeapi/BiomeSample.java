package org.seedatlas.xaero.nativeapi;

/** A generated biome sample. Color is opaque ARGB (0xFFRRGGBB). */
public record BiomeSample(int id, String name, int argb) {
    public BiomeSample {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Biome name must not be blank");
        }
    }
}

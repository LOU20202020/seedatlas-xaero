package org.seedatlas.xaero.nativeapi;

import java.util.List;

/** A bounded native scan result. Split the bounding box when truncated. */
public record ScanResult(List<StructurePosition> positions, boolean truncated) {
    public ScanResult {
        positions = List.copyOf(positions);
    }
}

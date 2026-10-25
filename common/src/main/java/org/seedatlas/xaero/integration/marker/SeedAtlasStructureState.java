package org.seedatlas.xaero.integration.marker;

import java.util.OptionalLong;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.config.SeedAtlasConfig.StructureKey;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;

/** Persistent progress belongs to a world and seed, never to a cached marker object. */
final class SeedAtlasStructureState {
    static StructureKey key(SeedAtlasStructureMarker marker) {
        var dimension = SeedAtlasXaeroIntegration.nativeDimension(marker.dimension());
        var seed = dimension == null ? OptionalLong.empty() : SeedAtlasClientState.activeSeed(dimension);
        String world = SeedAtlasClientState.currentContextKey();
        if (seed.isEmpty() || world.isBlank()) return null;
        return new StructureKey(world, seed.getAsLong(), SeedAtlasClientState.config().largeBiomes(),
            marker.dimension().identifier().toString(), marker.type().id(), marker.x(), marker.z());
    }

    static boolean isCompleted(SeedAtlasStructureMarker marker) {
        return SeedAtlasClientState.config().isCompleted(key(marker));
    }
}

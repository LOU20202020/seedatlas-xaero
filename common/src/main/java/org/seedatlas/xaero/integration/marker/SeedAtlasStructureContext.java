package org.seedatlas.xaero.integration.marker;

import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.icon.MarkerSize;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;

final class SeedAtlasStructureContext {
    volatile List<SeedAtlasStructureMarker> markers = List.of();
    volatile ResourceKey<Level> dimension;
    /** Marker types the current zoom level may draw; the provider filters the snapshot with it. */
    volatile Set<String> visibleMarkerIds = Set.of();
    MultiTextureRenderTypeRenderer batchedIcons;
    float iconScale = 1.0F;

    void updateZoom(double mapScale, double screenSizeBasedScale, double viewportShortSide) {
        // Markers keep a readable minimum, grow with the zoom so zooming in brings the
        // icon closer, and stop at a share of the viewport instead of filling the map.
        iconScale = MarkerSize.iconScale(
            mapScale, screenSizeBasedScale, viewportShortSide, SeedAtlasClientState.markerSizeFactor());
    }
}

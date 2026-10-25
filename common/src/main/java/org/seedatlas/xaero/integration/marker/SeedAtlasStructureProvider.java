package org.seedatlas.xaero.integration.marker;

import java.util.List;
import java.util.Set;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;

/**
 * Iterates the current marker snapshot while skipping types that the zoom level hides.
 * The scan keeps those markers in memory, so zooming back in shows them without a rescan.
 */
final class SeedAtlasStructureProvider
    extends ElementRenderProvider<SeedAtlasStructureMarker, SeedAtlasStructureContext> {
    private List<SeedAtlasStructureMarker> markers = List.of();
    private Set<String> visible = Set.of();
    private int index;

    @Override
    public void begin(ElementRenderLocation location, SeedAtlasStructureContext context) {
        this.markers = context.markers;
        this.visible = context.visibleMarkerIds;
        this.index = 0;
    }

    @Override
    public boolean hasNext(ElementRenderLocation location, SeedAtlasStructureContext context) {
        while (this.index < this.markers.size()
            && !this.visible.contains(this.markers.get(this.index).type().id())) {
            this.index++;
        }
        return this.index < this.markers.size();
    }

    @Override
    public SeedAtlasStructureMarker getNext(
        ElementRenderLocation location, SeedAtlasStructureContext context
    ) {
        return this.markers.get(this.index++);
    }

    @Override
    public void end(ElementRenderLocation location, SeedAtlasStructureContext context) {
        this.markers = List.of();
        this.visible = Set.of();
        this.index = 0;
    }
}

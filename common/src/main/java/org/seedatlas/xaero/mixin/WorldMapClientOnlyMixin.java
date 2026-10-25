package org.seedatlas.xaero.mixin;

import org.seedatlas.xaero.integration.biome.SeedAtlasBiomeOverlayRenderer;
import org.seedatlas.xaero.integration.marker.SeedAtlasStructureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.WorldMap;
import xaero.map.WorldMapClientOnly;

/** Adds the Seed Atlas layers once Xaero's full-map handler exists. */
@Mixin(value = WorldMapClientOnly.class, remap = false)
abstract class WorldMapClientOnlyMixin {
    @Inject(method = "loadLaterClientRender", at = @At("RETURN"))
    private void seedAtlas$registerRenderers(CallbackInfo ci) {
        WorldMap.mapElementRenderHandler.add(SeedAtlasBiomeOverlayRenderer.INSTANCE);
        WorldMap.mapElementRenderHandler.add(SeedAtlasStructureRenderer.INSTANCE);
    }
}

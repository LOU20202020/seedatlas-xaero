package org.seedatlas.xaero.mixin;

import java.io.File;
import org.seedatlas.xaero.integration.ExploredEdgeCaches;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.BranchLeveledRegion;

/** Zoomed-out textures cached with dark seams are rebuilt once. */
@Mixin(value = BranchLeveledRegion.class, remap = false)
abstract class BranchLeveledRegionMixin {
    @Inject(method = "findCacheFile", at = @At("RETURN"))
    private void seedAtlas$rebuildSeamedTextures(MapSaveLoad mapSaveLoad, CallbackInfoReturnable<File> cir) {
        ExploredEdgeCaches.dropIfOutdated(cir.getReturnValue());
    }
}

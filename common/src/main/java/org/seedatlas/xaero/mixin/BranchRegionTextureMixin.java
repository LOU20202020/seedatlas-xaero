package org.seedatlas.xaero.mixin;

import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.texture.BranchRegionTexture;

/** Also handle zoom textures cached before transparent unknown quadrants. */
@Mixin(value = BranchRegionTexture.class, remap = false)
abstract class BranchRegionTextureMixin {
    @Inject(method = "readCacheData", at = @At("RETURN"))
    private void seedAtlas$restoreUnknownTransparency(CallbackInfo ci) {
        BranchRegionTexture texture = (BranchRegionTexture)(Object)this;
        ByteBuffer colors = texture.getDirectColorBuffer();
        if (colors == null || colors.limit() < 64 * 64 * 4) {
            return;
        }
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                int offset = (z * 64 + x) * 4;
                // Check Xaero's missing-height sentinel as well as the old
                // clear color, so legitimately black explored blocks survive.
                if (texture.getHeight(x, z) == 32767
                    && texture.getTopHeight(x, z) == 32767
                    && colors.get(offset) == 0 && colors.get(offset + 1) == 0
                    && colors.get(offset + 2) == 0) {
                    colors.put(offset + 3, (byte)0);
                }
            }
        }
    }
}

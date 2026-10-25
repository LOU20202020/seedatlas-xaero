package org.seedatlas.xaero.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector4f;
import org.seedatlas.xaero.integration.ExploredTerrainRenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.texture.BranchTextureRenderer;

/** Keep unknown quadrants transparent, and unknown texels out of the average, when Xaero builds zoomed-out textures. */
@Mixin(value = BranchTextureRenderer.class, remap = false)
abstract class BranchTextureRendererMixin {
    @Shadow
    private Vector4f black4f;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void seedAtlas$transparentUnknownTerrain(CallbackInfo ci) {
        // The map shader discards RGBA zero. Opaque black would hide the seed
        // background, even though this quadrant contains no explored terrain.
        this.black4f.set(0.0F, 0.0F, 0.0F, 0.0F);
    }

    @ModifyArg(method = "render", at = @At(
        value = "INVOKE",
        target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;getRenderer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;"
    ))
    private RenderType seedAtlas$exploredOnlyAverage(RenderType type) {
        return ExploredTerrainRenderTypes.replace(type);
    }
}

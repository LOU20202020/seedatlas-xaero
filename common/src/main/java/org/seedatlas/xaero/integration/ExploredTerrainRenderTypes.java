package org.seedatlas.xaero.integration;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import xaero.lib.client.graphics.XaeroRenderType;
import xaero.lib.client.graphics.shader.BuiltInCustomUniforms;
import xaero.lib.client.graphics.shader.LibShaders;
import xaero.map.WorldMap;
import xaero.map.graphics.CustomRenderTypes;

/**
 * Xaero's render types for explored terrain and its zoomed-out textures, with fragment shaders that
 * leave unexplored texels out of filtering. Xaero's own blend them in as black, which only shows once
 * the seed layer, not black, surrounds the explored terrain.
 */
public final class ExploredTerrainRenderTypes {
    private static final Supplier<GpuSampler> NEAREST = () -> RenderSystem.getDevice()
        .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.of(0.0));
    private static RenderType map;
    private static RenderType branch;

    private ExploredTerrainRenderTypes() {
    }

    /** Used in place of Xaero's type; any other type passes through. */
    public static RenderType replace(RenderType type) {
        if (type == CustomRenderTypes.MAP) return map();
        if (type == CustomRenderTypes.MAP_BRANCH) return branch();
        return type;
    }

    private static RenderType map() {
        if (map == null) {
            RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seedatlas_xaero", "pipeline/explored_map"))
                .withVertexShader(LibShaders.WORLD_MAP)
                .withFragmentShader(Identifier.fromNamespaceAndPath("seedatlas_xaero", "core/explored_map"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withBindGroupLayout(matrices())
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform(BuiltInCustomUniforms.BRIGHTNESS.name(), BuiltInCustomUniforms.BRIGHTNESS.type())
                    .withUniform(BuiltInCustomUniforms.WITH_LIGHT.name(), BuiltInCustomUniforms.WITH_LIGHT.type())
                    .build())
                // Xaero's map writes its colour and keeps the target's alpha.
                .withColorTargetState(new ColorTargetState(
                    new BlendFunction(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE)))
                .withCull(false)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .build();
            map = XaeroRenderType.createRenderType("seedatlas_explored_map",
                RenderSetup.builder(pipeline).withTexture("Sampler0", WorldMap.guiTextures, NEAREST));
        }
        return map;
    }

    private static RenderType branch() {
        if (branch == null) {
            RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seedatlas_xaero", "pipeline/explored_map_branch"))
                .withVertexShader(LibShaders.WORLD_MAP_BRANCH)
                .withFragmentShader(Identifier.fromNamespaceAndPath("seedatlas_xaero", "core/explored_map_branch"))
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withBindGroupLayout(matrices())
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false)
                .build();
            branch = XaeroRenderType.createRenderType("seedatlas_explored_map_branch",
                RenderSetup.builder(pipeline).withTexture("Sampler0", WorldMap.guiTextures, NEAREST));
        }
        return branch;
    }

    private static BindGroupLayout matrices() {
        return BindGroupLayout.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .build();
    }
}

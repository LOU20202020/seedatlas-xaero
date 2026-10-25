package org.seedatlas.xaero.integration.marker;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.integration.icon.CompletedBadge;
import org.seedatlas.xaero.integration.icon.StructureIcons;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/** Xaero map-element renderer backed by Seed Atlas' original structure icons. */
public final class SeedAtlasStructureRenderer extends ElementRenderer<
    SeedAtlasStructureMarker,
    SeedAtlasStructureContext,
    SeedAtlasStructureRenderer
> {
    public static final SeedAtlasStructureRenderer INSTANCE = new SeedAtlasStructureRenderer();

    private final SeedAtlasStructureMarkerSource source;

    private SeedAtlasStructureRenderer() {
        this(new SeedAtlasStructureContext(), new SeedAtlasStructureProvider(), new SeedAtlasStructureReader());
    }

    private SeedAtlasStructureRenderer(
        SeedAtlasStructureContext context,
        SeedAtlasStructureProvider provider,
        SeedAtlasStructureReader reader
    ) {
        super(context, provider, reader);
        this.source = new SeedAtlasStructureMarkerSource();
    }

    @Override
    public void preRender(
        ElementRenderInfo renderInfo,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider,
        boolean shadow
    ) {
        if (!SeedAtlasXaeroIntegration.isLayerActive()) {
            this.context.markers = List.of();
            this.context.batchedIcons = null;
            return;
        }
        this.context.dimension = renderInfo.mapDimension;
        Minecraft minecraft = Minecraft.getInstance();
        this.context.updateZoom(
            renderInfo.scale,
            renderInfo.screenSizeBasedScale,
            Math.min(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight())
        );
        // PNGs use straight alpha. Nearest sampling keeps the original pixel-art edges crisp.
        this.context.batchedIcons = rendererProvider.getRenderer(CustomRenderTypes.GUI_NEAREST);
        this.source.request(
            this.context,
            renderInfo.mapDimension,
            renderInfo.renderPos.x,
            renderInfo.renderPos.z,
            renderInfo.scale,
            minecraft.getWindow().getWidth(),
            minecraft.getWindow().getHeight()
        );
    }

    @Override
    public void postRender(
        ElementRenderInfo renderInfo,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider,
        boolean shadow
    ) {
        if (this.context.batchedIcons != null) {
            rendererProvider.draw(this.context.batchedIcons);
        }
    }

    @Override
    public void renderElementShadow(
        SeedAtlasStructureMarker marker,
        boolean hovered,
        float optionalScale,
        double partialX,
        double partialY,
        ElementRenderInfo renderInfo,
        MapElementGraphics graphics,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider
    ) {
    }

    @Override
    public boolean renderElement(
        SeedAtlasStructureMarker marker,
        boolean hovered,
        double optionalDepth,
        float optionalScale,
        double partialX,
        double partialY,
        ElementRenderInfo renderInfo,
        MapElementGraphics graphics,
        XaeroBufferProvider xaeroBufferProvider,
        MultiTextureRenderTypeRendererProvider rendererProvider
    ) {
        PoseStack pose = graphics.pose();
        pose.translate(partialX, partialY, optionalDepth);
        float iconScale = optionalScale * this.context.iconScale;
        pose.scale(iconScale, iconScale, 1.0F);
        var icon = StructureIcons.get(marker.textureId());
        var layout = icon.layout();
        boolean completed = SeedAtlasStructureState.isCompleted(marker);
        // Completed markers fade by the configured amount; the reader hides them at 0 %.
        float alpha = completed ? SeedAtlasClientState.config().completedOpacityFraction() : 1.0F;
        if (icon.block()) {
            // Block textures are full squares: a dark frame keeps them apart from the map below.
            int frame = Math.round(alpha * 0xC0) << 24 | 0x141414;
            graphics.fill(layout.left() - 1, layout.top() - 1, layout.right() + 1, layout.bottom() + 1, frame);
        }
        if (hovered) {
            // Outline only: keep the map visible through transparent icon pixels.
            int left = layout.left() - 1, top = layout.top() - 1;
            int right = layout.right() + 1, bottom = layout.bottom() + 1;
            int color = 0xBFFFFFFF;
            graphics.fill(left, top, right, top + 1, color);
            graphics.fill(left, bottom - 1, right, bottom, color);
            graphics.fill(left, top + 1, left + 1, bottom - 1, color);
            graphics.fill(right - 1, top + 1, right, bottom - 1, color);
        }
        MapRenderHelper.blitIntoMultiTextureRenderer(
            pose.last().pose(), this.context.batchedIcons,
            layout.left(), layout.top(), layout.u(), layout.v(),
            // Destination size first, then the measured motif: the blit samples exactly the
            // motif and the padding around it never shrinks the visible icon.
            layout.displayWidth(), layout.displayHeight(),
            layout.width(), layout.height(),
            1.0F, 1.0F, 1.0F, alpha, layout.textureWidth(), layout.textureHeight(),
            Minecraft.getInstance().getTextureManager().getTexture(icon.texture()).getTextureView()
        );
        if (completed) {
            // Badge on the lower right corner, drawn right after the icon so it stays on top.
            pose.translate(0.0F, 0.0F, 0.1F);
            MapRenderHelper.blitIntoMultiTextureRenderer(
                pose.last().pose(), this.context.batchedIcons,
                CompletedBadge.left(layout), CompletedBadge.top(layout), 0, 0,
                CompletedBadge.DISPLAY_SIZE, CompletedBadge.DISPLAY_SIZE,
                CompletedBadge.SOURCE_SIZE, CompletedBadge.SOURCE_SIZE,
                1.0F, 1.0F, 1.0F, alpha,
                CompletedBadge.SOURCE_SIZE, CompletedBadge.SOURCE_SIZE,
                Minecraft.getInstance().getTextureManager().getTexture(CompletedBadge.TEXTURE).getTextureView()
            );
        }
        return false;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return location == ElementRenderLocation.WORLD_MAP
            && !shadow
            && SeedAtlasXaeroIntegration.isLayerActive();
    }

    @Override
    public int getOrder() {
        return 175;
    }

    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }
}

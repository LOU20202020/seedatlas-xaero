package org.seedatlas.xaero.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import org.seedatlas.xaero.gui.BiomeHighlightScreen;
import org.seedatlas.xaero.gui.MarkerSettingsScreen;
import org.seedatlas.xaero.gui.NearestScreen;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.ExploredTerrainRenderTypes;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.seedatlas.xaero.integration.MapNavigator;
import org.seedatlas.xaero.integration.biome.SeedAtlasBiomeOverlayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.GuiMap;
import xaero.map.gui.TooltipButton;

/** Full-map-only controls and lifecycle gate for the seed layer. */
@Mixin(value = GuiMap.class, remap = false)
abstract class GuiMapMixin {
    /** Xaero's own bound with its "Unlimited zoom out" option: one pixel for 512 blocks. */
    @Unique
    private static final double SEED_ATLAS$MIN_SCALE = 0.001953125;

    @Shadow
    private double cameraX;
    @Shadow
    private double cameraZ;
    @Shadow
    private double scale;
    @Shadow
    private boolean shouldResetCameraPos;
    @Shadow
    private static boolean attachedCamera;
    @Shadow
    private Button settingsButton;

    @Shadow
    private void onSettingsButton(Button button) {
        throw new AssertionError();
    }

    @Unique
    private Button seedAtlas$layerButton;
    @Unique
    private Button seedAtlas$settingsButton;
    @Unique
    private Button seedAtlas$mapSettingsButton;
    @Unique
    private long seedAtlas$displayedRevision = Long.MIN_VALUE;
    @Unique
    private int seedAtlas$quickY = -1;

    // Draw before Xaero reserves both renderers in its shared provider.
    @Inject(method = "extractRenderState", at = @At(
        value = "INVOKE",
        target = "Lxaero/map/MapProcessor;getMultiTextureRenderTypeRenderers()Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;"
    ))
    private void seedAtlas$renderBiomeBackground(
        CallbackInfo ci,
        @Local(name = "matrixStack") PoseStack pose,
        @Local(name = "flooredCameraX") int flooredCameraX,
        @Local(name = "flooredCameraZ") int flooredCameraZ
    ) {
        GuiMap map = (GuiMap)(Object)this;
        var processor = map.getMapProcessor();
        SeedAtlasBiomeOverlayRenderer.INSTANCE.renderBackground(
            pose, processor.getMapWorld().getCurrentDimension().getDimId(),
            this.cameraX, this.cameraZ, this.scale, flooredCameraX, flooredCameraZ,
            processor.getMultiTextureRenderTypeRenderers(), false
        );
    }

    // Both terrain batches have been flushed and released. Use the same FBO and pose,
    // before grids, waypoints and structure markers, so explored blocks cannot hide focus.
    @Inject(method = "extractRenderState", at = @At(
        value = "INVOKE",
        target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
        ordinal = 1, shift = At.Shift.AFTER
    ))
    private void seedAtlas$renderBiomeFocus(
        CallbackInfo ci,
        @Local(name = "matrixStack") PoseStack pose,
        @Local(name = "flooredCameraX") int flooredCameraX,
        @Local(name = "flooredCameraZ") int flooredCameraZ
    ) {
        GuiMap map = (GuiMap)(Object)this;
        var processor = map.getMapProcessor();
        SeedAtlasBiomeOverlayRenderer.INSTANCE.renderBackground(
            pose, processor.getMapWorld().getCurrentDimension().getDimId(),
            this.cameraX, this.cameraZ, this.scale, flooredCameraX, flooredCameraZ,
            processor.getMultiTextureRenderTypeRenderers(), true
        );
    }

    // Unexplored texels must not darken explored terrain where the seed layer surrounds it.
    @ModifyArg(method = "extractRenderState", at = @At(
        value = "INVOKE",
        target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;getRenderer(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;"
    ))
    private RenderType seedAtlas$exploredTerrainType(RenderType type) {
        return ExploredTerrainRenderTypes.replace(type);
    }

    // The seed layer is made for overviews, so with it on the map zooms out as far as Xaero allows at all.
    @ModifyVariable(method = "applyZoomLimits", at = @At("LOAD"), name = "minScale")
    private double seedAtlas$zoomOutFurther(double minScale) {
        return SeedAtlasXaeroIntegration.isLayerConfigured() ? Math.min(minScale, SEED_ATLAS$MIN_SCALE) : minScale;
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void seedAtlas$addLayerControls(CallbackInfo ci) {
        GuiMap map = (GuiMap)(Object)this;
        SeedAtlasClientState.refreshContext(Minecraft.getInstance());
        SeedAtlasXaeroIntegration.setFullMapActive(true);

        // Xaero reserves x=0 in 20px rows along the left edge. Its own buttons
        // occupy h-40/-60/-100/-120; h-20 is free for SA.
        int x = 0;
        int layerY = Math.max(0, map.height - 20);
        this.seedAtlas$layerButton = new TooltipButton(x, layerY, 20, 20,
            compactLayerButtonText(), button -> {
            SeedAtlasClientState.toggleLayer();
            button.setMessage(compactLayerButtonText());
            SeedAtlasXaeroIntegration.synchronizeRevision();
        }, () -> new Tooltip(layerButtonText()));
        map.addButton(this.seedAtlas$layerButton);

        // Compact controls in the top-left corner; full names remain available as tooltips.
        Button xaeroSettings = this.settingsButton;
        int settingsY = 0;
        Tooltip mapTooltip = new Tooltip(Component.translatable("options.seedatlas_xaero.map_settings"));
        Tooltip atlasTooltip = new Tooltip(Component.translatable("options.seedatlas_xaero.seed_atlas_settings"));
        this.seedAtlas$mapSettingsButton = new TooltipButton(0, settingsY, 20, 20, Component.literal("M"),
            button -> this.onSettingsButton(xaeroSettings), () -> mapTooltip);
        map.addButton(this.seedAtlas$mapSettingsButton);
        this.seedAtlas$settingsButton = new TooltipButton(22, settingsY, 20, 20, Component.literal("SA"),
            button -> SeedAtlasClientState.openSettings(map), () -> atlasTooltip);
        map.addButton(this.seedAtlas$settingsButton);
        if (xaeroSettings != null) {
            xaeroSettings.visible = false;
            xaeroSettings.active = false;
        }

        // Quick access labels are drawn by this mixin (no button chrome), so only the free
        // Y slot has to be resolved here.
        this.seedAtlas$quickY = seedAtlas$freeColumnY(map, 132);
        this.seedAtlas$displayedRevision = SeedAtlasClientState.revision();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void seedAtlas$refreshState(CallbackInfo ci) {
        // Xaero recentres on the player until its first frame; wait for that, then jump.
        if (MapNavigator.hasPendingCamera() && !this.shouldResetCameraPos) {
            var target = MapNavigator.takeCamera();
            attachedCamera = false;
            this.cameraX = target.x();
            this.cameraZ = target.z();
        }
        MapNavigator.tickWaypoint();
        SeedAtlasClientState.refreshContext(Minecraft.getInstance());
        SeedAtlasXaeroIntegration.synchronizeRevision();
        seedAtlas$observeXaeroView();
        if (this.seedAtlas$layerButton != null) {
            this.seedAtlas$layerButton.visible = !GuiMap.hiddenUI;
        }
        if (this.seedAtlas$settingsButton != null) {
            this.seedAtlas$settingsButton.visible = !GuiMap.hiddenUI;
        }
        if (this.seedAtlas$mapSettingsButton != null) {
            this.seedAtlas$mapSettingsButton.visible = !GuiMap.hiddenUI;
        }
        long revision = SeedAtlasClientState.revision();
        if (revision == this.seedAtlas$displayedRevision) {
            return;
        }
        this.seedAtlas$displayedRevision = revision;
        if (this.seedAtlas$layerButton != null) {
            this.seedAtlas$layerButton.setMessage(compactLayerButtonText());
        }
    }

    /** Seed / camera readout along the top edge, like the launcher's seed map header. */
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void seedAtlas$renderInfoBar(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci
    ) {
        if (GuiMap.hiddenUI) return;
        GuiMap map = (GuiMap)(Object)this;
        var seed = SeedAtlasClientState.activeSeed();
        String seedText = seed.isPresent() ? Long.toString(seed.getAsLong()) : "未设置";
        String dimension = "?";
        try {
            var dim = map.getMapProcessor().getMapWorld().getCurrentDimension().getDimId();
            dimension = dim.equals(net.minecraft.world.level.Level.NETHER) ? "下界"
                : dim.equals(net.minecraft.world.level.Level.END) ? "末地" : "主世界";
        } catch (RuntimeException ignored) {
            // dimension can swap mid-frame
        }
        int x = (int)Math.round(this.cameraX);
        int z = (int)Math.round(this.cameraZ);
        String text = "种子 " + seedText + "  ·  " + dimension + "  ·  X " + x + "  Z " + z;
        var font = Minecraft.getInstance().font;
        int width = font.width(text) + 12;
        int top = 2;
        graphics.fill(48, top, 48 + width, top + 14, 0x90000000);
        graphics.text(font, Component.literal(text), 54, top + 4, 0xFFE8E8E8);
    }

    @Unique
    private static final String[] SEED_ATLAS$QUICK_LABELS = {"构", "群", "找", "近"};
    @Unique
    private static final String[] SEED_ATLAS$QUICK_TITLES = {"结构开关", "群系高亮", "最近的结构", "最近的群系"};

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void seedAtlas$renderQuickLabels(
        GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci
    ) {
        if (GuiMap.hiddenUI || this.seedAtlas$quickY < 0) return;
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < SEED_ATLAS$QUICK_LABELS.length; i++) {
            int by = this.seedAtlas$quickY + i * 22;
            boolean hovered = mouseX >= 0 && mouseX < 20 && mouseY >= by && mouseY < by + 20;
            int color = hovered ? 0xFFFFFF80 : 0xFFFFFFFF;
            graphics.text(font, Component.literal(SEED_ATLAS$QUICK_LABELS[i]), 6, by + 6, color, true);
            if (hovered) {
                graphics.setTooltipForNextFrame(Component.literal(SEED_ATLAS$QUICK_TITLES[i]), mouseX, mouseY);
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void seedAtlas$quickClick(
        net.minecraft.client.input.MouseButtonEvent event, boolean doubled,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.seedAtlas$quickY < 0) return;
        int mx = (int)event.x();
        int my = (int)event.y();
        if (mx < 0 || mx >= 20) return;
        int index = (my - this.seedAtlas$quickY) / 22;
        if (index < 0 || index >= 4) return;
        GuiMap map = (GuiMap)(Object)this;
        switch (index) {
            case 0 -> Minecraft.getInstance().gui.setScreen(new MarkerSettingsScreen(map));
            case 1 -> Minecraft.getInstance().gui.setScreen(new BiomeHighlightScreen(map));
            case 2 -> Minecraft.getInstance().gui.setScreen(new NearestScreen(map, false));
            default -> Minecraft.getInstance().gui.setScreen(new NearestScreen(map, true));
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void seedAtlas$disableFullMapLayer(CallbackInfo ci) {
        SeedAtlasXaeroIntegration.setFullMapActive(false);
    }


    /** First y in the left column with room for the four quick-access buttons. */
    @Unique
    private static int seedAtlas$freeColumnY(GuiMap map, int fallback) {
        int need = 4 * 22 + 4;
        int limit = Math.max(400, map.height);
        boolean[] used = new boolean[limit + need + 8];
        try {
            for (var listener : map.children()) {
                if (listener instanceof Button button && button.getX() < 44 && button.getWidth() <= 24) {
                    int y0 = Math.max(0, button.getY());
                    int y1 = Math.min(used.length, button.getY() + button.getHeight());
                    for (int y = y0; y < y1; y++) used[y] = true;
                }
            }
        } catch (RuntimeException ignored) {
            return fallback;
        }
        for (int y = 0; y + need < used.length; y++) {
            boolean free = true;
            for (int k = 0; k < need; k++) {
                if (used[y + k]) { free = false; break; }
            }
            if (free) return y;
        }
        return fallback;
    }

    @Unique
    private static Component layerButtonText() {
        Component state = Component.translatable(
            SeedAtlasClientState.config().layerEnabled()
                ? "options.seedatlas_xaero.on"
                : "options.seedatlas_xaero.off");
        return Component.translatable("options.seedatlas_xaero.layer", state);
    }

    @Unique
    private static Component compactLayerButtonText() {
        return Component.literal("SA").withStyle(
            SeedAtlasClientState.config().layerEnabled()
                ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    @Unique
    private void seedAtlas$observeXaeroView() {
        try {
            GuiMap map = (GuiMap)(Object)this;
            if (map.getMapProcessor() == null
                || map.getMapProcessor().getMapWorld() == null
                || map.getMapProcessor().getMapWorld().getCurrentDimension() == null) {
                return;
            }
            SeedAtlasXaeroIntegration.observeMapView(
                map.getMapProcessor().getMapWorld().getCurrentDimension().getDimId(),
                this.cameraX,
                this.cameraZ,
                this.scale,
                Minecraft.getInstance().getWindow().getWidth(),
                Minecraft.getInstance().getWindow().getHeight()
            );
        } catch (RuntimeException ignored) {
            // GuiMap can swap dimensions while a frame is being prepared.
        }
    }
}

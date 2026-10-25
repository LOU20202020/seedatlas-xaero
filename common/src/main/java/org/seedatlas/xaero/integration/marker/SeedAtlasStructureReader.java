package org.seedatlas.xaero.integration.marker;

import java.util.ArrayList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.detail.StructureDetail;
import org.seedatlas.xaero.detail.StructureTypes;
import org.seedatlas.xaero.gui.StructureDetailScreen;
import org.seedatlas.xaero.integration.icon.CompletedBadge;
import org.seedatlas.xaero.integration.icon.IconLayout;
import org.seedatlas.xaero.integration.icon.StructureIcons;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderLocation;

final class SeedAtlasStructureReader extends ElementReader<
    SeedAtlasStructureMarker,
    SeedAtlasStructureContext,
    SeedAtlasStructureRenderer
> {
    @Override
    public boolean isHidden(SeedAtlasStructureMarker marker, SeedAtlasStructureContext context) {
        return context.dimension == null || !context.dimension.equals(marker.dimension())
            || (SeedAtlasClientState.config().hideCompletedStructures() && SeedAtlasStructureState.isCompleted(marker));
    }

    @Override
    public double getRenderX(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return marker.x();
    }

    @Override
    public double getRenderY(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return marker.y();
    }

    @Override
    public boolean hasYCoordinate() {
        // Show horizontal map distance: many predicted structures have unknown Y.
        return false;
    }

    @Override
    public double getRenderZ(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return marker.z();
    }

    @Override
    public int getInteractionBoxLeft(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxLeft(marker);
    }

    @Override
    public int getInteractionBoxRight(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxRight(marker);
    }

    @Override
    public int getInteractionBoxTop(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxTop(marker);
    }

    @Override
    public int getInteractionBoxBottom(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxBottom(marker);
    }

    @Override
    public int getRenderBoxLeft(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxLeft(marker);
    }

    @Override
    public int getRenderBoxRight(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxRight(marker);
    }

    @Override
    public int getRenderBoxTop(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxTop(marker);
    }

    @Override
    public int getRenderBoxBottom(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, float partialTicks
    ) {
        return boxBottom(marker);
    }

    private static int boxLeft(SeedAtlasStructureMarker marker) {
        return StructureIcons.get(marker.textureId()).layout().left() - 1;
    }

    private static int boxTop(SeedAtlasStructureMarker marker) {
        return StructureIcons.get(marker.textureId()).layout().top() - 1;
    }

    private static int boxRight(SeedAtlasStructureMarker marker) {
        IconLayout layout = StructureIcons.get(marker.textureId()).layout();
        int right = layout.right() + 1;
        // The completion badge overhangs the icon's lower right corner.
        return SeedAtlasStructureState.isCompleted(marker)
            ? Math.max(right, CompletedBadge.right(layout) + 1)
            : right;
    }

    private static int boxBottom(SeedAtlasStructureMarker marker) {
        IconLayout layout = StructureIcons.get(marker.textureId()).layout();
        int bottom = layout.bottom() + 1;
        return SeedAtlasStructureState.isCompleted(marker)
            ? Math.max(bottom, CompletedBadge.bottom(layout) + 1)
            : bottom;
    }

    @Override
    public int getLeftSideLength(SeedAtlasStructureMarker marker, Minecraft minecraft) {
        return 10 + minecraft.font.width(marker.displayName());
    }

    @Override
    public String getMenuName(SeedAtlasStructureMarker marker) {
        return marker.displayName();
    }

    @Override
    public String getFilterName(SeedAtlasStructureMarker marker) {
        return marker.type().id();
    }

    @Override
    public int getMenuTextFillLeftPadding(SeedAtlasStructureMarker marker) {
        return 0;
    }

    @Override
    public int getRightClickTitleBackgroundColor(SeedAtlasStructureMarker marker) {
        return 0xCC1F2530;
    }

    @Override
    public boolean shouldScaleBoxWithOptionalScale() {
        return true;
    }

    @Override
    public float getBoxScale(
        ElementRenderLocation location, SeedAtlasStructureMarker marker,
        SeedAtlasStructureContext context
    ) {
        // Xaero applies optionalScale separately to both hit and render boxes.
        return context.iconScale;
    }

    @Override
    public boolean isInteractable(ElementRenderLocation location, SeedAtlasStructureMarker marker) {
        return location == ElementRenderLocation.WORLD_MAP;
    }

    @Override
    public boolean isRightClickValid(SeedAtlasStructureMarker marker) {
        return SeedAtlasStructureState.key(marker) != null;
    }

    @Override
    public ArrayList<RightClickOption> getRightClickOptions(SeedAtlasStructureMarker marker, IRightClickableElement target) {
        var options = new ArrayList<RightClickOption>();
        options.add(new RightClickOption("", options.size(), target) {
            @Override
            public Component getDisplayName() {
                return Component.literal(marker.displayName()).withStyle(ChatFormatting.GRAY);
            }

            @Override
            public void onAction(Screen screen) { }
        }.setActive(false));
        var key = SeedAtlasStructureState.key(marker);
        boolean completed = SeedAtlasClientState.config().isCompleted(key);
        options.add(new RightClickOption(completed ? "menu.seedatlas_xaero.mark_incomplete"
            : "menu.seedatlas_xaero.mark_completed", options.size(), target) {
            @Override
            public void onAction(Screen screen) {
                // Ignore stale menus if the world/seed changed while they were open.
                if (key != null && key.equals(SeedAtlasStructureState.key(marker))) {
                    SeedAtlasClientState.setStructureCompleted(key, !completed);
                }
            }
        }.setActive(key != null));
        options.add(new RightClickOption("menu.seedatlas_xaero.structure_detail", options.size(), target) {
            @Override
            public void onAction(Screen screen) {
                if (key == null || !key.equals(SeedAtlasStructureState.key(marker))) return;
                var seed = SeedAtlasClientState.activeSeed();
                if (seed.isEmpty()) return;
                Minecraft.getInstance().gui.setScreen(
                    new StructureDetailScreen(screen).withSeed(seed.getAsLong()).withType(marker.type()));
            }
        }.setActive(StructureTypes.of(marker.type()) != null));
        options.add(new RightClickOption("menu.seedatlas_xaero.copy_coordinates", options.size(), target) {
            @Override
            public void onAction(Screen screen) {
                String coordinates = "X: " + marker.x()
                    + (marker.y() == Integer.MIN_VALUE ? "" : ", Y: " + marker.y()) + ", Z: " + marker.z();
                Minecraft.getInstance().keyboardHandler.setClipboard(coordinates);
            }
        });
        if (SeedAtlasWaypointBridge.available()) {
            options.add(new RightClickOption("menu.seedatlas_xaero.create_waypoint", options.size(), target) {
                @Override
                public void onAction(Screen screen) {
                    if (screen instanceof xaero.map.gui.GuiMap map && key != null
                        && key.equals(SeedAtlasStructureState.key(marker))) {
                        SeedAtlasWaypointBridge.create(map, marker);
                    }
                }
            });
            options.add(new RightClickOption("menu.seedatlas_xaero.create_temp_waypoint", options.size(), target) {
                @Override
                public void onAction(Screen screen) {
                    if (screen instanceof xaero.map.gui.GuiMap map && key != null
                        && key.equals(SeedAtlasStructureState.key(marker))) {
                        SeedAtlasWaypointBridge.createTemporary(map, marker);
                    }
                }
            });
        }
        return options;
    }

    @Override
    public Tooltip getTooltip(
        SeedAtlasStructureMarker marker, SeedAtlasStructureContext context, boolean overMenu
    ) {
        return new Tooltip(SeedAtlasStructureState.isCompleted(marker)
            ? marker.tooltip().copy().append(" · ").append(Component.translatable("tooltip.seedatlas_xaero.completed"))
            : marker.tooltip(), true);
    }
}

package org.seedatlas.xaero.integration.marker;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.WorldMap;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;
import xaero.map.gui.GuiMap;
import xaero.map.mods.SupportMods;

/**
 * Uses World Map's own optional integration where it exists. Only named waypoints
 * reach Minimap's classes, reflectively, and switch themselves off when those change.
 */
public final class SeedAtlasWaypointBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("seedatlas_xaero");
    private static boolean incompatible;
    private static boolean namedIncompatible;

    /**
     * Named waypoint at the exact coordinates in the current waypoint world, hidden when disabled. Built reflectively
     * because World Map does not expose Minimap's waypoint classes at compile time.
     * Returns false while Minimap is not ready yet, so the caller may retry on a later tick;
     * once the waypoint is in its set it returns true, even if saving it failed, to avoid duplicates.
     */
    public static boolean createNamed(int x, int z, String name, boolean temporary, boolean disabled) {
        return createNamed(x, 32767, z, name, temporary, disabled);
    }

    /**
     * Named waypoint in the current waypoint set. Xaero 26.x dropped the old
     * {@code SupportXaeroMinimap.getWaypointWorld()} entry point, so the set is reached through
     * {@code XaeroMinimapSession -> MinimapProcessor -> MinimapSession -> world manager -> world}.
     */
    public static boolean createNamed(int x, int y, int z, String name, boolean temporary, boolean disabled) {
        if (namedIncompatible) return false;
        if (!available()) {
            LOGGER.info("waypoints unavailable (minimap missing or world-map waypoints disabled)");
            return false;
        }
        try {
            Object set = currentWaypointSet();
            if (set == null) {
                LOGGER.info("waypoint set unavailable (minimap session not ready)");
                return false;
            }
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            Object waypoint = waypointClass.getConstructor(int.class, int.class, int.class, String.class,
                String.class, colorClass, purposeClass, boolean.class, boolean.class).newInstance(
                x, y, z, name, name.isEmpty() ? "X" : name.substring(0, 1).toUpperCase(Locale.ROOT),
                colorClass.getMethod("getRandom").invoke(null), purposeClass.getField("NORMAL").get(null),
                temporary, false);
            if (disabled) waypointClass.getMethod("setDisabled", boolean.class).invoke(waypoint, true);
            set.getClass().getMethod("add", waypointClass).invoke(set, waypoint);
            LOGGER.info("waypoint added {} at {},{},{} temp={}", name, x, y, z, temporary);
            return true;
        } catch (InvocationTargetException | RuntimeException error) {
            LOGGER.warn("Seed Atlas waypoint not created", error);
            return false;
        } catch (ReflectiveOperationException | LinkageError error) {
            namedIncompatible = true;
            LOGGER.warn("Disabling named Seed Atlas waypoints: Xaero's Minimap API is incompatible", error);
            return false;
        }
    }

    /** Current waypoint set, or null while a world/minimap session is not up yet. */
    private static Object currentWaypointSet() throws ReflectiveOperationException {
        Object session = Class.forName("xaero.common.XaeroMinimapSession")
            .getMethod("getCurrentSession").invoke(null);
        if (session == null) return null;
        Object processor = session.getClass().getMethod("getMinimapProcessor").invoke(session);
        if (processor == null) return null;
        Object minimap = processor.getClass().getMethod("getSession").invoke(processor);
        if (minimap == null) return null;
        Object manager = minimap.getClass().getMethod("getWorldManager").invoke(minimap);
        if (manager == null) return null;
        Object world = manager.getClass().getMethod("getCurrentWorld").invoke(manager);
        if (world == null) return null;
        return world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
    }

    static boolean available() {
        if (incompatible) return false;
        try {
            return SupportMods.minimap() && Boolean.TRUE.equals(WorldMap.INSTANCE.getConfigs()
                .getClientConfigManager().getEffective(WorldMapProfiledConfigOptions.WAYPOINTS));
        } catch (LinkageError error) {
            disable(error);
            return false;
        }
    }

    static void create(GuiMap map, SeedAtlasStructureMarker marker) {
        if (!available()) {
            unavailable(map);
            return;
        }
        try {
            var processor = map.getMapProcessor();
            var dimension = processor.getMapWorld().getCurrentDimension();
            if (dimension == null || !marker.dimension().equals(dimension.getDimId())) {
                unavailable(map);
                return;
            }
            // Xaero captures the source waypoint world on the actual right click.
            // Supply exact structure coordinates, not the nearby mouse/terrain pixel.
            // Its bridge expects 32767 for unknown Y, rather than our MIN_VALUE.
            SupportMods.xaeroMinimap.createWaypoint(map, marker.x(),
                marker.y() == Integer.MIN_VALUE ? 32767 : marker.y(), marker.z(),
                dimension.calculateDimScale(processor.getWorldDimensionTypeRegistry()), true);
            if (Minecraft.getInstance().gui.screen() == map) unavailable(map);
        } catch (LinkageError error) {
            disable(error);
            unavailable(map);
        }
    }

    static void createTemporary(GuiMap map, SeedAtlasStructureMarker marker) {
        if (!available()) {
            unavailable(map);
            return;
        }
        try {
            var processor = map.getMapProcessor();
            var dimension = processor.getMapWorld().getCurrentDimension();
            if (dimension == null || !marker.dimension().equals(dimension.getDimId())) {
                unavailable(map);
                return;
            }
            SupportMods.xaeroMinimap.createTempWaypoint(marker.x(),
                marker.y() == Integer.MIN_VALUE ? 32767 : marker.y(), marker.z(),
                dimension.calculateDimScale(processor.getWorldDimensionTypeRegistry()), true);
        } catch (LinkageError error) {
            disable(error);
            unavailable(map);
        }
    }

    private static void disable(LinkageError error) {
        incompatible = true;
        LOGGER.warn("Disabling optional waypoint integration: World Map's bridge is incompatible", error);
    }

    private static void unavailable(GuiMap map) {
        map.getMapProcessor().getMessageBox().addMessage(
            Component.translatable("message.seedatlas_xaero.waypoint_unavailable"));
    }
}

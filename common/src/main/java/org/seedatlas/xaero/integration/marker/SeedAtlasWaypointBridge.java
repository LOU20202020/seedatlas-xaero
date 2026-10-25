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
        if (namedIncompatible || !available()) return false;
        Object world;
        Class<?> worldClass;
        try {
            Object support = SupportMods.xaeroMinimap;
            world = support.getClass().getMethod("getWaypointWorld").invoke(support);
            if (world == null) return false;
            Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            Class<?> colorClass = Class.forName("xaero.hud.minimap.waypoint.WaypointColor");
            Class<?> purposeClass = Class.forName("xaero.hud.minimap.waypoint.WaypointPurpose");
            worldClass = Class.forName("xaero.hud.minimap.world.MinimapWorld");
            Object waypoint = waypointClass.getConstructor(int.class, int.class, int.class, String.class,
                String.class, colorClass, purposeClass, boolean.class, boolean.class).newInstance(
                x, 32767, z, name, name.isEmpty() ? "X" : name.substring(0, 1).toUpperCase(Locale.ROOT),
                colorClass.getMethod("getRandom").invoke(null), purposeClass.getField("NORMAL").get(null),
                temporary, false);
            if (disabled) waypointClass.getMethod("setDisabled", boolean.class).invoke(waypoint, true);
            Object set = worldClass.getMethod("getCurrentWaypointSet").invoke(world);
            if (set == null) return false;
            set.getClass().getMethod("add", waypointClass).invoke(set, waypoint);
        } catch (InvocationTargetException | RuntimeException error) {
            // Minimap threw while its waypoint world was still settling; MapNavigator retries.
            LOGGER.debug("Seed Atlas waypoint not created yet", error);
            return false;
        } catch (ReflectiveOperationException | LinkageError error) {
            namedIncompatible = true;
            LOGGER.warn("Disabling named Seed Atlas waypoints: Xaero's Minimap API is incompatible", error);
            return false;
        }
        if (!temporary) {
            try {
                Object module = Class.forName("xaero.hud.minimap.BuiltInHudModules").getField("MINIMAP").get(null);
                Object session = module.getClass().getMethod("getCurrentSession").invoke(module);
                Object io = session.getClass().getMethod("getWorldManagerIO").invoke(session);
                io.getClass().getMethod("saveWorld", worldClass).invoke(io, world);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                // The waypoint exists in memory; Minimap saves it with its next change.
                LOGGER.warn("Could not save Seed Atlas waypoint immediately", error);
            }
        }
        try {
            SupportMods.xaeroMinimap.requestWaypointsRefresh();
        } catch (LinkageError error) {
            disable(error);
        }
        return true;
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

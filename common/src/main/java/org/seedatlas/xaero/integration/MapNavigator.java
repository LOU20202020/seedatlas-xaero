package org.seedatlas.xaero.integration;

import net.minecraft.client.Minecraft;
import org.seedatlas.xaero.integration.marker.SeedAtlasWaypointBridge;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;

/** Opens Xaero's full map; GuiMapMixin then centres it on the pending target and adds the waypoint. */
public final class MapNavigator {
    /** Every jump leaves a waypoint: a temporary one, or a permanent one that starts shown or hidden. */
    public enum WaypointMode { TEMPORARY, ENABLED, DISABLED }

    public record Target(int x, int z, String name, WaypointMode mode) { }

    private static volatile Target pendingCamera;
    private static Target pendingWaypoint;
    private static int waypointTries;

    private MapNavigator() { }

    public static boolean open(int x, int z, String name, WaypointMode mode) {
        Minecraft mc = Minecraft.getInstance();
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable() || mc.getCameraEntity() == null) return false;
        Target target = new Target(x, z, name, mode);
        pendingCamera = target;
        pendingWaypoint = target;
        waypointTries = 0;
        SeedAtlasXaeroIntegration.setHeavyWorkPaused(false);
        mc.gui.setScreen(new GuiMap(null, null, session.getMapProcessor(), mc.getCameraEntity()));
        return true;
    }

    public static Target takeCamera() {
        Target target = pendingCamera;
        pendingCamera = null;
        return target;
    }

    public static boolean hasPendingCamera() {
        return pendingCamera != null;
    }

    /** Xaero's waypoint world only exists once the map has rendered, so retry for ~2 seconds. */
    public static void tickWaypoint() {
        Target target = pendingWaypoint;
        if (target == null) return;
        if (SeedAtlasWaypointBridge.createNamed(target.x(), target.z(), target.name(),
            target.mode() == WaypointMode.TEMPORARY, target.mode() == WaypointMode.DISABLED) || ++waypointTries > 40) {
            pendingWaypoint = null;
        }
    }
}

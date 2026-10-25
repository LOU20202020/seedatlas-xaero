package org.seedatlas.xaero.integration.icon;

/**
 * Size of map markers.
 *
 * Markers keep a fixed footprint on the map instead of a fixed pixel size: one map unit is
 * one map pixel and Xaero draws {@code mapScale} pixels per block, so multiplying the world
 * footprint by that scale makes the icon grow with the map. Zooming in therefore never makes
 * a marker look smaller relative to the terrain, while a pixel floor keeps far zoom levels
 * readable and a share of the viewport stops extreme zoom from filling the screen.
 */
public final class MarkerSize {
    /** World footprint of a marker in blocks, before the user setting. */
    public static final float BLOCKS_PER_MARKER = 2.0F;
    /** Smallest marker size, in the pixels Xaero uses for its own map sprites. */
    public static final float MIN_PIXELS = 20.0F;
    /** Largest share of the map's shorter side a marker may cover. */
    public static final float MAX_SCREEN_SHARE = 0.25F;

    private MarkerSize() { }

    /**
     * @param mapScale              Xaero's map scale: pixels per block at the current zoom
     * @param screenSizeBasedScale  Xaero's screen-size factor for the current window
     * @param viewportShortSide     shorter side of the map viewport, in the same units
     * @param userScale             marker size setting as a factor of the default band
     */
    public static float iconScale(
        double mapScale, double screenSizeBasedScale, double viewportShortSide, float userScale
    ) {
        float setting = Math.clamp(userScale, 0.25F, 4.0F);
        double multiplier = Math.max(screenSizeBasedScale, 1.0E-4D);
        float floor = (float) (MIN_PIXELS * multiplier) * setting;
        float scaledWithMap = BLOCKS_PER_MARKER * setting * (float) Math.max(mapScale, 0.0D);
        float ceiling = Math.max(floor, (float) viewportShortSide * MAX_SCREEN_SHARE);
        float units = Math.clamp(Math.max(floor, scaledWithMap), floor, ceiling);
        return units / IconLayout.DISPLAY_SIZE;
    }
}

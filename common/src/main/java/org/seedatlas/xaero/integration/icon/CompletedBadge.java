package org.seedatlas.xaero.integration.icon;

import net.minecraft.resources.Identifier;

/**
 * Geometry of the pixel-art completion badge.
 *
 * The badge is drawn in the same coordinate space as the structure icon (18 units for
 * the full marker), so it keeps a readable size together with the icon and scales with
 * it. It sits on the icon's lower right corner and overhangs it slightly.
 */
public final class CompletedBadge {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
        "seedatlas_xaero", "textures/structure/completed.png");
    /** Pixel raster of the badge texture. */
    public static final int SOURCE_SIZE = 12;
    /** Displayed size in marker units, where the structure icon spans 18 units. */
    public static final int DISPLAY_SIZE = 10;
    /** Units the badge sticks out beyond the icon's lower right corner. */
    private static final int OVERHANG = 3;

    private CompletedBadge() { }

    public static int left(IconLayout icon) {
        return icon.right() - DISPLAY_SIZE + OVERHANG;
    }

    public static int top(IconLayout icon) {
        return icon.bottom() - DISPLAY_SIZE + OVERHANG;
    }

    public static int right(IconLayout icon) {
        return left(icon) + DISPLAY_SIZE;
    }

    public static int bottom(IconLayout icon) {
        return top(icon) + DISPLAY_SIZE;
    }
}

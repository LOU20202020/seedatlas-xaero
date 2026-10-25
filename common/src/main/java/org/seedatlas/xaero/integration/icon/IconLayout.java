package org.seedatlas.xaero.integration.icon;

import java.util.function.IntBinaryOperator;

/** Visible source bounds, fitted into a common marker size without distorting the image. */
public record IconLayout(int textureWidth, int textureHeight, int u, int v, int width, int height) {
    public static final int DISPLAY_SIZE = 18;

    public IconLayout {
        if (textureWidth <= 0 || textureHeight <= 0 || width <= 0 || height <= 0
            || u < 0 || v < 0 || u + width > textureWidth || v + height > textureHeight) {
            throw new IllegalArgumentException("Invalid icon bounds");
        }
    }

    public static IconLayout measure(int width, int height, IntBinaryOperator argb) {
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Ignore virtually invisible export noise around the original camp sources.
                if ((argb.applyAsInt(x, y) >>> 24) >= 8) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        // An intentionally empty resource-pack icon must remain empty, with valid UVs.
        return right < left ? new IconLayout(width, height, 0, 0, width, height)
            : new IconLayout(width, height, left, top, right - left + 1, bottom - top + 1);
    }

    public int displayWidth() {
        return Math.max(1, Math.round((float) width * DISPLAY_SIZE / Math.max(width, height)));
    }

    public int displayHeight() {
        return Math.max(1, Math.round((float) height * DISPLAY_SIZE / Math.max(width, height)));
    }

    public int left() { return -displayWidth() / 2; }
    public int top() { return -displayHeight() / 2; }
    public int right() { return left() + displayWidth(); }
    public int bottom() { return top() + displayHeight(); }
}

package org.seedatlas.xaero.integration.icon;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import javax.imageio.ImageIO;

/** Geometry and asset regressions that do not need a running Minecraft client. */
public final class IconLayoutTest {
    public static void main(String[] args) throws Exception {
        BufferedImage padded = new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB);
        for (int y = 7; y < 17; y++) {
            for (int x = 5; x < 25; x++) padded.setRGB(x, y, 0xFFAA6600);
        }
        padded.setRGB(0, 0, 0x01000000); // near-invisible export noise is not part of the motif
        IconLayout layout = measure(padded);
        check(layout.u() == 5 && layout.v() == 7 && layout.width() == 20 && layout.height() == 10,
            "Padding must not shrink or offset the visible motif");
        check(layout.displayWidth() == 18 && layout.displayHeight() == 9,
            "Non-square icons must retain their aspect ratio");
        check(layout.left() + layout.displayWidth() == layout.right(), "Horizontal bounds");
        check(layout.top() + layout.displayHeight() == layout.bottom(), "Vertical bounds");
        IconLayout empty = measure(new BufferedImage(19, 20, BufferedImage.TYPE_INT_ARGB));
        check(empty.width() == 19 && empty.height() == 20, "Empty pack textures need valid full UVs");

        Path directory = Path.of("common/src/main/resources/assets/seedatlas_xaero/textures/structure");
        int count = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                if (path.getFileName().toString().equals("completed.png")) continue;
                BufferedImage image = ImageIO.read(path.toFile());
                IconLayout bounds = measure(image);
                check(Math.max(bounds.displayWidth(), bounds.displayHeight()) == 18,
                    "All visible motifs must share the same maximum size: " + path);
                check(bounds.u() >= 0 && bounds.u() + bounds.width() <= image.getWidth(), "Source X bounds");
                check(bounds.v() >= 0 && bounds.v() + bounds.height() <= image.getHeight(), "Source Y bounds");
                double scale = 18.0 / Math.max(bounds.width(), bounds.height());
                check(Math.abs(bounds.displayWidth() - bounds.width() * scale) <= 0.5,
                    "Horizontal aspect ratio: " + path);
                check(Math.abs(bounds.displayHeight() - bounds.height() * scale) <= 0.5,
                    "Vertical aspect ratio: " + path);
                // Nearest sampling at ~18 px only stays crisp for pixel art drawn near that size.
                check(Math.max(image.getWidth(), image.getHeight()) <= 32, "Icons are small pixel art: " + path);
                check(blackShare(image, bounds) < 0.45, "Black outlines must not dominate the motif: " + path);
                count++;
            }
        }
        // The other markers show Minecraft's own block and item textures, which are never bundled.
        check(count == 6, "Every bundled pixel-art icon must be checked");
        BufferedImage camp = ImageIO.read(directory.resolve("camp.png").toFile());
        BufferedImage special = ImageIO.read(directory.resolve("camp_special.png").toFile());
        for (BufferedImage image : new BufferedImage[]{camp, special}) {
            IconLayout campBounds = measure(image);
            check(campBounds.width() == image.getWidth() && campBounds.height() == image.getHeight(),
                "Camp pixel art must fill its canvas without transparent margins");
            check(Math.max(image.getWidth(), image.getHeight()) >= 16,
                "Camp icons keep a readable pixel grid");
            check(distinctColours(image) <= 3,
                "Camp icons stay flat: outline, fill, transparency");
        }
        check(camp.getWidth() == special.getWidth() && camp.getHeight() == special.getHeight()
                && sameSilhouette(camp, special),
            "The blue variant may only change the tent fill colour");
        IconLayout campLayout = measure(camp);
        check(CompletedBadge.right(campLayout) > campLayout.right()
                && CompletedBadge.bottom(campLayout) > campLayout.bottom()
                && CompletedBadge.left(campLayout) < campLayout.right()
                && CompletedBadge.top(campLayout) < campLayout.bottom(),
            "Badge overlaps the icon's lower right corner");
        BufferedImage badge = ImageIO.read(directory.resolve("completed.png").toFile());
        check(badge.getWidth() == CompletedBadge.SOURCE_SIZE && badge.getHeight() == CompletedBadge.SOURCE_SIZE,
            "Badge keeps its source raster");
        check(distinctColours(badge) == 4, "Badge stays flat: ring, fill, check, transparency");
        check((badge.getRGB(0, 0) >>> 24) == 0
                && (badge.getRGB(badge.getWidth() / 2, badge.getHeight() / 2) >>> 24) == 255,
            "Badge is an opaque disc with transparent corners");
        checkPixels(MarkerSize.iconScale(0.25, 1.0, 1000.0, 1.0F), MarkerSize.MIN_PIXELS,
            "Far zoom must use the minimum marker size");
        checkPixels(MarkerSize.iconScale(0.125, 1.0, 1000.0, 1.0F), MarkerSize.MIN_PIXELS,
            "Zooming out must not shrink markers further");
        checkPixels(MarkerSize.iconScale(10.0, 1.0, 1000.0, 1.0F), MarkerSize.MIN_PIXELS,
            "Markers only leave the minimum band when the map outgrows it");
        checkPixels(MarkerSize.iconScale(20.0, 1.0, 1000.0, 1.0F),
            MarkerSize.BLOCKS_PER_MARKER * 20.0F,
            "Markers must keep a fixed map footprint instead of shrinking");
        checkPixels(MarkerSize.iconScale(50.0, 1.0, 2000.0, 1.0F),
            MarkerSize.BLOCKS_PER_MARKER * 50.0F,
            "Deeper zoom keeps growing the footprint with the map");
        checkPixels(MarkerSize.iconScale(50.0, 1.0, 300.0, 1.0F),
            300.0F * MarkerSize.MAX_SCREEN_SHARE,
            "Markers stop at a share of the viewport");
        checkPixels(MarkerSize.iconScale(0.25, 1.0, 1000.0, 2.0F), 2.0F * MarkerSize.MIN_PIXELS,
            "The marker size setting scales the minimum");
        check(Math.abs(relativeShare(50.0, 1.0, 1080.0) - relativeShare(100.0, 2.0, 2160.0)) < 1.0E-4F,
            "The marker share of the map must not depend on the window size");
        System.out.println("Icon geometry and transparency tests passed (" + count + " icons)");
    }

    private static void checkPixels(float iconScale, float expectedPixels, String message) {
        float pixels = iconScale * IconLayout.DISPLAY_SIZE;
        check(Math.abs(pixels - expectedPixels) < 0.01F, message + " (was " + pixels + " px)");
    }

    private static float relativeShare(double mapScale, double screenFactor, double viewportShortSide) {
        return MarkerSize.iconScale(mapScale, screenFactor, viewportShortSide, 1.0F)
            * IconLayout.DISPLAY_SIZE / (float) viewportShortSide;
    }

    /** Share of near-black opaque pixels inside the visible bounds. */
    private static double blackShare(BufferedImage image, IconLayout bounds) {
        int black = 0;
        for (int y = bounds.v(); y < bounds.v() + bounds.height(); y++) {
            for (int x = bounds.u(); x < bounds.u() + bounds.width(); x++) {
                int argb = image.getRGB(x, y);
                int max = Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
                if ((argb >>> 24) >= 8 && max < 30) black++;
            }
        }
        return black / (double) (bounds.width() * bounds.height());
    }

    private static boolean sameSilhouette(BufferedImage first, BufferedImage second) {
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if ((first.getRGB(x, y) >>> 24) != (second.getRGB(x, y) >>> 24)) return false;
            }
        }
        return true;
    }

    private static int distinctColours(BufferedImage image) {
        var colours = new HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) colours.add(image.getRGB(x, y));
        }
        return colours.size();
    }

    private static IconLayout measure(BufferedImage image) {
        return IconLayout.measure(image.getWidth(), image.getHeight(), image::getRGB);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

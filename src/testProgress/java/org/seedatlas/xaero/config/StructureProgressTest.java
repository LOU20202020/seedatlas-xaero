package org.seedatlas.xaero.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.seedatlas.xaero.config.SeedAtlasConfig.StructureKey;
import org.seedatlas.xaero.nativeapi.Dimension;

/** Executable persistence regression; runs without opening a Minecraft client. */
public final class StructureProgressTest {
    public static void main(String[] args) throws Exception {
        var directory = Files.createTempDirectory("seedatlas-progress-test");
        var file = directory.resolve("config.json");
        try {
            Files.writeString(file, """
                {"version":5,"layer":{"enabled":true,"opacity":180},
                 "structures":{"enabled":true},"markers":{"village":false},
                 "seeds":{"server-a":{"input":"123","value":123,"label":"A"}}}
                """);
            var config = SeedAtlasConfigIO.load(file);
            check(!config.overlayExplored(), "Old configs keep predictions beneath explored terrain");
            check(config.setOverlayExplored(true), "Enable overlay above explored terrain");
            check(!config.setOverlayExplored(true), "Repeated overlay toggle is idempotent");
            check(!config.hideCompletedStructures(), "Old configs must show completed structures");
            var key = new StructureKey("server-a", 123, false, "minecraft:overworld", "village", -128, 256);
            check(config.setCompleted(key, true), "First completion");
            check(!config.setCompleted(key, true), "Completion must be idempotent");
            check(!config.hideCompletedStructures(), "Completing must not enable hiding");
            SeedAtlasConfigIO.save(file, config);
            var loaded = SeedAtlasConfigIO.load(file);
            check(loaded.overlayExplored(), "Overlay preference survives reload");
            loaded.setOverlayExplored(false);
            SeedAtlasConfigIO.save(file, loaded);
            check(!SeedAtlasConfigIO.load(file).overlayExplored(), "Overlay off survives reload");
            check(loaded.isCompleted(key), "Completion survives reload");
            check(loaded.opacity() == 180 && loaded.structuresEnabled() && !loaded.markerEnabled("village"), "Preserve existing settings");
            check(loaded.seedProfile("server-a").base().seed() == 123, "Preserve seed profiles");
            dimensionSeeds(file, loaded);
            for (var other : List.of(
                new StructureKey("server-b",123,false,"minecraft:overworld","village",-128,256),
                new StructureKey("server-a",124,false,"minecraft:overworld","village",-128,256),
                new StructureKey("server-a",123,true,"minecraft:overworld","village",-128,256),
                new StructureKey("server-a",123,false,"minecraft:the_nether","village",-128,256),
                new StructureKey("server-a",123,false,"minecraft:overworld","outpost",-128,256),
                new StructureKey("server-a",123,false,"minecraft:overworld","village",128,256),
                new StructureKey("server-a",123,false,"minecraft:overworld","village",-128,269))) {
                check(!loaded.isCompleted(other), "Do not share progress across identities: " + other);
            }
            check(loaded.completedOpacity() == 100, "Completed structures start fully visible");
            check(loaded.setCompletedOpacity(35) && !loaded.hideCompletedStructures(), "Fading is not hiding");
            SeedAtlasConfigIO.save(file, loaded);
            loaded = SeedAtlasConfigIO.load(file);
            check(loaded.completedOpacity() == 35 && loaded.isCompleted(key), "Fade persists independently");
            check(loaded.setCompletedOpacity(-5) && loaded.completedOpacity() == 0
                && loaded.hideCompletedStructures(), "0 % hides completed structures");
            SeedAtlasConfigIO.save(file, loaded);
            loaded = SeedAtlasConfigIO.load(file);
            check(loaded.hideCompletedStructures() && loaded.isCompleted(key), "Filter persists independently");
            // Version 7 only had the hide switch.
            Path legacyHide = directory.resolve("legacy-hide.json");
            Files.writeString(legacyHide, """
                {"version":7,"structures":{"enabled":true,"hideCompleted":true}}
                """);
            check(SeedAtlasConfigIO.load(legacyHide).completedOpacity() == 0, "Hidden completed structures stay hidden");
            Files.delete(legacyHide);
            gameVersions(file, loaded);
            nearbyAndReset(file);
            loaded.setBiomeHighlightEnabled(true);
            loaded.toggleHighlightedBiome(1);
            loaded.toggleHighlightedBiome(27);
            check(loaded.highlightedBiomeCount() == 2 && loaded.isBiomeHighlighted(27), "Highlight selection");
            check(!loaded.highlightSignature().isEmpty(), "An active highlight must invalidate map tiles");
            SeedAtlasConfigIO.save(file, loaded);
            loaded = SeedAtlasConfigIO.load(file);
            check(loaded.biomeHighlightEnabled()
                && loaded.isBiomeHighlighted(1) && loaded.isBiomeHighlighted(27)
                && !loaded.isBiomeHighlighted(2), "Highlight selection survives reload");
            check(loaded.toggleHighlightedBiome(1) && !loaded.isBiomeHighlighted(1), "Unselect a biome");
            check(loaded.clearHighlightedBiomes() && loaded.highlightedBiomeCount() == 0, "Clear the selection");
            check(loaded.highlightSignature().isEmpty(), "A cleared highlight must not invalidate map tiles");
            // These two selections collide under the previous 31-based rolling hash.
            loaded.addHighlightedBiome(1);
            loaded.addHighlightedBiome(32);
            var firstSelection = loaded.highlightSignature();
            loaded.clearHighlightedBiomes();
            loaded.addHighlightedBiome(2);
            loaded.addHighlightedBiome(1);
            var secondSelection = loaded.highlightSignature();
            check(!firstSelection.equals(secondSelection), "Different biome sets must invalidate cached colours");
            loaded.clearHighlightedBiomes();
            loaded.addHighlightedBiome(1);
            loaded.addHighlightedBiome(2);
            check(secondSelection.equals(loaded.highlightSignature()), "Selection order must not rebuild tiles");
            loaded.setBiomeHighlightEnabled(false);
            check(loaded.highlightSignature().isEmpty() && loaded.highlightedBiomeCount() == 2,
                "Disabling restores normal colours but remembers the selection");
            loaded.completedSnapshot().clear();
            check(loaded.isCompleted(key), "Snapshot cannot mutate live progress");
            check(loaded.setCompleted(key, false), "Reopen structure");
            SeedAtlasConfigIO.save(file, loaded);
            check(!SeedAtlasConfigIO.load(file).isCompleted(key), "Reopening survives reload");
            loaded.setCompleted(key, true);
            SeedAtlasConfigIO.save(file, loaded);
            String json = Files.readString(file).replace("\"completed\": [", "\"completed\": [null, {}, {\"seed\":123,\"x\":0,\"z\":0},");
            Files.writeString(file, json);
            check(SeedAtlasConfigIO.load(file).isCompleted(key), "Malformed entries must not lose valid progress");
            System.out.println("Structure progress persistence tests passed");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory.resolve("config.json.tmp"));
            Files.deleteIfExists(directory);
        }
    }

    /** Issue #4: the Nether and End may use their own seeds; the base seed covers the rest. */
    private static void dimensionSeeds(Path file, SeedAtlasConfig config) throws Exception {
        var base = config.seedProfile("server-a");
        check(base.seedFor(Dimension.NETHER).seed() == 123 && base.ownSeed(Dimension.NETHER) == null,
            "Old profiles generate every dimension from their seed");
        config.putSeedProfile("server-a", base.withDimension(Dimension.NETHER, new SeedAtlasConfig.SeedValue("nether text", 77)));
        config.putSeedProfile("server-c", new SeedAtlasConfig.SeedProfile(null, null, new SeedAtlasConfig.SeedValue("9", 9), "C"));
        SeedAtlasConfigIO.save(file, config);
        String json = Files.readString(file);
        check(json.contains("\"value\": 123") && json.contains("\"dimensions\""),
            "The base seed keeps the location older releases read; dimension seeds are additive");
        var loaded = SeedAtlasConfigIO.load(file);
        var a = loaded.seedProfile("server-a");
        check(a.seedFor(Dimension.NETHER).seed() == 77 && a.seedFor(Dimension.NETHER).input().equals("nether text"),
            "Nether seed and its text input survive reload");
        check(a.seedFor(Dimension.OVERWORLD).seed() == 123 && a.seedFor(Dimension.END).seed() == 123,
            "Dimensions without their own seed follow the base seed");
        var c = loaded.seedProfile("server-c");
        check(c != null && c.base() == null && c.seedFor(Dimension.OVERWORLD) == null && c.seedFor(Dimension.END).seed() == 9,
            "A profile may know only one dimension's seed");
        var cleared = a.withDimension(Dimension.NETHER, null);
        check(cleared.ownSeed(Dimension.NETHER) == null && cleared.seedFor(Dimension.NETHER).seed() == 123,
            "Clearing a dimension seed falls back to the base seed");
        check(new SeedAtlasConfig.SeedProfile(null, null, null, "x").isEmpty(), "Empty profiles are recognisable");
        try {
            a.withDimension(Dimension.OVERWORLD, new SeedAtlasConfig.SeedValue("1", 1));
            throw new AssertionError("The Overworld must use the base seed");
        } catch (IllegalArgumentException expected) {
            // The base seed is the Overworld seed.
        }
        config.putSeedProfile("server-a", base);
        config.removeSeedProfile("server-c");
    }

    /** A structure moved by a few blocks, e.g. by another Minecraft version, stays completed. */
    private static void nearbyAndReset(final Path file) throws Exception {
        var config = new SeedAtlasConfig();
        var village = new StructureKey("w", 7, false, "minecraft:overworld", "village", 100, -200);
        check(config.setCompleted(village, true), "Mark a village");
        for (int[] offset : new int[][]{{0, 0}, {12, 0}, {-12, 12}, {5, -9}}) {
            check(config.isCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "village",
                100 + offset[0], -200 + offset[1])), "Moved by a few blocks stays completed: " + offset[0] + "," + offset[1]);
        }
        check(!config.isCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "village", 113, -200)),
            "A structure 13 blocks away is another one");
        check(!config.isCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "slime", 100, -200)),
            "Another type at the same place is not completed");
        var slime = new StructureKey("w", 7, false, "minecraft:overworld", "slime", 0, 0);
        config.setCompleted(slime, true);
        check(!config.isCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "slime", 16, 0)),
            "Neighbouring slime chunks stay apart");
        check(!config.setCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "village", 104, -196), true),
            "Marking the moved village again changes nothing");
        SeedAtlasConfigIO.save(file, config);
        var loaded = SeedAtlasConfigIO.load(file);
        check(loaded.isCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "village", 108, -205)),
            "Tolerance survives a reload");
        check(loaded.setCompleted(new StructureKey("w", 7, false, "minecraft:overworld", "village", 108, -205), false)
            && !loaded.isCompleted(village), "Unmarking the moved structure clears the stored one");
        loaded.setCompleted(village, true);
        loaded.setCompleted(new StructureKey("other", 7, false, "minecraft:overworld", "village", 0, 0), true);
        check(loaded.completedCount("w", null) == 2 && loaded.completedCount("w", "village") == 1,
            "Counts per world and type");
        check(loaded.resetCompleted("w", "slime") == 1 && loaded.isCompleted(village) && !loaded.isCompleted(slime),
            "Resetting one type keeps the others");
        check(loaded.resetCompleted("w", null) == 1 && loaded.completedCount("w", null) == 0, "Reset all of a world");
        check(loaded.completedCount("other", null) == 1, "Other worlds keep their progress");
    }

    /** A server on another Minecraft release keeps its version; unknown names are dropped. */
    private static void gameVersions(final Path file, final SeedAtlasConfig config) throws Exception {
        check(config.gameVersion("server-a") == null, "Contexts follow the running game by default");
        check(config.setGameVersion("server-a", "26.2"), "Choose 26.2 for one server");
        check(!config.setGameVersion("server-a", "26.2"), "Choosing the same version is idempotent");
        SeedAtlasConfigIO.save(file, config);
        var root = Files.readString(file);
        check(root.contains("\"gameVersions\"") && root.contains("\"26.2\""), "Version is written per context");
        Files.writeString(file, root.replace("\"26.2\"", "\"not-a-version\""));
        check(SeedAtlasConfigIO.load(file).gameVersion("server-a") == null, "Unknown versions fall back to automatic");
        SeedAtlasConfigIO.save(file, config);
        var loaded = SeedAtlasConfigIO.load(file);
        check("26.2".equals(loaded.gameVersion("server-a")) && loaded.gameVersion("server-b") == null,
            "Version survives reload for its own context only");
        check(loaded.setGameVersion("server-a", null) && loaded.gameVersion("server-a") == null, "Back to automatic");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

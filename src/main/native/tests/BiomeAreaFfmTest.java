import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.seedatlas.xaero.nativeapi.BiomeRegion;
import org.seedatlas.xaero.nativeapi.BlockBox;
import org.seedatlas.xaero.nativeapi.StructureType;
import org.seedatlas.xaero.nativeapi.BiomeSample;
import org.seedatlas.xaero.nativeapi.BiomeTile;
import org.seedatlas.xaero.nativeapi.Dimension;
import org.seedatlas.xaero.nativeapi.GameVersion;
import org.seedatlas.xaero.nativeapi.NativeException;
import org.seedatlas.xaero.nativeapi.SeedAtlasNative;
import org.seedatlas.xaero.nativeapi.WorldType;

/** Manual Java 25 FFM smoke/concurrency test for native ABI 6 (4/5 accepted with fewer features). */
public final class BiomeAreaFfmTest {
    public static void main(String[] args) throws Exception {
        assert SeedAtlasNative.status().available();
        // Checked-in macOS libraries may still be ABI 4 (biomes only) until CI rebuilds them;
        // SAX_REQUIRE_HEIGHTS=1 (freshly built libraries) demands ABI 6.
        boolean abi6 = SeedAtlasNative.engineVersion().contains("ABI 6");
        assert abi6 || (SeedAtlasNative.engineVersion().matches(".*ABI [45].*")
            && !"1".equals(System.getenv("SAX_REQUIRE_HEIGHTS")));
        assert SeedAtlasNative.engineVersion().contains("26.3") : SeedAtlasNative.engineVersion();
        gameVersions();
        boolean heights;
        try (SeedAtlasNative camp = SeedAtlasNative.open(14L, WorldType.NORMAL)) {
            heights = camp.supportsHeights();
            var special = camp.structures(StructureType.ABANDONED_CAMP,
                new BlockBox(-832, -2128, -832, -2128), 8, true).positions();
            assert special.size() == 1;
            assert special.getFirst().campBiomeId() == 30;
            assert special.getFirst().hasSpecialLoot();
        }
        if (abi6) {
            // Independent 26.3 vanilla-density fixture at the reported player's column:
            // Full cave mode skips the roof from 127, not the biome sampling layer 64.
            try (SeedAtlasNative reported = SeedAtlasNative.open(2842062689604764280L, WorldType.NORMAL)) {
                assert reported.netherFloorArea(-170, 14, 64, 1, 1, 1)[0] == 59.0F;
                assert reported.netherFloorArea(-170, 14, 127, 1, 1, 1)[0] == 100.0F;
                assert reported.netherFloorArea(-170, -18, 64, 1, 1, 1)[0] == 0.0F;
                assert reported.netherFloorArea(-170, -18, 127, 1, 1, 1)[0] == 102.0F;
                assert reported.supportsNetherCarving();
                assert reported.netherFloorArea(-200, 14, 127, 1, 1, 1)[0] == 62.0F;
                long[] carved = {0, (1L << (102 - 64)) | (1L << (103 - 64))};
                assert reported.netherFloorArea(-200, 14, 127, 1, 1, 1, carved)[0] == 101.0F;
                assert reported.netherFloorArea(-200, 14, 127, 1, 1, 1, new long[2])[0] == 62.0F;
                boolean invalidMask = false;
                try {
                    reported.netherFloorArea(-200, 14, 127, 1, 1, 1, new long[1]);
                } catch (IllegalArgumentException expected) {
                    invalidMask = true;
                }
                assert invalidMask;
            }
        }
        try (SeedAtlasNative atlas = SeedAtlasNative.open(8371904829L, WorldType.NORMAL)) {
            assert atlas.supportsHeights() == heights;
            assert heights == SeedAtlasNative.engineVersion().matches(".*ABI [56].*");
            if (heights) {
                float[] area = atlas.heightArea(Dimension.OVERWORLD, -517, 39, 4, 24, 24);
                float[] again = atlas.heightArea(Dimension.OVERWORLD, -517, 39, 4, 24, 24);
                assert area.length == 576 && Arrays.equals(area, again);
                float[] halo = atlas.heightArea(Dimension.OVERWORLD, -517 - 4, 39 - 4, 4, 26, 26);
                for (int hz = 0; hz < 24; hz++) {
                    for (int hx = 0; hx < 24; hx++) {
                        assert halo[(hz + 1) * 26 + hx + 1] == area[hz * 24 + hx];
                    }
                }
                boolean nether = false;
                try {
                    atlas.heightArea(Dimension.NETHER, 0, 0, 4, 4, 4);
                } catch (NativeException expected) {
                    nether = true;
                }
                assert nether;
            }
            if (abi6) {
                assert atlas.supportsNetherFloor();
                float[] nf = atlas.netherFloorArea(-48, 16, 64, 4, 24, 24);
                float[] nfHalo = atlas.netherFloorArea(-52, 12, 64, 4, 26, 26);
                boolean lavaSeen = false, rockSeen = false;
                for (int hz = 0; hz < 24; hz++) {
                    for (int hx = 0; hx < 24; hx++) {
                        assert nfHalo[(hz + 1) * 26 + hx + 1] == nf[hz * 24 + hx];
                        if (nf[hz * 24 + hx] < 31) lavaSeen = true; else rockSeen = true;
                    }
                }
                assert nf.length == 576 && lavaSeen && rockSeen;
                float[] end = atlas.heightArea(Dimension.END, -64, -64, 4, 32, 32);
                float top = 0;
                for (float v : end) top = Math.max(top, v);
                assert end.length == 1024 && top > 30;
                float[] endHalo = atlas.heightArea(Dimension.END, -68, -68, 4, 34, 34);
                for (int hz = 0; hz < 32; hz++) {
                    for (int hx = 0; hx < 32; hx++) {
                        assert endHalo[(hz + 1) * 34 + hx + 1] == end[hz * 32 + hx];
                    }
                }
            }
            var dappled = atlas.biomeAt(Dimension.OVERWORLD, -1184, 100, 1248);
            assert dappled.id() == 188;
            assert dappled.name().equals("minecraft:dappled_forest");
            assert dappled.argb() == 0xffdf6827;
            var camps = atlas.structures(StructureType.ABANDONED_CAMP,
                new BlockBox(-512, 416, -512, 416), 8, true).positions();
            assert camps.size() == 1;
            assert camps.getFirst().campBiomeId() == 4;
            assert !camps.getFirst().hasSpecialLoot();
            var unchecked = atlas.structures(StructureType.ABANDONED_CAMP,
                new BlockBox(288, 32, 288, 32), 8, false).positions();
            assert unchecked.size() == 1;
            assert unchecked.getFirst().campBiomeId() == -1;
            assert !unchecked.getFirst().hasSpecialLoot();
            BiomeRegion exact = atlas.biomeArea(
                Dimension.OVERWORLD, -517, 39, 255, 1, 96, 80);
            for (int z = 0; z < 80; z += 7) {
                for (int x = 0; x < 96; x += 11) {
                    BiomeSample point = atlas.biomeAt(
                        Dimension.OVERWORLD, -517 + x, 255, 39 + z);
                    assert exact.biomeIdAtSample(x, z) == point.id();
                    assert exact.biomeIdAtBlock(-517 + x, 39 + z) == point.id();
                    assert exact.argbAtSample(x, z) == point.argb();
                }
            }

            BiomeRegion sampled = atlas.biomeArea(
                Dimension.OVERWORLD, -517, 39, 255, 2, 48, 40);
            for (int z = 0; z < 40; z += 5) {
                for (int x = 0; x < 48; x += 7) {
                    BiomeSample point = atlas.biomeAt(
                        Dimension.OVERWORLD, -517 + x * 2 + 1,
                        255, 39 + z * 2 + 1);
                    assert sampled.biomeIdAtSample(x, z) == point.id();
                }
            }

            for (Dimension dimension : Dimension.values()) {
                int y = switch (dimension) {
                    case OVERWORLD -> 255;
                    case NETHER -> 64;
                    case END -> 128;
                };
                for (int step = 4; step <= 1024; step *= 2) {
                    BiomeRegion region = atlas.biomeArea(
                        dimension, -4096, 2048, y, step, 32, 24);
                    assert region.sampleStep() == step;
                    assert region.sampleWidth() == 32;
                    assert region.sampleHeight() == 24;
                    assert region.argbAtSample(31, 23) != 0;
                }
            }

            BiomeRegion chunkRegion = atlas.biomeArea(
                Dimension.OVERWORLD, -512, 256, 255, 4, 256, 256);
            BiomeTile tile = chunkRegion.biomeTile(-32, 16);
            int[] copied = new int[BiomeTile.SAMPLE_COUNT];
            chunkRegion.copyChunkArgb(-32, 16, copied);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assert copied[z * 16 + x] == tile.argb(x, z);
                }
            }
            int[] bulk = new int[chunkRegion.sampleWidth()
                * chunkRegion.sampleHeight() + 3];
            chunkRegion.copyArgbSamplesTo(bulk, 3);
            assert bulk[3] == chunkRegion.argbAtSample(0, 0);
            short[] compactIds = new short[chunkRegion.sampleWidth()
                * chunkRegion.sampleHeight()];
            chunkRegion.copyBiomeIdsToUnsignedShorts(compactIds, 0);
            assert Short.toUnsignedInt(compactIds[0]) ==
                chunkRegion.biomeIdAtSample(0, 0);

            boolean rejected = false;
            try {
                atlas.biomeArea(Dimension.OVERWORLD, 1, 0, 255, 4, 16, 16);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assert rejected;

            try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
                List<Callable<Integer>> calls = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    int tileIndex = index;
                    calls.add(() -> atlas.biomeArea(Dimension.OVERWORLD,
                        -65536 + tileIndex * 256, -65536,
                        255, 1, 256, 256).argbAtSample(0, 0));
                }
                for (var result : executor.invokeAll(calls)) {
                    assert result.get() != 0;
                }
            }
        }
        System.out.println("seedatlas_xaero Java FFM tests passed (heights " + (heights ? "on" : "off") + ")");
    }

    /** Servers on an older release: libraries with SAX_CAP_MC_VERSIONS generate any listed version. */
    private static void gameVersions() {
        assert SeedAtlasNative.supports(GameVersion.NEWEST);
        if (!SeedAtlasNative.supports(GameVersion.ALL.getLast())) {
            // Checked-in libraries predating version selection only generate their own release.
            assert !"1".equals(System.getenv("SAX_REQUIRE_HEIGHTS"));
            return;
        }
        for (GameVersion version : GameVersion.ALL) {
            assert SeedAtlasNative.supports(version) : version;
        }
        assert !SeedAtlasNative.supports(new GameVersion("1.0", "1.0"));
        assert GameVersion.parse("1.20.1").orElseThrow().name().equals("1.20.6");
        assert GameVersion.parse("26.1").orElseThrow().label().equals("1.21.5 – 26.1.2");
        assert GameVersion.parse("1.0").isEmpty() && GameVersion.parse("27.1").isEmpty();
        assert !GameVersion.parse("1.17.1").orElseThrow().hasTerrain() && GameVersion.parse("1.18").orElseThrow().hasTerrain();
        BlockBox box = new BlockBox(-4096, -4096, 4096, 4096);
        try (SeedAtlasNative newest = SeedAtlasNative.open(8371904829L, WorldType.NORMAL);
             SeedAtlasNative older = SeedAtlasNative.open(8371904829L, WorldType.NORMAL, GameVersion.parse("1.20").orElseThrow())) {
            assert older.gameVersion().name().equals("1.20.6");
            // Trial Chambers arrived in 1.21: an empty scan for 1.20, not an error.
            assert older.structures(StructureType.TRIAL_CHAMBERS, box, 64, false).positions().isEmpty();
            assert !newest.structures(StructureType.TRIAL_CHAMBERS, box, 64, false).positions().isEmpty();
            assert !older.structures(StructureType.VILLAGE, box, 64, false).positions().isEmpty();
        }
        // Layered generation before 1.18: biomes and structures, no terrain.
        try (SeedAtlasNative layered = SeedAtlasNative.open(8371904829L, WorldType.NORMAL, GameVersion.parse("1.12.2").orElseThrow())) {
            for (int argb : layered.biomeArea(Dimension.OVERWORLD, 0, 0, 64, 8, 32, 32).argbSamples()) assert argb != 0;
            for (int argb : layered.biomeArea(Dimension.OVERWORLD, 0, 0, 64, 1024, 4, 4).argbSamples()) assert argb != 0;
            assert layered.biomeName(3).equals("minecraft:extreme_hills") || layered.biomeName(3).equals("minecraft:mountains");
            boolean noHeights = false;
            try {
                layered.heightArea(Dimension.OVERWORLD, 0, 0, 4, 2, 2);
            } catch (NativeException expected) {
                noHeights = true;
            }
            assert noHeights;
            assert !layered.structures(StructureType.VILLAGE, box, 64, false).positions().isEmpty();
            assert layered.structures(StructureType.ORE_VEIN, box, 64, false).positions().isEmpty();
        }
        boolean rejected = false;
        try (SeedAtlasNative ignored = SeedAtlasNative.open(1L, WorldType.NORMAL, new GameVersion("1.0", "1.0"))) {
            // unreachable
        } catch (NativeException expected) {
            rejected = true;
        }
        assert rejected;
    }
}

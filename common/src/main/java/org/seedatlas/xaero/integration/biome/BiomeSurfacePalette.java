package org.seedatlas.xaero.integration.biome;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

/**
 * Surface colours as Xaero's explored map shows them: each biome's vanilla grass, foliage or water
 * tint times the average brightness of the block texture. Forest biomes get decorative tree crowns
 * in their leaf colour, placed from block coordinates.
 */
final class BiomeSurfacePalette {
    /** Texture brightness of grass_block_top and water_still, offsetting BiomeTerrainStyler's -8% base shade. */
    private static final float GRASS_TEXTURE = 0.62F;
    private static final float WATER_TEXTURE = 0.70F;
    private static final int SNOW = 0xE8EFF0;
    private static final int SAND = 0xD8CF9B;
    private static final int STONE = 0x858585;
    private static final int TERRACOTTA = 0xB06B48;
    private static final int MYCELIUM = 0x7A6D72;
    private static final int MUD = 0x514B40;
    private static final int CROWN_CELL = 5;
    private static final long CROWN_SALT = 0x5EED_A71A5L;

    /** Leaf tint source: a fixed vanilla tint/texture colour, or the biome's own foliage colour. */
    private record Canopy(int fixedColor, float texture, float density) {
        static Canopy foliage(float texture, float density) {
            return new Canopy(-1, texture, density);
        }
    }

    private static final Map<ResourceKey<Biome>, Canopy> CANOPIES = Map.ofEntries(
        // Texture factors are the average brightness of each leaf texture (oak 0.565, birch 0.51, ...).
        Map.entry(Biomes.FOREST, Canopy.foliage(0.60F, 0.55F)),
        Map.entry(Biomes.FLOWER_FOREST, Canopy.foliage(0.60F, 0.35F)),
        Map.entry(Biomes.DAPPLED_FOREST, Canopy.foliage(0.60F, 0.45F)),
        Map.entry(Biomes.WINDSWEPT_FOREST, Canopy.foliage(0.55F, 0.45F)),
        Map.entry(Biomes.DARK_FOREST, Canopy.foliage(0.50F, 0.90F)),
        Map.entry(Biomes.BIRCH_FOREST, new Canopy(FoliageColor.FOLIAGE_BIRCH & 0xFFFFFF, 0.62F, 0.55F)),
        Map.entry(Biomes.OLD_GROWTH_BIRCH_FOREST, new Canopy(FoliageColor.FOLIAGE_BIRCH & 0xFFFFFF, 0.62F, 0.60F)),
        Map.entry(Biomes.TAIGA, new Canopy(FoliageColor.FOLIAGE_EVERGREEN & 0xFFFFFF, 0.42F, 0.55F)),
        Map.entry(Biomes.OLD_GROWTH_PINE_TAIGA, new Canopy(FoliageColor.FOLIAGE_EVERGREEN & 0xFFFFFF, 0.42F, 0.70F)),
        Map.entry(Biomes.OLD_GROWTH_SPRUCE_TAIGA, new Canopy(FoliageColor.FOLIAGE_EVERGREEN & 0xFFFFFF, 0.42F, 0.75F)),
        Map.entry(Biomes.JUNGLE, Canopy.foliage(0.62F, 0.85F)),
        Map.entry(Biomes.BAMBOO_JUNGLE, Canopy.foliage(0.62F, 0.75F)),
        Map.entry(Biomes.SPARSE_JUNGLE, Canopy.foliage(0.62F, 0.30F)),
        Map.entry(Biomes.SAVANNA, Canopy.foliage(0.62F, 0.12F)),
        Map.entry(Biomes.SAVANNA_PLATEAU, Canopy.foliage(0.62F, 0.15F)),
        Map.entry(Biomes.WINDSWEPT_SAVANNA, Canopy.foliage(0.62F, 0.08F)),
        Map.entry(Biomes.WOODED_BADLANDS, Canopy.foliage(0.60F, 0.25F)),
        Map.entry(Biomes.SWAMP, Canopy.foliage(0.60F, 0.15F)),
        Map.entry(Biomes.MANGROVE_SWAMP, new Canopy(FoliageColor.FOLIAGE_MANGROVE & 0xFFFFFF, 0.55F, 0.75F)),
        // Cherry and pale oak leaves are not tinted: these are their texture averages.
        Map.entry(Biomes.CHERRY_GROVE, new Canopy(0xE5ADC2, 0.95F, 0.50F)),
        Map.entry(Biomes.PALE_GARDEN, new Canopy(0x757A73, 1.05F, 0.85F))
    );

    /** Grass/foliage colormap corners (hot-dry, hot-wet, cold), used only before resources load. */
    private static final int[] GRASS_CORNERS = {0xBFB755, 0x47CD33, 0x80B497};
    private static final int[] FOLIAGE_CORNERS = {0xAEA42A, 0x1ABF00, 0x60A17B};
    private static final Map<Biome, Float> DOWNFALL = new ConcurrentHashMap<>();

    private BiomeSurfacePalette() { }

    /** Top colour of a base-terrain column at one sample; {@code step} selects crown dots or blending. */
    static int surfaceColor(Holder<Biome> biome, int x, int y, int z, boolean fluid, int seaLevel, int step) {
        int ground = ground(biome, x, y, z, fluid, seaLevel);
        // Snow layers also cover leaves, so snowy forests read as snow like on Xaero's map.
        Canopy canopy = fluid || ground == SNOW ? null : biome.unwrapKey().map(CANOPIES::get).orElse(null);
        if (canopy == null) return ground;
        return crowns(ground, leaf(biome), canopy.density, x, z, step);
    }

    /** Colour of the visible block between trees: water, snow, sand, stone or tinted grass. */
    static int ground(Holder<Biome> biome, int x, int y, int z, boolean fluid, int seaLevel) {
        Biome value = biome.value();
        if (fluid) return water(value.getWaterColor());
        // Vanilla surface rules need a chunk, so the top material is inferred from the biome.
        if (value.coldEnoughToSnow(new BlockPos(x, y, z), seaLevel)) return SNOW;
        if (biome.is(Biomes.DESERT) || biome.is(Biomes.BEACH)) return SAND;
        if (biome.is(Biomes.STONY_SHORE) || biome.is(Biomes.STONY_PEAKS)) return STONE;
        if (biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS)
            || biome.is(Biomes.WOODED_BADLANDS)) return TERRACOTTA;
        if (biome.is(Biomes.MUSHROOM_FIELDS)) return MYCELIUM;
        if (biome.is(Biomes.MANGROVE_SWAMP)) return MUD;
        return grass(grassColor(value, x, z));
    }

    /** Leaf colour of a forest biome's crowns, or -1 for biomes drawn without trees. */
    static int leaf(Holder<Biome> biome) {
        Canopy canopy = biome.unwrapKey().map(CANOPIES::get).orElse(null);
        if (canopy == null) return -1;
        int tint = canopy.fixedColor >= 0 ? canopy.fixedColor : foliageColor(biome.value());
        return scale(tint, canopy.texture);
    }

    static int grass(int tint) {
        return scale(tint, GRASS_TEXTURE);
    }

    static int water(int tint) {
        return scale(tint, WATER_TEXTURE);
    }

    /**
     * Tree crowns of radius 1.8..2.7 on a jittered 5-block grid, lit from the north-west like the
     * relief. Samples of 8+ blocks blend ground and leaves by coverage so distant forests stay calm.
     */
    static int crowns(int ground, int leaf, float density, int x, int z, int step) {
        if (step >= 8) return mix(ground, leaf, density);
        int cellX = Math.floorDiv(x, CROWN_CELL);
        int cellZ = Math.floorDiv(z, CROWN_CELL);
        // A crown covers about 0.6 of its cell, so this probability yields the requested coverage.
        int threshold = Math.round(Math.min(1.0F, density / 0.6F) * 1000.0F);
        for (int dz = -1; dz <= 1; ++dz) {
            for (int dx = -1; dx <= 1; ++dx) {
                long hash = BiomeTerrainStyler.hash(cellX + dx, cellZ + dz, CROWN_SALT);
                if (hash % 1000 >= threshold) continue;
                float centreX = (cellX + dx) * CROWN_CELL + 0.5F + (hash >>> 12) % CROWN_CELL;
                float centreZ = (cellZ + dz) * CROWN_CELL + 0.5F + (hash >>> 20) % CROWN_CELL;
                float radius = 1.8F + ((hash >>> 28) % 10) / 10.0F;
                float offsetX = x + 0.5F - centreX;
                float offsetZ = z + 0.5F - centreZ;
                if (offsetX * offsetX + offsetZ * offsetZ > radius * radius) continue;
                // North-west half of a crown faces the light.
                int light = Math.round(-(offsetX + offsetZ) / radius * 9.0F);
                return scale(leaf, 1.0F + light / 100.0F);
            }
        }
        return ground;
    }

    private static int grassColor(Biome biome, int x, int z) {
        // The client colormaps are what Xaero tints explored grass with; before they load
        // (or in tests), fall back to the same triangle the colormap texture encodes.
        if (GrassColor.get(0.5, 1.0) != 0) return biome.getGrassColor(x, z) & 0xFFFFFF;
        var effects = biome.getSpecialEffects();
        int base = effects.grassColorOverride()
            .orElseGet(() -> colormap(GRASS_CORNERS, biome.getBaseTemperature(), downfall(biome)));
        return effects.grassColorModifier().modifyColor(x, z, base) & 0xFFFFFF;
    }

    private static int foliageColor(Biome biome) {
        if (FoliageColor.get(0.5, 1.0) != 0) return biome.getFoliageColor() & 0xFFFFFF;
        return biome.getSpecialEffects().foliageColorOverride()
            .orElseGet(() -> colormap(FOLIAGE_CORNERS, biome.getBaseTemperature(), downfall(biome))) & 0xFFFFFF;
    }

    /** Barycentric form of vanilla's colormap lookup (x = 1 - temperature, y = 1 - downfall * temperature). */
    static int colormap(int[] corners, float temperature, float downfall) {
        float t = Math.clamp(temperature, 0.0F, 1.0F);
        float d = Math.clamp(downfall, 0.0F, 1.0F) * t;
        float hotDry = t - d;
        float hotWet = d;
        float cold = 1.0F - t;
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float channel = hotDry * (corners[0] >> shift & 0xFF) + hotWet * (corners[1] >> shift & 0xFF)
                + cold * (corners[2] >> shift & 0xFF);
            rgb |= Math.clamp(Math.round(channel), 0, 255) << shift;
        }
        return rgb;
    }

    /** Biome exposes no downfall getter; its network codec carries the climate settings. */
    private static float downfall(Biome biome) {
        return DOWNFALL.computeIfAbsent(biome, value -> {
            try {
                return Biome.NETWORK_CODEC.encodeStart(JsonOps.INSTANCE, value).result()
                    .filter(JsonElement::isJsonObject)
                    .map(json -> json.getAsJsonObject().get("downfall"))
                    .map(JsonElement::getAsFloat)
                    .orElse(0.5F);
            } catch (RuntimeException ignored) {
                return 0.5F;
            }
        });
    }

    static int scale(int rgb, float factor) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            result |= Math.clamp(Math.round((rgb >> shift & 0xFF) * factor), 0, 255) << shift;
        }
        return result;
    }

    static int mix(int from, int to, float amount) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            float a = from >> shift & 0xFF;
            float b = to >> shift & 0xFF;
            result |= Math.clamp(Math.round(a + (b - a) * amount), 0, 255) << shift;
        }
        return result;
    }
}

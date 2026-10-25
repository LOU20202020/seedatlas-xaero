package org.seedatlas.xaero.detail;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * The engine reports biomes as display names ("Badlands Plateau"), which never matched
 * Minecraft's translation keys ("biome.minecraft.badlands_plateau"). Normalise first, and
 * drop biome ids this game version does not know about (they are legacy 1.17 names).
 *
 * <p>Built through a HashMap on purpose: {@code Map.ofEntries} throws on duplicate keys and
 * that runs inside a static initialiser, which took the whole map screen down once already.</p>
 */
public final class BiomeNames {
    private static final Map<String, String> LEGACY = new HashMap<>();

    static {
        // 1.17-era names that current versions merged or removed.
        LEGACY.put("badlands_plateau", "恶地高原");
        LEGACY.put("wooded_badlands_plateau", "疏林恶地高原");
        LEGACY.put("modified_badlands_plateau", "变种恶地高原");
        LEGACY.put("modified_wooded_badlands_plateau", "变种疏林恶地高原");
        LEGACY.put("bamboo_jungle_hills", "竹林丘陵");
        LEGACY.put("birch_forest_hills", "桦木森林丘陵");
        LEGACY.put("dark_forest_hills", "黑森林丘陵");
        LEGACY.put("taiga_hills", "针叶林丘陵");
        LEGACY.put("taiga_mountains", "针叶林山地");
        LEGACY.put("snowy_taiga_hills", "积雪针叶林丘陵");
        LEGACY.put("snowy_taiga_mountains", "积雪针叶林山地");
        LEGACY.put("giant_tree_taiga", "巨型针叶林");
        LEGACY.put("giant_tree_taiga_hills", "巨型针叶林丘陵");
        LEGACY.put("giant_spruce_taiga", "巨型云杉针叶林");
        LEGACY.put("giant_spruce_taiga_hills", "巨型云杉针叶林丘陵");
        LEGACY.put("jungle_hills", "丛林丘陵");
        LEGACY.put("jungle_edge", "丛林边缘");
        LEGACY.put("modified_jungle", "变种丛林");
        LEGACY.put("modified_jungle_edge", "变种丛林边缘");
        LEGACY.put("desert_hills", "沙漠丘陵");
        LEGACY.put("desert_lakes", "沙漠湖泊");
        LEGACY.put("wooded_hills", "疏林丘陵");
        LEGACY.put("tall_birch_forest", "高桦木森林");
        LEGACY.put("tall_birch_hills", "高桦木丘陵");
        LEGACY.put("mountains", "山地");
        LEGACY.put("wooded_mountains", "疏林山地");
        LEGACY.put("gravelly_mountains", "沙砾山地");
        LEGACY.put("mountain_edge", "山地边缘");
        LEGACY.put("shattered_savanna", "破碎热带草原");
        LEGACY.put("shattered_savanna_plateau", "破碎热带高原");
        LEGACY.put("snowy_mountains", "积雪山地");
        LEGACY.put("swamp_hills", "沼泽丘陵");
        LEGACY.put("stone_shore", "石岸");
        LEGACY.put("mushroom_field_shore", "蘑菇岛岸");
        LEGACY.put("snowy_tundra", "积雪的冻原");
    }

    private BiomeNames() {}

    /** "Badlands Plateau" / "minecraft:badlands_plateau" -> "badlands_plateau". */
    public static String snake(final String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        int colon = value.indexOf(':');
        if (colon > 0) value = value.substring(colon + 1);
        return value.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    /** Chinese name for legacy ids the game dropped, otherwise null. */
    public static String legacyChinese(final String snake) {
        return LEGACY.get(snake);
    }

    /** True when this game version actually has the biome registered. */
    public static boolean existsInGame(final String raw) {
        String path = snake(raw);
        if (path.isEmpty()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return true; // no world yet: keep everything
        try {
            ResourceKey<Biome> key = ResourceKey.create(
                Registries.BIOME, Identifier.fromNamespaceAndPath("minecraft", path));
            return client.level.registryAccess().lookupOrThrow(Registries.BIOME).get(key).isPresent();
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}

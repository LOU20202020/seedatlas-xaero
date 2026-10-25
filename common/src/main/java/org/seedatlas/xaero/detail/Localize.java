package org.seedatlas.xaero.detail;

import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Chinese labels for the loot panel: items come from the game's own translations. */
public final class Localize {
    private static final Map<String, String> TABLES = Map.ofEntries(
        Map.entry("stronghold_library", "要塞图书馆"),
        Map.entry("stronghold_corridor", "要塞走廊"),
        Map.entry("stronghold_crossing", "要塞十字路口"),
        Map.entry("stronghold_portal", "要塞传送门室"),
        Map.entry("desert_pyramid", "沙漠神殿"),
        Map.entry("jungle_temple", "丛林神庙"),
        Map.entry("jungle_temple_dispenser", "丛林神庙发射器"),
        Map.entry("shipwreck_treasure", "沉船宝藏箱"),
        Map.entry("shipwreck_supply", "沉船补给箱"),
        Map.entry("shipwreck_map", "沉船地图箱"),
        Map.entry("pillager_outpost", "掠夺者前哨站"),
        Map.entry("ruined_portal", "废弃传送门"),
        Map.entry("buried_treasure", "埋藏的宝藏"),
        Map.entry("nether_bridge", "下界要塞"),
        Map.entry("bastion_bridge", "堡垒遗迹桥梁箱"),
        Map.entry("bastion_other", "堡垒遗迹普通箱"),
        Map.entry("bastion_treasure", "堡垒遗迹藏宝箱"),
        Map.entry("end_city_treasure", "末地城宝藏"),
        Map.entry("igloo_chest", "雪屋地下室"),
        Map.entry("abandoned_camp_common_chest", "废弃营地普通箱"),
        Map.entry("abandoned_camp_secret_chest", "废弃营地隐藏箱"),
        Map.entry("abandoned_camp_barrel", "废弃营地木桶")
    );

    private static final Map<String, String> ENCHANTS = Map.ofEntries(
        Map.entry("protection", "保护"),
        Map.entry("fire_protection", "火焰保护"),
        Map.entry("feather_falling", "摔落保护"),
        Map.entry("blast_protection", "爆炸保护"),
        Map.entry("projectile_protection", "弹射物保护"),
        Map.entry("respiration", "水下呼吸"),
        Map.entry("aqua_affinity", "水下速掘"),
        Map.entry("thorns", "荆棘"),
        Map.entry("depth_strider", "深海探索者"),
        Map.entry("frost_walker", "冰霜行者"),
        Map.entry("binding_curse", "绑定诅咒"),
        Map.entry("soul_speed", "灵魂疾行"),
        Map.entry("swift_sneak", "迅捷潜行"),
        Map.entry("sharpness", "锋利"),
        Map.entry("smite", "亡灵杀手"),
        Map.entry("bane_of_arthropods", "节肢杀手"),
        Map.entry("knockback", "击退"),
        Map.entry("fire_aspect", "火焰附加"),
        Map.entry("looting", "抢夺"),
        Map.entry("sweeping_edge", "横扫之刃"),
        Map.entry("efficiency", "效率"),
        Map.entry("silk_touch", "精准采集"),
        Map.entry("unbreaking", "耐久"),
        Map.entry("fortune", "时运"),
        Map.entry("power", "力量"),
        Map.entry("punch", "冲击"),
        Map.entry("flame", "火矢"),
        Map.entry("infinity", "无限"),
        Map.entry("luck_of_the_sea", "海之眷顾"),
        Map.entry("lure", "饵钓"),
        Map.entry("loyalty", "忠诚"),
        Map.entry("impaling", "穿刺"),
        Map.entry("riptide", "激流"),
        Map.entry("channeling", "引雷"),
        Map.entry("multishot", "多重射击"),
        Map.entry("quick_charge", "快速装填"),
        Map.entry("piercing", "穿透"),
        Map.entry("mending", "经验修补"),
        Map.entry("vanishing_curse", "消失诅咒"),
        Map.entry("density", "致密"),
        Map.entry("breach", "破甲"),
        Map.entry("wind_burst", "风爆")
    );

    private Localize() {}

    /** Item id ("minecraft:diamond_sword") to its in-game Chinese name. */
    public static String item(String rawId) {
        try {
            Identifier id = Identifier.tryParse(rawId);
            if (id != null) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null) {
                    String name = item.getName(new ItemStack(item)).getString();
                    if (!name.isBlank() && !name.equals(rawId)) return name;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the id
        }
        int colon = rawId.indexOf(':');
        return (colon >= 0 ? rawId.substring(colon + 1) : rawId).replace('_', ' ');
    }

    /** Loot table path ("stronghold_library") to a readable Chinese label. */
    public static String table(String path) {
        String key = path;
        int slash = key.lastIndexOf('/');
        if (slash >= 0) key = key.substring(slash + 1);
        return TABLES.getOrDefault(key, key.replace('_', ' '));
    }

    /** Enchantment id from cubiomes ("sharpness") to Chinese. */
    public static String enchant(String id) {
        return ENCHANTS.getOrDefault(id, id.replace('_', ' '));
    }
}

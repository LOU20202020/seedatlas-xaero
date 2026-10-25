package org.seedatlas.xaero.detail;

import org.seedatlas.xaero.config.MarkerType;

/**
 * Maps a Seed Atlas marker type onto the cubiomes structure type used for piece and loot
 * prediction. Returns null when the native engine has no loot salt for that structure
 * (currently witch huts and abandoned camps), which greys the menu entry out.
 */
public final class StructureTypes {
    /** cubiomes StructureType id, its dimension (-1 nether, 0 overworld, 1 end), display name, region size (chunks). */
    public record Mapping(int stype, int dimension, String label, int regionSize) {}

    private StructureTypes() {}

    public static Mapping of(MarkerType type) {
        if (type == null) return null;
        return switch (type) {
            case DESERT_PYRAMID -> new Mapping(1, 0, "沙漠神殿", 32);
            case JUNGLE_TEMPLE -> new Mapping(2, 0, "丛林神庙", 32);
            case IGLOO -> new Mapping(4, 0, "雪屋", 32);
            case SHIPWRECK -> new Mapping(7, 0, "沉船", 24);
            case PILLAGER_OUTPOST -> new Mapping(10, 0, "掠夺者前哨站", 32);
            case RUINED_PORTAL -> new Mapping(11, 0, "废弃传送门", 40);
            case BURIED_TREASURE -> new Mapping(14, 0, "埋藏的宝藏", 1);
            case NETHER_FORTRESS -> new Mapping(18, -1, "下界要塞", 27);
            case BASTION -> new Mapping(19, -1, "堡垒遗迹", 27);
            case END_CITY -> new Mapping(21, 1, "末地城", 20);
            case END_SHIP -> new Mapping(21, 1, "末地船", 20);
            case STRONGHOLD -> new Mapping(27, 0, "要塞", 1);
            default -> null;
        };
    }
}

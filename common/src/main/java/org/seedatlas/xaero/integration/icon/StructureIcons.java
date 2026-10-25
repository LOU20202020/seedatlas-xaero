package org.seedatlas.xaero.integration.icon;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.seedatlas.xaero.config.MarkerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marker icons. Most show a Minecraft block, item or mob head, read from the game's own (or a
 * resource pack's) textures at runtime and never bundled; heads and the chest are composed from
 * entity textures. The rest are Seed Atlas pixel art. Each icon is measured once per reload, and
 * map and settings share the same geometry.
 */
public final class StructureIcons {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureIcons.class);
    private static final Map<String, Source> SOURCES = sources();
    private static final Map<String, Icon> DEFAULTS = defaults();
    private static volatile Map<String, Icon> icons = DEFAULTS;

    private StructureIcons() { }

    /** {@code block} icons are full squares and get a dark frame on the map. */
    public record Icon(Identifier texture, IconLayout layout, boolean block) { }

    /** Builds an icon from game textures; {@code null} when a texture is missing. */
    @FunctionalInterface
    private interface Composer {
        NativeImage compose(ResourceManager resources) throws IOException;
    }

    private record Source(Identifier texture, boolean block, Composer composer) {
        static Source own(String id) {
            return new Source(Identifier.fromNamespaceAndPath("seedatlas_xaero", "textures/structure/" + id + ".png"), false, null);
        }

        static Source item(String name) {
            return new Source(Identifier.withDefaultNamespace("textures/item/" + name + ".png"), false, null);
        }

        static Source block(String name, boolean frame) {
            return new Source(Identifier.withDefaultNamespace("textures/block/" + name + ".png"), frame, null);
        }

        static Source composed(String name, boolean frame, Composer composer) {
            return new Source(Identifier.fromNamespaceAndPath("seedatlas_xaero", "dynamic/" + name), frame, composer);
        }
    }

    private static Map<String, Source> sources() {
        Map<String, Source> result = new HashMap<>();
        result.put("camp", Source.own("camp"));
        result.put("camp_special", Source.own("camp_special"));
        result.put("ancient_city", Source.composed("warden_head", false, StructureIcons::wardenHead));
        result.put("bastion", Source.block("cracked_polished_blackstone_bricks", true));
        result.put("chambers", Source.item("trial_key"));
        result.put("desert", Source.block("chiseled_sandstone", true));
        result.put("endcity", Source.block("purpur_block", true));
        result.put("end_ship", Source.item("elytra"));
        result.put("fortress", Source.block("nether_bricks", true));
        result.put("gateway", Source.item("ender_pearl"));
        result.put("geode", Source.item("amethyst_shard"));
        result.put("hut", Source.own("hut"));
        result.put("igloo", Source.own("igloo"));
        result.put("jungle", Source.block("mossy_cobblestone", true));
        result.put("mansion", Source.item("totem_of_undying"));
        // The rail texture is transparent between its sleepers: no frame, so the map shows through.
        result.put("mineshaft", Source.block("rail", false));
        result.put("monument", Source.block("prismarine_bricks", true));
        result.put("orevein", Source.item("raw_iron"));
        result.put("orevein_copper", Source.item("raw_copper"));
        result.put("outpost", Source.item("crossbow_standby"));
        result.put("portal", Source.block("crying_obsidian", true));
        result.put("ruins", Source.composed("cracked_mossy_stone_bricks", true, StructureIcons::crackedMossyStoneBricks));
        result.put("shipwreck", Source.own("shipwreck"));
        result.put("slime", Source.item("slime_ball"));
        result.put("spawn", Source.item("compass_00"));
        result.put("stronghold", Source.item("ender_eye"));
        result.put("trails", Source.item("brush"));
        result.put("treasure", Source.composed("chest", false, StructureIcons::chest));
        result.put("village", Source.composed("villager_head", false, StructureIcons::villagerHead));
        result.put("well", Source.own("well"));
        for (MarkerType type : MarkerType.all()) {
            if (!result.containsKey(type.id())) throw new IllegalStateException("No icon for " + type.id());
        }
        return Map.copyOf(result);
    }

    private static Map<String, Icon> defaults() {
        Map<String, Icon> result = new HashMap<>();
        SOURCES.forEach((id, source) -> result.put(id,
            new Icon(source.texture(), new IconLayout(16, 16, 0, 0, 16, 16), source.block())));
        return Map.copyOf(result);
    }

    public static Icon get(String id) {
        return icons.getOrDefault(id, DEFAULTS.get("camp"));
    }

    public static PreparableReloadListener reloadListener() {
        return new SimplePreparableReloadListener<Prepared>() {
                @Override
                protected Prepared prepare(ResourceManager resources, ProfilerFiller profiler) {
                    Map<String, Icon> loaded = new HashMap<>();
                    Map<Identifier, NativeImage> composed = new HashMap<>();
                    SOURCES.forEach((id, source) -> {
                        try {
                            if (source.composer() != null) {
                                NativeImage image = source.composer().compose(resources);
                                composed.put(source.texture(), image);
                                loaded.put(id, new Icon(source.texture(), measure(image), source.block()));
                            } else {
                                try (var stream = resources.open(source.texture());
                                     var image = NativeImage.read(stream)) {
                                    loaded.put(id, new Icon(source.texture(), measure(image), source.block()));
                                }
                            }
                        } catch (IOException | RuntimeException exception) {
                            LOGGER.warn("Could not load structure icon {}", id, exception);
                            loaded.put(id, DEFAULTS.get("camp"));
                        }
                    });
                    return new Prepared(Map.copyOf(loaded), Map.copyOf(composed));
                }

                @Override
                protected void apply(Prepared prepared, ResourceManager resources, ProfilerFiller profiler) {
                    var textures = Minecraft.getInstance().getTextureManager();
                    prepared.composed().forEach((id, image) ->
                        textures.register(id, new DynamicTexture(() -> "Seed Atlas icon " + id, image)));
                    icons = prepared.icons();
                }
            };
    }

    private record Prepared(Map<String, Icon> icons, Map<Identifier, NativeImage> composed) { }

    /** Animated textures stack their frames vertically; the icon shows the first one. */
    private static IconLayout measure(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight() > width && image.getHeight() % width == 0 ? width : image.getHeight();
        IconLayout frame = IconLayout.measure(width, height, image::getPixel);
        return new IconLayout(width, image.getHeight(), frame.u(), frame.v(), frame.width(), frame.height());
    }

    /** Cracked stone bricks, with every pixel where mossy stone bricks differ from stone bricks. */
    private static NativeImage crackedMossyStoneBricks(ResourceManager resources) throws IOException {
        try (var cracked = read(resources, "block/cracked_stone_bricks");
             var mossy = read(resources, "block/mossy_stone_bricks");
             var plain = read(resources, "block/stone_bricks")) {
            int size = cracked.getWidth();
            NativeImage result = new NativeImage(size, size, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = cracked.getPixel(x, y);
                    if (mossy.getWidth() == size && plain.getWidth() == size
                        && mossy.getPixel(x, y) != plain.getPixel(x, y)) {
                        pixel = mossy.getPixel(x, y);
                    }
                    result.setPixel(x, y, pixel);
                }
            }
            return result;
        }
    }

    /** The villager's face (head front 8x10) with its nose (front 2x4) over the lower face. */
    private static NativeImage villagerHead(ResourceManager resources) throws IOException {
        try (var skin = read(resources, "entity/villager/villager")) {
            int k = Math.max(1, skin.getWidth() / 64);
            NativeImage result = new NativeImage(8 * k, 11 * k, true);
            copy(skin, 8, 8, 8, 10, k, false, result, 0, 0);
            copy(skin, 26, 2, 2, 4, k, false, result, 3, 7);
            return result;
        }
    }

    /** The warden's face (head front 16x16) with both tendrils hooking out of its upper corners. */
    private static NativeImage wardenHead(ResourceManager resources) throws IOException {
        try (var skin = read(resources, "entity/warden/warden")) {
            int k = Math.max(1, skin.getWidth() / 128);
            NativeImage result = new NativeImage(28 * k, 20 * k, true);
            copy(skin, 10, 42, 16, 16, k, false, result, 6, 4);
            copy(skin, 58, 38, 10, 10, k, false, result, 0, 0);
            copy(skin, 58, 6, 10, 10, k, false, result, 18, 0);
            return result;
        }
    }

    /** A chest seen from the front: lid and base fronts with the latch over their seam. */
    private static NativeImage chest(ResourceManager resources) throws IOException {
        try (var skin = read(resources, "entity/chest/normal")) {
            int k = Math.max(1, skin.getWidth() / 64);
            NativeImage result = new NativeImage(14 * k, 15 * k, true);
            copy(skin, 42, 14, 14, 5, k, true, result, 0, 0);
            copy(skin, 42, 33, 14, 10, k, true, result, 0, 5);
            copy(skin, 1, 1, 2, 4, k, false, result, 6, 3);
            return result;
        }
    }

    /** Copies a w x h rectangle in 1/k texture units, skipping transparent pixels so overlays layer. */
    private static void copy(NativeImage source, int u, int v, int w, int h, int k, boolean flip,
                             NativeImage target, int x, int y) {
        for (int dy = 0; dy < h * k; dy++) {
            int sy = v * k + (flip ? h * k - 1 - dy : dy);
            for (int dx = 0; dx < w * k; dx++) {
                int pixel = source.getPixel(u * k + dx, sy);
                if ((pixel >>> 24) != 0) target.setPixel(x * k + dx, y * k + dy, pixel);
            }
        }
    }

    private static NativeImage read(ResourceManager resources, String path) throws IOException {
        try (var stream = resources.open(Identifier.withDefaultNamespace("textures/" + path + ".png"))) {
            return NativeImage.read(stream);
        }
    }
}

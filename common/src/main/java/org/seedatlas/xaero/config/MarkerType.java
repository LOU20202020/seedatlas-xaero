package org.seedatlas.xaero.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.network.chat.Component;

/**
 * Marker types supported by the bundled Seed Atlas icon set.
 *
 * <p>The ids deliberately match the texture file names and the ids exchanged
 * with the native/integration layer.</p>
 */
public enum MarkerType {
	ABANDONED_CAMP("camp", false),
	ANCIENT_CITY("ancient_city", false),
	BASTION("bastion", false),
	TRIAL_CHAMBERS("chambers", false),
	DESERT_PYRAMID("desert", false),
	END_CITY("endcity", false),
	END_SHIP("end_ship", false),
	NETHER_FORTRESS("fortress", false),
	END_GATEWAY("gateway", false),
	GEODE("geode", true),
	SWAMP_HUT("hut", false),
	IGLOO("igloo", false),
	JUNGLE_TEMPLE("jungle", false),
	WOODLAND_MANSION("mansion", false),
	MINESHAFT("mineshaft", false),
	OCEAN_MONUMENT("monument", false),
	ORE_VEIN("orevein", true),
	PILLAGER_OUTPOST("outpost", false),
	RUINED_PORTAL("portal", false),
	OCEAN_RUINS("ruins", false),
	SHIPWRECK("shipwreck", false),
	SLIME_CHUNK("slime", true),
	SPAWN("spawn", true),
	STRONGHOLD("stronghold", false),
	TRAIL_RUINS("trails", false),
	BURIED_TREASURE("treasure", false),
	VILLAGE("village", false),
	DESERT_WELL("well", true);

	private static final List<MarkerType> VALUES = List.copyOf(Arrays.asList(values()));

	private final String id;
	private final boolean helper;

	MarkerType(final String id, final boolean helper) {
		this.id = id;
		this.helper = helper;
	}

	public String id() {
		return this.id;
	}

	public boolean helper() {
		return this.helper;
	}

	public boolean enabledByDefault() {
		return !this.helper;
	}

	public Component displayName() {
		return Component.translatable("marker.seedatlas_xaero." + this.id);
	}

	public static List<MarkerType> all() {
		return VALUES;
	}

	public static Optional<MarkerType> byId(final String id) {
		if (id == null) {
			return Optional.empty();
		}

		String normalized = id.trim().toLowerCase(Locale.ROOT);
		return VALUES.stream().filter(type -> type.id.equals(normalized)).findFirst();
	}
}

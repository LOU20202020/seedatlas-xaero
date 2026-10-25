package org.seedatlas.xaero.compat;

import java.lang.reflect.Field;
import java.util.Set;

import org.seedatlas.xaero.config.SeedAtlasClientState;

/**
 * Read-only view of SeedCrackerX (MIT) plus the seed hand-off. That mod is optional: nothing here
 * links against its classes at compile time except the Fabric bridge, so the jar can be removed
 * without breaking Seed Atlas.
 */
public final class SeedCrackerSupport {
    /** What SeedCrackerX wants before it starts cracking, from its README (1.18+). */
    public static final int BASE_TARGET = 32;
    public static final int LIFT_TARGET = 40;

    private static Boolean present;
    private static boolean configured;
    private static Status cached;
    private static long cachedAt;

    private SeedCrackerSupport() { }

    public record Status(double base, double lifting, boolean cracking, boolean solved) {
        public boolean hasBase() {
            return this.base >= BASE_TARGET;
        }

        public boolean hasLifting() {
            return this.lifting >= LIFT_TARGET;
        }
    }

    /** True when SeedCrackerX is installed and can supply a cracked seed. */
    public static boolean available() {
        if (present == null) {
            try {
                Class.forName("kaptainwutax.seedcrackerX.api.SeedCrackerAPI", false,
                    SeedCrackerSupport.class.getClassLoader());
                present = Boolean.TRUE;
            } catch (Throwable missing) {
                present = Boolean.FALSE;
            }
        }
        return present;
    }

    /** Collected bits and cracker state, cached for a fraction of a second; zeros when absent. */
    public static Status status() {
        if (!available()) {
            return new Status(0, 0, false, false);
        }
        configure();
        long now = System.currentTimeMillis();
        if (cached == null || now - cachedAt > 400L) {
            cachedAt = now;
            cached = read();
        }
        return cached;
    }

    public static void clearCollected() {
        try {
            Object storage = storage();
            if (storage != null) {
                storage.getClass().getMethod("clear").invoke(storage);
            }
            cached = null;
        } catch (Throwable ignored) {
            // SeedCrackerX is not in a state where its data can be cleared.
        }
    }

    /** A cracked seed becomes the active world's base seed, exactly like typing it in the field. */
    public static void apply(final long seed) {
        try {
            SeedAtlasClientState.setSeed(Long.toString(seed));
        } catch (RuntimeException noContext) {
            // Nothing to attach the seed to yet; the field stays available once in a world.
        }
    }

    /**
     * SeedCrackerX still ships finders that stopped matching modern world generation: its own
     * README asks for emeralds, gateways, biomes, desert wells and warped fungi to be off, and says
     * dungeon cracking no longer works. Switch them off once so the player does not have to.
     */
    private static void configure() {
        if (configured) {
            return;
        }
        configured = true;
        try {
            Class<?> configClass = Class.forName("kaptainwutax.seedcrackerX.config.Config");
            Object config = configClass.getMethod("get").invoke(null);
            for (String name : new String[]{"emeraldOre", "endGateway", "biome", "desertWell", "warpedFungus", "dungeon"}) {
                Object toggle = configClass.getField(name).get(config);
                toggle.getClass().getMethod("set", boolean.class).invoke(toggle, false);
            }
            // Its renderer outlines detected structures in bright red across the world; the
            // progress bars in the seed screen already report what was found.
            Class<?> renderType = Class.forName("kaptainwutax.seedcrackerX.config.Config$RenderType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object off = Enum.valueOf((Class<Enum>) renderType, "OFF");
            configClass.getField("render").set(config, off);
            configClass.getMethod("save").invoke(null);
        } catch (Throwable ignored) {
            // A different SeedCrackerX version: leave its configuration alone.
        }
    }

    private static Status read() {
        try {
            Object storage = storage();
            if (storage == null) {
                return new Status(0, 0, false, false);
            }
            double base = (Double) storage.getClass().getMethod("getBaseBits").invoke(storage);
            double lifting = (Double) storage.getClass().getMethod("getLiftingBits").invoke(storage);
            Object machine = storage.getClass().getMethod("getTimeMachine").invoke(storage);
            boolean running = (Boolean) machine.getClass().getField("isRunning").get(machine);
            Object seeds = machine.getClass().getField("worldSeeds").get(machine);
            boolean solved = seeds instanceof Set<?> set && !set.isEmpty();
            return new Status(base, lifting, running, solved);
        } catch (Throwable broken) {
            return new Status(0, 0, false, false);
        }
    }

    private static Object storage() {
        try {
            Class<?> cracker = Class.forName("kaptainwutax.seedcrackerX.SeedCracker");
            Object instance = cracker.getMethod("get").invoke(null);
            return instance == null ? null : cracker.getMethod("getDataStorage").invoke(instance);
        } catch (Throwable missing) {
            return null;
        }
    }
}

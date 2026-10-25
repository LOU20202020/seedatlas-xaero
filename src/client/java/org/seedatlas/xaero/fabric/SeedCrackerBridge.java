package org.seedatlas.xaero.fabric;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;
import org.seedatlas.xaero.compat.SeedCrackerSupport;

/**
 * Registered as the {@code seedcrackerx} entry point in fabric.mod.json. SeedCrackerX looks that
 * key up only when it is installed, so this class is never loaded without it.
 */
public final class SeedCrackerBridge implements SeedCrackerAPI {
    @Override
    public void pushWorldSeed(final long seed) {
        SeedCrackerSupport.apply(seed);
    }
}

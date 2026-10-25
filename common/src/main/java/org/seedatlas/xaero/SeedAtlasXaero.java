package org.seedatlas.xaero;

import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.seedatlas.xaero.compat.XaeroCompatibility;
import org.seedatlas.xaero.config.SeedAtlasClientState;
import org.seedatlas.xaero.integration.SeedAtlasXaeroIntegration;
import org.slf4j.LoggerFactory;

/** Shared client lifecycle, called by each loader's client entry point. */
public final class SeedAtlasXaero {
    public static final String MOD_ID = "seedatlas_xaero";

    private static boolean incompatibilityShown;

    private SeedAtlasXaero() { }

    public static void initialize(Path configDirectory) {
        SeedAtlasClientState.initialize(configDirectory);
        if (xaeroCompatible()) {
            SeedAtlasXaeroIntegration.initialize();
        } else {
            LoggerFactory.getLogger(MOD_ID).warn("Seed Atlas is disabled for this Xaero's World Map version: {}",
                XaeroCompatibility.current().summary());
        }
        SeedAtlasClientState.save();
    }


    public static void tick(Minecraft client) {
        SeedAtlasClientState.refreshContext(client);
        if (xaeroCompatible()) {
            SeedAtlasXaeroIntegration.tickBackground(client);
        } else if (!incompatibilityShown && client.player != null) {
            incompatibilityShown = true;
            client.player.sendSystemMessage(incompatibleMessage());
        }
    }

    /**
     * False when the installed Xaero's World Map changed something Seed Atlas uses. Its mixins
     * were then not applied, and no code that touches Xaero may run.
     */
    public static boolean xaeroCompatible() {
        return XaeroCompatibility.current().compatible();
    }

    public static Component incompatibleMessage() {
        return Component.translatable("message.seedatlas_xaero.xaero_incompatible",
            XaeroCompatibility.current().summary());
    }
}

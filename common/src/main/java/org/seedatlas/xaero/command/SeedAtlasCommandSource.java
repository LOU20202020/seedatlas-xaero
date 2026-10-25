package org.seedatlas.xaero.command;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The client command operations that differ between loaders. */
public interface SeedAtlasCommandSource {
    Minecraft getClient();
    boolean attended();
    void sendFeedback(Component message);
    void sendError(Component message);
}

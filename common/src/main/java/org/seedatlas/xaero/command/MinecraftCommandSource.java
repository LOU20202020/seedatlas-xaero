package org.seedatlas.xaero.command;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Adapter for Forge and NeoForge's local command dispatcher. */
public record MinecraftCommandSource(CommandSourceStack source) implements SeedAtlasCommandSource {
    @Override public Minecraft getClient() { return Minecraft.getInstance(); }
    @Override public boolean attended() { return getClient().player != null && getClient().level != null; }
    @Override public void sendFeedback(Component message) { source.sendSuccess(() -> message, false); }
    @Override public void sendError(Component message) { source.sendFailure(message); }
}

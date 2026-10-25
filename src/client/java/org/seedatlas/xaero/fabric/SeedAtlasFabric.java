package org.seedatlas.xaero.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.command.SeedAtlasCommands;
import org.seedatlas.xaero.command.SeedAtlasCommandSource;
import org.seedatlas.xaero.integration.icon.StructureIcons;

public final class SeedAtlasFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        SeedAtlasXaero.initialize(FabricLoader.getInstance().getConfigDir());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath(SeedAtlasXaero.MOD_ID, "structure_icons"), StructureIcons.reloadListener());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
            SeedAtlasCommands.register(dispatcher, Source::new));
        ClientTickEvents.END_CLIENT_TICK.register(SeedAtlasXaero::tick);
    }

    private record Source(FabricClientCommandSource source) implements SeedAtlasCommandSource {
        @Override public Minecraft getClient() { return source.getClient(); }
        @Override public boolean attended() { return source.attended(); }
        @Override public void sendFeedback(Component message) { source.sendFeedback(message); }
        @Override public void sendError(Component message) { source.sendError(message); }
    }
}

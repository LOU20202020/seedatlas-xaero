package org.seedatlas.xaero.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.integration.icon.StructureIcons;

public final class SeedAtlasFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        SeedAtlasXaero.initialize(FabricLoader.getInstance().getConfigDir());
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath(SeedAtlasXaero.MOD_ID, "structure_icons"), StructureIcons.reloadListener());
        ClientTickEvents.END_CLIENT_TICK.register(SeedAtlasXaero::tick);
    }
}

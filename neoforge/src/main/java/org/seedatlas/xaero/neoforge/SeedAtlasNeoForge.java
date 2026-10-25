package org.seedatlas.xaero.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.command.MinecraftCommandSource;
import org.seedatlas.xaero.command.SeedAtlasCommands;
import org.seedatlas.xaero.integration.icon.StructureIcons;

@Mod(value = SeedAtlasXaero.MOD_ID, dist = Dist.CLIENT)
public final class SeedAtlasNeoForge {
    public SeedAtlasNeoForge(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) ->
            event.enqueueWork(() -> SeedAtlasXaero.initialize(FMLPaths.CONFIGDIR.get())));
        modBus.addListener((AddClientReloadListenersEvent event) -> event.addListener(
            Identifier.fromNamespaceAndPath(SeedAtlasXaero.MOD_ID, "structure_icons"), StructureIcons.reloadListener()));
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
            SeedAtlasCommands.register(event.getDispatcher(), MinecraftCommandSource::new));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> SeedAtlasXaero.tick(Minecraft.getInstance()));
    }
}

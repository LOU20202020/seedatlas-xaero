package org.seedatlas.xaero.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.seedatlas.xaero.SeedAtlasXaero;
import org.seedatlas.xaero.command.MinecraftCommandSource;
import org.seedatlas.xaero.command.SeedAtlasCommands;
import org.seedatlas.xaero.integration.icon.StructureIcons;

/** Forge skips this client-only mod entirely on dedicated servers. */
@Mod(SeedAtlasXaero.MOD_ID)
public final class SeedAtlasForge {
    private volatile boolean clientReady;
    public SeedAtlasForge(FMLJavaModLoadingContext context) {
        FMLClientSetupEvent.getBus(context.getModBusGroup()).addListener(event ->
            event.enqueueWork(() -> {
                SeedAtlasXaero.initialize(FMLPaths.CONFIGDIR.get());
                clientReady = true;
            }));
        RegisterClientReloadListenersEvent.BUS.addListener(event ->
            event.registerReloadListener(StructureIcons.reloadListener()));
        RegisterClientCommandsEvent.BUS.addListener(event ->
            SeedAtlasCommands.register(event.getDispatcher(), MinecraftCommandSource::new));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> {
            if (clientReady) SeedAtlasXaero.tick(Minecraft.getInstance());
        });
    }
}

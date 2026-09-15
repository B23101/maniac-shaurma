package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.renderer.*;
import com.log_to_kot.maniacmod.entity.ModEntityTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.MANIAC.get(), ManiacEntityRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.CORPSE.get(), CorpseEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "generator_progress",
            (gui, graphics, partialTick, screenWidth, screenHeight) ->
                com.log_to_kot.maniacmod.client.overlay.GeneratorProgressOverlay
                    .onRenderOverlay(graphics));
    }
}

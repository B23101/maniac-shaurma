package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.renderer.GroundItemRenderer;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Клієнтська реєстрація: рендерери сутностей і власні оверлеї мода.
 *
 * Оверлеї shaurma-lib (actionbar, відлік) реєструються не тут, а
 * через ShaurmaLib.attachOverlayEngine у головному класі — бібліотека
 * тримає власний єдиний IGuiOverlay замість десятків окремих.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.GROUND_ITEM.get(), GroundItemRenderer::new);
        // TODO(міграція maniacs): рендерер маньяка додається сюди.
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_progress",
            (gui, graphics, partialTick, width, height) ->
                GeneratorProgressOverlay.render(graphics));
    }
}

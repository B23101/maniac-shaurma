package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.overlay.hotbar.ManiacHotbarOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.SurvivorVitalsOverlay;
import com.log_to_kot.maniacmod.client.renderer.GroundItemRenderer;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

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

    /**
     * Підключення кастомного рендерера хотбару до shaurma-lib —
     * прямий виклик сетера, не реєстрація forge-events, тому саме
     * тут (FMLClientSetupEvent), а не в onRegisterOverlays. Один
     * екземпляр ManiacHotbarOverlay на весь клієнт: він тримає в
     * полях стан анімації (позиція рамки, масштаб), який має
     * накопичуватись між кадрами, а не створюватись заново.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        InventorySlotAllocationClientHooks.setCustomHotbarRenderer(new ManiacHotbarOverlay());
    }

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
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_survivor_vitals",
            (gui, graphics, partialTick, width, height) ->
                SurvivorVitalsOverlay.render(graphics));
    }
}

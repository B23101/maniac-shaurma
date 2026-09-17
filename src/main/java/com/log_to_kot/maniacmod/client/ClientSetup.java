package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.chat.ManiacChatEntryRenderer;
import com.log_to_kot.maniacmod.core.match.ManiacChatChannels;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.overlay.hotbar.ManiacHotbarOverlay;
import com.log_to_kot.maniacmod.client.overlay.roster.TabRosterOverlay;
import com.log_to_kot.maniacmod.client.overlay.debug.DebugOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.SurvivorVitalsOverlay;
import com.log_to_kot.maniacmod.client.renderer.GroundItemRenderer;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import dev.shaurmalib.forge.ShaurmaLib;
import dev.shaurmalib.forge.chat.ChatEntryRendererRegistry;
import dev.shaurmalib.forge.chat.ChatModule;
import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Клієнтська реєстрація: рендерери сутностей і власні оверлеї мода.
 *
 * Оверлеї shaurma-lib (actionbar, відлік) реєструються не тут, а
 * через ShaurmaLib.attachOverlayEngine у головному класі — бібліотека
 * тримає власний єдиний IGuiOverlay замість десятків окремих.
 * Tab-екран так само не тут: {@link ShaurmaLib#attachTabVisibility}
 * сам реєструє свій рендер під ключем {@code tab_list} усередині
 * {@link dev.shaurmalib.forge.tab.TabVisibilityModule#attach} —
 * викликається нижче прямим викликом сетера (як хотбар), а не через
 * {@code RegisterGuiOverlaysEvent}.
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
     *
     * Той самий принцип для tab-екрана: TabRosterOverlay тримає
     * власний TabListStyle з анімаційним станом рядків, тому теж
     * один екземпляр на весь клієнт, підключений один раз тут.
     * attachTabVisibility сам скасовує ванільний player_list і
     * показує TabRosterOverlay по тій самій клавіші Tab.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        InventorySlotAllocationClientHooks.setCustomHotbarRenderer(new ManiacHotbarOverlay());
        ShaurmaLib.attachTabVisibility(new TabRosterOverlay());

        // Чат: бібліотека піднімається на серверній події (ServerAboutToStart),
        // тому на ВИДІЛЕНОМУ сервері клієнт не отримує ані фіду спливаючих
        // повідомлень, ані звуку — їх треба підключити тут, у клієнтському
        // сетапі. Канали реєструються з того ж місця (це чисті дані), щоб
        // кнопки чату мали правильні підписи й кольори на будь-якому сервері.
        ManiacChatChannels.registerChannels();
        ChatModule.attachClientFeed(ManiacMod.CHAT_FEED_ID, () -> 0, true);
        // Вигляд повідомлення (2D-голова + нік кольором групи + текст
        // під ним) — це вигляд САМЕ цього режиму, тому він реєструється
        // тут, а не зашитий у бібліотеку.
        ChatEntryRendererRegistry.set(new ManiacChatEntryRenderer());
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Opening screenEvent) ->
            ShaurmaLib.attachChatScreenIntercept(screenEvent, () -> false, visible -> {
            }));
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
        event.registerAboveAll("maniac_debug_mode",
            (gui, graphics, partialTick, width, height) ->
                DebugOverlay.render(graphics));
    }
}

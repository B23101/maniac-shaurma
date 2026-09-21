package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.chat.ManiacChatEntryRenderer;
import com.log_to_kot.maniacmod.core.match.ManiacChatChannels;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.overlay.hint.GeneratorHintOverlay;
import com.log_to_kot.maniacmod.client.overlay.hint.GroundItemHintOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorCompletedOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorExplosionMarker;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.RescueOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.DownedSurvivorMarker;
import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorHighlightMarker;
import com.log_to_kot.maniacmod.client.overlay.hotbar.ManiacHotbarOverlay;
import com.log_to_kot.maniacmod.client.overlay.roster.TabRosterOverlay;
import com.log_to_kot.maniacmod.client.overlay.debug.DebugOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.SurvivorVitalsOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.LowHpVignetteOverlay;
import com.log_to_kot.maniacmod.client.renderer.FuelCanisterChargeDecorator;
import com.log_to_kot.maniacmod.client.renderer.GroundItemRenderer;
import com.log_to_kot.maniacmod.client.renderer.GeneratorRenderer;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import com.log_to_kot.maniacmod.registry.ModItems;
import dev.shaurmalib.forge.ShaurmaLib;
import dev.shaurmalib.forge.chat.ChatEntryRendererRegistry;
import dev.shaurmalib.forge.chat.ChatModule;
import dev.shaurmalib.forge.inventory.InventorySlotAllocationClientHooks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterItemDecorationsEvent;
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

        // Бібліотека сама малює дефолтну шкалу стаміни по центру екрана
        // (shaurma-lib StaminaClientHooks), поки withStamina() підключено —
        // але цей режим уже має власний HUD показників виживого
        // (SurvivorVitalsOverlay: медальйон + сегментована шкала стаміни
        // зліва внизу). Два незалежні бари стаміни одночасно (різні
        // позиції, різні джерела даних — StaminaSyncPacket проти
        // SurvivorVitalsPacket) — це і є причина "стаміна тратиться, а
        // візуально не видно": гравець дивиться на один HUD, поки
        // насправді оновлюється інший. Порожній рендер тут вимикає
        // дефолтний бар, лишаючи єдине джерело правди для гравця.
        dev.shaurmalib.forge.stamina.StaminaClientHooks.setRenderer(
            (graphics, minecraft, stamina, maxStamina, partialTick, width, height) -> { });

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

        // Кнопка "⚙ Налаштування гри" у вікні чату — видима лише
        // операторам (та сама межа прав, що вимагає корінь команди
        // /maniac: hasPermissions(2)). Це лише КОСМЕТИЧНЕ приховування
        // для гравців без прав — справжня перевірка на сервері, у
        // ServerPacketHandler.onOpenSettingsMenuRequest, бо клієнт
        // можна модифікувати. Натискання шле
        // OpenSettingsMenuRequestPacket і чекає відповіді сервера
        // (OpenSettingsMenuPacket), а не відкриває екран напряму —
        // клієнт не має актуальних значень конфігу без запиту.
        dev.shaurmalib.forge.chat.ChatScreenButtonRegistry.register(
            dev.shaurmalib.forge.chat.ChatScreenButton.of(
                "maniacmod_settings",
                net.minecraft.network.chat.Component.translatable("maniacmod.chat.settings_button"),
                net.minecraft.network.chat.Component.translatable("maniacmod.chat.settings_button_hover"),
                () -> {
                    var player = net.minecraft.client.Minecraft.getInstance().player;
                    return player != null && player.hasPermissions(2);
                },
                screen -> com.log_to_kot.maniacmod.net.ModNetwork.toServer(
                    new com.log_to_kot.maniacmod.net.c2s.settings.OpenSettingsMenuRequestPacket())));
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.GROUND_ITEM.get(), GroundItemRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.GENERATOR.get(), GeneratorRenderer::new);
        // TODO(міграція maniacs): рендерер маньяка додається сюди.
    }

    /**
     * Число заряду на іконці каністри. Реєструється на mod-bus (цей клас
     * вже підписаний на Bus.MOD, Dist.CLIENT), подія викликається один
     * раз на старті клієнта, після реєстрації предметів — тому
     * {@code ModItems.get(...).get()} уже повертає створений предмет.
     */
    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        event.register(ModItems.get("fuel_canister").get(), new FuelCanisterChargeDecorator());
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_progress",
            (gui, graphics, partialTick, width, height) ->
                GeneratorProgressOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_hint",
            (gui, graphics, partialTick, width, height) ->
                GeneratorHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_ground_item_hint",
            (gui, graphics, partialTick, width, height) ->
                GroundItemHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_completed",
            (gui, graphics, partialTick, width, height) ->
                GeneratorCompletedOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_explosion",
            (gui, graphics, partialTick, width, height) ->
                GeneratorExplosionMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_highlight",
            (gui, graphics, partialTick, width, height) ->
                GeneratorHighlightMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_downed_marker",
            (gui, graphics, partialTick, width, height) ->
                DownedSurvivorMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_rescue",
            (gui, graphics, partialTick, width, height) ->
                RescueOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_survivor_vitals",
            (gui, graphics, partialTick, width, height) ->
                SurvivorVitalsOverlay.render(graphics));
        // Поверх усього ігрового HUD (той самий шар, що debug-режим) —
        // вінʼєтка має лишатись видимою крайовим зором незалежно від
        // того, що ще малюється (прогрес-панелі, підказки, ростер).
        event.registerAboveAll("maniac_low_hp_vignette",
            (gui, graphics, partialTick, width, height) ->
                LowHpVignetteOverlay.render(graphics));
        event.registerAboveAll("maniac_debug_mode",
            (gui, graphics, partialTick, width, height) ->
                DebugOverlay.render(graphics));
    }
}

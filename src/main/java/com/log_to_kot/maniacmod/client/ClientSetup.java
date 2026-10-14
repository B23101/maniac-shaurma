package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.chat.ManiacChatEntryRenderer;
import com.log_to_kot.maniacmod.core.match.ManiacChatChannels;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay;
import com.log_to_kot.maniacmod.client.overlay.hint.GeneratorHintOverlay;
import com.log_to_kot.maniacmod.client.overlay.hint.GroundItemHintOverlay;
import com.log_to_kot.maniacmod.client.overlay.hint.TrapEscapeHintOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorCompletedOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorExplosionMarker;
import com.log_to_kot.maniacmod.client.overlay.actionprogress.RescueOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.DownedSurvivorMarker;
import com.log_to_kot.maniacmod.client.overlay.notify.ManiacStunOverlay;
import com.log_to_kot.maniacmod.client.overlay.notify.RoleRevealOverlay;
import com.log_to_kot.maniacmod.client.overlay.hotbar.ManiacHotbarOverlay;
import com.log_to_kot.maniacmod.client.overlay.hotbar.ManiacStrikeOverlay;
import com.log_to_kot.maniacmod.client.overlay.hotbar.TrapPanelOverlay;
import com.log_to_kot.maniacmod.client.overlay.roster.TabRosterOverlay;
import com.log_to_kot.maniacmod.client.overlay.debug.DebugOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.SurvivorVitalsOverlay;
import com.log_to_kot.maniacmod.client.overlay.vitals.LowHpVignetteOverlay;
import com.log_to_kot.maniacmod.client.renderer.ItemHudDecorator;
import com.log_to_kot.maniacmod.items.BatItem;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import com.log_to_kot.maniacmod.items.ScrewdriverItem;
import com.log_to_kot.maniacmod.client.renderer.GroundItemRenderer;
import com.log_to_kot.maniacmod.client.renderer.BearTrapRenderer;
import com.log_to_kot.maniacmod.client.renderer.ElectricWireMountRenderer;
import com.log_to_kot.maniacmod.client.renderer.ElectricWireRenderer;
import com.log_to_kot.maniacmod.client.renderer.GeneratorRenderer;
import com.log_to_kot.maniacmod.client.renderer.maniac.ManiacRenderHandler;
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
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
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
        // Іконка вікна гри — як у snipers_shaurma: config/maniacmod/window_icon.png
        // (створюється з ресурсів мода при першому запуску). Виклик GLFW — у головному потоці.
        event.enqueueWork(CustomWindowIcon::trySetIcon);

        InventorySlotAllocationClientHooks.setCustomHotbarRenderer(new ManiacHotbarOverlay());
        ShaurmaLib.attachTabVisibility(new TabRosterOverlay());
        // Інтро ШАУРМА LIB («Команда Шаурма представляє») стартує лише після плавного
        // затемнення екрана заходу — без стрибка між екраном і інтро.
        dev.shaurmalib.forge.overlay.IntroOverlay.setActivationGate(
                com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingOutro::introMayStart);

        // "Жива" поза бинтування аптечкою (LiveHeldItemAction/MedkitItem) —
        // реєструється РІВНО ОДИН раз тут, як і решта одноразових
        // клієнтських реєстрацій у цьому методі. Файл самої анімації:
        // assets/maniacmod/player_animations/medkit_use.json.
        com.log_to_kot.maniacmod.items.LiveHeldItemAction.registerUsePose("medkit");

        // Пози СТАНУ ТІЛА — лежачий, ремонт, залив, підбір — малюють
        // міксини на PlayerModel.setupAnim (MixinDownedPoseThirdPerson,
        // MixinGeneratorWorkThirdPerson), не PAL: PAL-шар не перекривав
        // ванільну SWIMMING-позу, і вони накладались одна на одну.
        // Ремонт/залив/підбір рук — через MixinGeneratorWorkThirdPerson
        // (прямий запис у PlayerModel.setupAnim, як у ліхтарика), не PAL.

        // Поза руки для ліхтарика в третій особі більше НЕ через
        // PAL/LiveHeldItemAction — див. MixinFlashlightHoldUpThirdPerson
        // (пряма правка PlayerModel.setupAnim, 1:1 з підходом
        // NotEnoughAnimations). Перша особа лишається окремим міксом,
        // MixinHoldFlashlightPose, на ItemInHandRenderer.

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
        // Екран чату (T або /) — власний ManiacChatScreen у стилі мода, а не
        // бібліотечний ChatHistoryScreen: у бібліотечного стрічка повідомлень
        // заходила під ряд кнопок дій (⚙ Налаштування гри лягала на текст),
        // а вигляд не збігався з рештою інтерфейсу. Дані (історія, канали,
        // кнопки з ChatScreenButtonRegistry, пакет відправки) — ті самі.
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Opening screenEvent) -> {
            if (screenEvent.getScreen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
                screenEvent.setNewScreen(new com.log_to_kot.maniacmod.client.chat.ManiacChatScreen());
            }
        });

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
        event.registerEntityRenderer(ModEntityTypes.OFFLINE_AVATAR.get(),
            com.log_to_kot.maniacmod.client.renderer.ManiacAvatarRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.GENERATOR.get(), GeneratorRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.RECEIVER.get(),
            com.log_to_kot.maniacmod.client.renderer.ReceiverRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.HOOK.get(),
            com.log_to_kot.maniacmod.client.renderer.HookRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.VICTORY_HOOK.get(),
            com.log_to_kot.maniacmod.client.victory.VictoryHookRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.VICTORY_MANIAC.get(),
            com.log_to_kot.maniacmod.client.victory.VictoryManiacRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.VICTORY_PLAYER.get(),
            com.log_to_kot.maniacmod.client.victory.VictoryPlayerRenderer::new);
        // Сидіння жертви невидиме: малювати нічого.
        event.registerEntityRenderer(ModEntityTypes.VICTIM_SEAT.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.HELICOPTER.get(),
            com.log_to_kot.maniacmod.client.renderer.HelicopterRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.EVAC_ROPE.get(),
            com.log_to_kot.maniacmod.client.renderer.EvacRopeRenderer::new);
        // Носій лазіння невидимий: малювати нічого.
        event.registerEntityRenderer(ModEntityTypes.CLIMB_CARRIER.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.BEAR_TRAP.get(), BearTrapRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.ROPE_TRAP.get(), com.log_to_kot.maniacmod.client.renderer.RopeTrapRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SMOKE_MINE.get(), com.log_to_kot.maniacmod.client.renderer.SmokeMineRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.SPIKE_TRAP.get(), com.log_to_kot.maniacmod.client.renderer.SpikeTrapRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.ELECTRIC_WIRE.get(), ElectricWireRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.ELECTRIC_WIRE_MOUNT.get(), ElectricWireMountRenderer::new);
        // Барикада: дошки малює рендерер; колайдери невидимі — малювати нічого.
        event.registerEntityRenderer(ModEntityTypes.BARRICADE.get(),
            com.log_to_kot.maniacmod.client.renderer.BarricadeRenderer::new);
        event.registerEntityRenderer(ModEntityTypes.BARRICADE_COLLIDER.get(),
            net.minecraft.client.renderer.entity.NoopRenderer::new);
        // Рендерер маньяка не реєструється за EntityType (усі маньяки —
        // звичайні Player), тому тут лише будується його контекст; саме
        // малювання підміняє ванільний рендер гравця через
        // RenderPlayerEvent.Pre (див. ManiacRenderHandler).
        ManiacRenderHandler.init();
    }

    /**
     * Число заряду на іконці каністри. Реєструється на mod-bus (цей клас
     * вже підписаний на Bus.MOD, Dist.CLIENT), подія викликається один
     * раз на старті клієнта, після реєстрації предметів — тому
     * {@code ModItems.get(...).get()} уже повертає створений предмет.
     */
    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        // ОДИН декоратор на предмет: відсотки й перезарядка не накладаються
        // (у спокої — число, під час перезарядки — бар + секунди), див. ItemHudDecorator.
        event.register(ModItems.get("fuel_canister").get(), new ItemHudDecorator(FuelCanisterItem::getCharge, null));
        event.register(ModItems.get("crowbar").get(), new ItemHudDecorator(CrowbarItem::getDurability, CrowbarItem.COOLDOWN_KEY));
        event.register(ModItems.get("screwdriver").get(), new ItemHudDecorator(ScrewdriverItem::getDurability, null));
        event.register(ModItems.get("bat").get(), new ItemHudDecorator(BatItem::getDurability, BatItem.COOLDOWN_KEY));
    }

    /** Кастомні частинки: провайдери й кадри ({@code particles/*.json}). */
    @SubscribeEvent
    public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        com.log_to_kot.maniacmod.client.particle.ManiacParticles.register(event);
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        // Вихід із екрана заходу: сцена розходиться навпіл і відкриває світ (над усім HUD).
        event.registerAboveAll("maniac_loading_outro",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.screen.loading.ManiacLoadingOutro.render(graphics, width, height));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_profile_hint",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.overlay.hint.ProfileHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_progress",
            (gui, graphics, partialTick, width, height) ->
                GeneratorProgressOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_hint",
            (gui, graphics, partialTick, width, height) ->
                GeneratorHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_ground_item_hint",
            (gui, graphics, partialTick, width, height) ->
                GroundItemHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_trap_place_hint",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.traps.TrapPlacementHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_barricade_hint",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.barricade.BarricadeHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_trap_escape_hint",
            (gui, graphics, partialTick, width, height) ->
                TrapEscapeHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_completed",
            (gui, graphics, partialTick, width, height) ->
                GeneratorCompletedOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_generator_explosion",
            (gui, graphics, partialTick, width, height) ->
                GeneratorExplosionMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_downed_marker",
            (gui, graphics, partialTick, width, height) ->
                DownedSurvivorMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_hook_marker",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.hooks.HookMarker.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_stun_overlay",
            (gui, graphics, partialTick, width, height) ->
                ManiacStunOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_rescue",
            (gui, graphics, partialTick, width, height) ->
                RescueOverlay.render(graphics));
        // Евакуація: підказки над прицілом (приймач/мотузка/лазіння), панель кроку з
        // лічильником «втекло N/M» і банер втечі.
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_evac_hint",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.evac.EvacHintOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_evac_hud",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.evac.EvacHudOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_evac_escape_toast",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.evac.EscapeToastOverlay.render(graphics));
        // Голос диспетчера після виклику вертольота: рація + текст, що друкується синхронно зі звуком.
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "maniac_evac_dispatcher",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.evac.DispatcherOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_strike_slot",
            (gui, graphics, partialTick, width, height) ->
                ManiacStrikeOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_trap_panel",
            (gui, graphics, partialTick, width, height) ->
                TrapPanelOverlay.render(graphics));
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_survivor_vitals",
            (gui, graphics, partialTick, width, height) ->
                SurvivorVitalsOverlay.render(graphics));
        // Власний індикатор голосу замість штатного значка SVC (лівий край, по центру).
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "maniac_voice_indicator",
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.voice.VoiceHudOverlay.render(graphics));
        // Поверх усього ігрового HUD (той самий шар, що debug-режим) —
        // вінʼєтка має лишатись видимою крайовим зором незалежно від
        // того, що ще малюється (прогрес-панелі, підказки, ростер).
        // Оголошення ролі — поверх усього HUD, щоб хотбар і панелі не
        // перекривали великий текст (той самий шар, що віньєтки нижче).
        // Кіно-вступ: затемнення, letterbox і loader. Нижче за оголошення ролі
        // (те малюється вже після розтемнення) і вище за весь звичайний HUD.
        event.registerAboveAll(com.log_to_kot.maniacmod.client.intro.IntroClient.OVERLAY_ID,
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.intro.IntroOverlay.render(graphics));
        event.registerAboveAll(com.log_to_kot.maniacmod.client.victory.VictoryClient.OVERLAY_ID,
            (gui, graphics, partialTick, width, height) ->
                com.log_to_kot.maniacmod.client.victory.VictoryClient.render(graphics, width, height));
        event.registerAboveAll("maniac_role_reveal",
            (gui, graphics, partialTick, width, height) ->
                RoleRevealOverlay.render(graphics));
        event.registerAboveAll("maniac_hit_effect",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; ManiacHitEffect.render(graphics); });
        // Ефект «ти влучив» — лише для маньяка (хітмаркер на прицілі, легкий відблиск).
        event.registerAboveAll("maniac_hit_confirm",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; ManiacHitConfirmFx.render(graphics); });
        // Екранний ефект шипів (залізні шипи знизу, кров, спалах): поверх удару
        // маньяка, нижче за віньєтку хп.
        event.registerAboveAll("maniac_spike_hit",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; com.log_to_kot.maniacmod.client.SpikeHitEffect.render(graphics); });
        // Плями диму на екрані в газі димової міни: нижче за віньєтку хп.
        event.registerAboveAll("maniac_smoke_screen",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; com.log_to_kot.maniacmod.client.overlay.effects.SmokeScreenOverlay.render(graphics); });
        // Затемнення країв, коли маніяк зовсім поруч: під червоною віньєткою хп.
        event.registerAboveAll("maniac_proximity_dark",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; com.log_to_kot.maniacmod.client.overlay.vitals.ManiacProximityDarkOverlay.render(graphics); });
        event.registerAboveAll("maniac_low_hp_vignette",
            (gui, graphics, partialTick, width, height) ->
                { if (com.log_to_kot.maniacmod.client.vr.VrCompat.eyeEffects()) return; LowHpVignetteOverlay.render(graphics); });
        event.registerAboveAll("maniac_debug_mode",
            (gui, graphics, partialTick, width, height) ->
                DebugOverlay.render(graphics));
    }
}

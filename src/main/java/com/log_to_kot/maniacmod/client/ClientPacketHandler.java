package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorCompletedOverlay;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Єдина точка, де серверні пакети торкаються клієнтського стану.
 *
 * НАВІЩО окремий клас: у v3 кожен пакет у своєму handle() напряму
 * смикав конкретний оверлей — тому оверлей не можна було замінити, не
 * правлячи пакети, а пакети не можна було тестувати без клієнта.
 * Тепер пакет знає лише про цей клас, а цей клас — про клієнтський
 * стан.
 *
 * Клас @OnlyIn(CLIENT): на виділеному сервері його немає в рантаймі,
 * тому пакети звертаються сюди ТІЛЬКИ через DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandler {

    private ClientPacketHandler() {}

    public static void onPhase(GamePhase phase) {
        ClientMatchState.setPhase(phase);
        com.log_to_kot.maniacmod.client.victory.VictoryClient.onPhaseChanged(phase);
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET || phase == GamePhase.ENDING) pendingCatalog = null;
        if (!phase.allows(com.log_to_kot.maniacmod.core.phase.PhaseRule.TRAPS)) {
            // Гра скінчилась / не почалась: жодної підсвітки жертв, підказки F,
            // удару струмом чи підвісу — незалежно від того, чи дійшли пакети.
            com.log_to_kot.maniacmod.client.glow.TrapVictimGlow.reset();
            ClientMatchState.setTrapEscapePrompt(false);
            ClientMatchState.setWireShocked(false);
            com.log_to_kot.maniacmod.client.WireShockCameraShake.configure(false, 0.0, 1.0);
            com.log_to_kot.maniacmod.client.rope.RopeHangClient.reset();
            com.log_to_kot.maniacmod.client.rope.RopeHangPoseAnimation.reset();
            com.log_to_kot.maniacmod.client.SmokeMuffle.reset();
            com.log_to_kot.maniacmod.client.SpikeHitEffect.reset();
        }
        // Голос маньяка прив'язаний до фази й ролі — перераховуємо одразу
        // після оновлення стану (див. voice/VoiceActivation).
        com.log_to_kot.maniacmod.voice.VoiceActivation.refresh();
        if (phase == GamePhase.ENDING) {
            // Після сцени перемоги екран статистики чекає, поки розтемниться лобі.
            com.log_to_kot.maniacmod.client.victory.VictoryClient.runWhenIdle(() ->
                Minecraft.getInstance().setScreen(
                    new com.log_to_kot.maniacmod.client.screen.statistics.MatchSummaryScreen()));
        }
    }

    /** Статистика матчу для екрана підсумків. */
    public static void onSummary(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket packet) {
        ClientMatchState.setSummary(packet);
    }

    /** Нагорода XP за матч — приходить одразу після підсумку, РАНІШЕ за фазу ENDING. */
    public static void onReward(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket packet) {
        ClientMatchState.setReward(packet);
    }

    /** Підсумок матчу (хто переміг) — приходить РАНІШЕ за фазу ENDING. */
    public static void onOutcome(boolean survivorsWon, int escaped, int total) {
        ClientMatchState.setOutcome(survivorsWon, escaped, total);
    }

    public static void onRole(RoleSyncPacket.Role role, String archetypeId, double attackRangeBlocks) {
        ClientMatchState.setRole(role, archetypeId, attackRangeBlocks);
        com.log_to_kot.maniacmod.voice.VoiceActivation.refresh();
    }

    /** Конфіг блиску предметів на землі ({@code loot.sparkleEnabled}), синхронізований із сервера. */
    public static void onGroundItemVisualSettings(boolean sparkleEnabled, double pickupRangeBlocks) {
        ClientMatchState.setGroundItemSparkleEnabled(sparkleEnabled);
        ClientMatchState.setGroundItemPickupRangeBlocks(pickupRangeBlocks);
    }

    public static void onVitals(int hp, int maxHp, float stamina,
                                SurvivorState state, float heartbeat, boolean trapped) {
        ClientMatchState.setVitals(hp, maxHp, stamina, state, heartbeat, trapped);
    }

    public static void onDownedSurvivors(List<com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket.Entry> entries) {
        ClientMatchState.setDowned(entries);
    }

    public static void onRescueProgress(int progress, int required, boolean asVictim) {
        ClientMatchState.setRescueProgress(progress, required, asVictim);
    }

    public static void onStandUpProgress(int presses, int required) {
        ClientMatchState.setStandUpProgress(presses, required);
    }

    /**
     * Застосований набір пасток. Якщо в цю мить відкритий екран вибору
     * ({@link com.log_to_kot.maniacmod.client.screen.maniac.TrapChooseScreen}) —
     * це підтвердження прийнято, закриваємо його. Прийти без відкритого
     * екрана — звичайний випадок (RANDOM без вибору, чи автовибір на
     * старті гри): тоді тут нема чого закривати.
     */
    public static void onTrapLoadout(java.util.List<String> trapIds, double placeRange) {
        ClientMatchState.setTrapLoadout(trapIds, placeRange);
        if (!trapIds.isEmpty()) pendingCatalog = null;   // вибір зроблено — меню більше не потрібне
        // Оголошення ролі маньяка: іконки пасток, які він щойно узяв.
        com.log_to_kot.maniacmod.client.overlay.notify.RoleRevealOverlay.onTrapLoadout(trapIds);
        if (!trapIds.isEmpty()
            && Minecraft.getInstance().screen instanceof com.log_to_kot.maniacmod.client.screen.maniac.TrapChooseScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    /**
     * Сигнал відкрити екран вибору пасток. Якщо він УЖЕ відкритий
     * (реконект, повторна відправка) — оновлюємо дані замість другого
     * екрана поверх першого.
     */
    /** Каталог, з якого маньяк ще не підтвердив вибір (щоб відкрити меню знову після Esc). */
    private record PendingCatalog(java.util.List<String> ids, int limit) {}
    private static PendingCatalog pendingCatalog;

    /**
     * Маньяк натиснув клавішу пастки, а пасток у нього ще немає: відкриває
     * меню вибору знову (якщо він закрив його через Esc). Гра не чекає на
     * вибір, тож повернутись до нього можна будь-коли.
     *
     * @return true, якщо меню відкрито
     */
    public static boolean reopenTrapChoice() {
        PendingCatalog p = pendingCatalog;
        if (p == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return false;
        mc.setScreen(new com.log_to_kot.maniacmod.client.screen.maniac.TrapChooseScreen(p.ids(), p.limit()));
        return true;
    }

    public static void onTrapCatalog(java.util.List<String> trapIds, int limit) {
        pendingCatalog = new PendingCatalog(java.util.List.copyOf(trapIds), limit);
        // Кіно-вступ блокує екрани (Esc/чат/меню). Каталог приходить раз — не губимо його,
        // а відкриваємо вибір пасток, щойно розтемнення закінчилось.
        if (com.log_to_kot.maniacmod.client.intro.IntroClient.blocksScreens()) {
            com.log_to_kot.maniacmod.client.intro.IntroClient.runWhenUnblocked(() -> onTrapCatalog(trapIds, limit));
            return;
        }
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.maniac.TrapChooseScreen choose) {
            choose.onCatalogUpdated(trapIds, limit);
        } else {
            Minecraft.getInstance().setScreen(
                new com.log_to_kot.maniacmod.client.screen.maniac.TrapChooseScreen(trapIds, limit));
        }
    }

    public static void onAbilityCooldown(String abilityId, int totalTicks) {
        ClientMatchState.setAbilityCooldown(abilityId, totalTicks, clientTick());
    }

    public static void onGeneratorHighlight(int durationTicks,
                                            List<GeneratorHighlightPacket.Entry> entries) {
        ClientMatchState.setHighlight(entries, durationTicks, clientTick());
        // Реальна підсвітка — контур сутності генератора крізь стіни,
        // лише на цьому клієнті (див. GeneratorHighlightGlow).
        com.log_to_kot.maniacmod.client.glow.GeneratorHighlightGlow
            .show(entries, durationTicks);
        // Звук активації підсвітки — момент, коли гравець натиснув
        // клавішу 5 і побачив контури генераторів крізь стіни.
        com.log_to_kot.maniacmod.client.style.UiAnim.playReady();
    }

    public static void onGeneratorProgress(GeneratorProgressPacket packet) {
        com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay.accept(packet);
    }

    // ── Міні-ігри ремонту ────────────────────────────────────────────────

    public static void onTargetMinigameOpen(
            com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket packet) {
        ClientInputHandler.forceReleaseRepairHold();
        // Заголовок вибирається ТУТ, а не береться з пакета: механіка в
        // обох випадках однакова (той самий екран), а належність екрана
        // клієнт знає зі свого стану — я зараз у капкані чи біля
        // генератора. Пакет лишається про механіку, а не про місце
        // (див. TargetMinigameOpenPacket).
        String titleKey = ClientMatchState.isTrapped()
            ? "maniacmod.minigame.trap.title"
            : "maniacmod.minigame.target.title";
        Minecraft.getInstance().setScreen(new com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen(
            titleKey, packet.seed(), packet.cursorSpeed(), packet.hitZoneWidth(),
            packet.targetPosition(), packet.hitsRequired()));
    }

    public static void onWireMinigameOpen(
            com.log_to_kot.maniacmod.net.s2c.minigame.WireMinigameOpenPacket packet) {
        ClientInputHandler.forceReleaseRepairHold();
        Minecraft.getInstance().setScreen(new com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen(
            packet.initialRightSlotForLeft(), packet.rightColors(), packet.timeLimitTicks()));
    }

    /**
     * Часткове просування всередині вже відкритої міні-гри. Пакет один
     * на обидві міні-ігри — розрізняємо за тим, який екран зараз
     * відкрито, а не за вмістом пакета (див. клас-докстрінг пакета).
     */
    public static void onRepairMinigameProgress(
            com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen target) {
            // Сам лічильник, без тлумачення «влучив чи промахнувся»: у
            // капкані число може й зменшитись (штраф за промах), а екран
            // у обох випадках робить те саме — показує нове число й дає
            // спробу ще раз.
            target.onHit(packet.hitsSoFar());
        } else if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen wires) {
            wires.onWireConnected(packet.leftSlot(), packet.rightSlot());
        }
    }

    /** Міні-гра завершена (успіх чи провал) — закриває який завгодно з двох екранів міні-ігор. */
    public static void onRepairMinigameResult(
            com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen target) {
            target.onResult(packet.success());
        } else if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen wires) {
            wires.onResult(packet.success());
        }
    }

    /**
     * Actionbar через shaurma-lib. Текст збирається ТУТ, з клієнтської
     * локалізації — сервер прислав лише ключ і аргументи, тому гравець
     * бачить повідомлення своєю мовою, а не мовою сервера.
     */
    public static void onActionBar(ActionBarMessageType type, String translationKey, String[] args) {
        Object[] boxed = new Object[args.length];
        System.arraycopy(args, 0, boxed, 0, args.length);
        Component text = Component.translatable(translationKey, boxed);
        dev.shaurmalib.forge.overlay.ActionBarMessageSystem.show(type, text.getString());
    }

    /**
     * Ростер усіх гравців матчу — джерело даних для
     * {@code client/overlay/roster/TabRosterOverlay} (утримання Tab).
     */
    public static void onRoster(List<RosterSyncPacket.RosterEntry> entries) {
        ClientMatchState.setRoster(entries);
    }

    /** Перелік маньяків для оцінки та збережені оцінки гравця — див. {@code ClientManiacRatingState}. */
    public static void onManiacRatings(com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket packet) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Map<String, float[]> saved = new java.util.HashMap<>();
        for (var e : packet.entries()) {
            ids.add(e.id());
            saved.put(e.id(), new float[]{e.look(), e.abilities(), e.fear()});
        }
        com.log_to_kot.maniacmod.client.screen.rating.ClientManiacRatingState.get()
            .onSync(ids, saved, packet.everSubmitted(), packet.comment());
    }

    /** Відгуки гравців для адміна: оновлює відкритий екран або відкриває новий. */
    public static void onAdminReviews(com.log_to_kot.maniacmod.net.s2c.rating.AdminReviewsPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.admin.AdminReviewsScreen reviews) {
            reviews.update(packet);
        } else {
            Minecraft.getInstance().setScreen(
                new com.log_to_kot.maniacmod.client.screen.admin.AdminReviewsScreen(packet));
        }
    }

    /** Сторінка історії матчів (адмінський список або історія гравця). */
    public static void onHistoryList(com.log_to_kot.maniacmod.net.s2c.history.HistoryListPacket packet) {
        com.log_to_kot.maniacmod.client.history.ClientHistoryState.receiveList(
            packet.target(), packet.total(), packet.offset(), packet.items());
    }

    /** Команда {@code /maniac history}: відкрити архів усіх матчів або історію вказаного гравця. */
    public static void onOpenHistory(com.log_to_kot.maniacmod.net.s2c.history.OpenHistoryPacket packet) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(packet.target() == null
            ? new com.log_to_kot.maniacmod.client.screen.history.MatchHistoryScreen(null)
            : new com.log_to_kot.maniacmod.client.screen.history.MatchHistoryScreen(
                null, packet.target(), packet.targetName()));
    }

    /** Повний матч для екрана деталей (або «заборонено/не знайдено»). */
    public static void onHistoryDetail(com.log_to_kot.maniacmod.net.s2c.history.HistoryDetailPacket packet) {
        com.log_to_kot.maniacmod.client.history.ClientHistoryState.receiveDetail(packet.matchId(), packet.record());
    }

    public static void onLeaderboardPage(com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardPagePacket packet) {
        com.log_to_kot.maniacmod.client.leaderboard.ClientLeaderboardState.receivePage(packet);
    }

    public static void onLeaderboardOverview(com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket packet) {
        com.log_to_kot.maniacmod.client.leaderboard.ClientLeaderboardState.receiveOverview(packet);
    }

    /** Ціль матчу («N з M генераторів», віхи) — джерело для верхньої картки tab-екрана. */
    public static void onObjectives(com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket packet) {
        ClientMatchState.setObjectives(packet);
    }

    public static void onCountdown(int digit, int accentArgb) {
        if (digit <= 0) {
            dev.shaurmalib.forge.overlay.AnimatedCountdownSystem.clear();
            return;
        }
        dev.shaurmalib.forge.overlay.AnimatedCountdownSystem.show(digit, accentArgb);
    }

    /** "Генератор N з M полагоджено" — див. {@link GeneratorCompletedOverlay}. */
    public static void onGeneratorCompleted(int index, int total) {
        GeneratorCompletedOverlay.show(index, total);
    }

    /** Генератор вибухнув — червоний маркер на екрані, див. {@code GeneratorExplosionMarker}. */
    public static void onGeneratorExplosion(net.minecraft.core.BlockPos pos, int durationTicks) {
        com.log_to_kot.maniacmod.client.overlay.notify.GeneratorExplosionMarker.show(pos, durationTicks);
    }

    /**
     * Маньяк оглушений ударом лома (чи оглушення скінчилось). Просто
     * записуємо в клієнтський стан — рендер (HUD-зірочки для себе,
     * world-зірочки над головою для всіх) читає
     * {@code ClientMatchState.isManiacStunned(...)} самостійно кожен
     * кадр, тут нема кого "показати" одноразово (див. докстрінг
     * {@code ManiacStunPacket}).
     */
    public static void onManiacStun(java.util.UUID maniacId, boolean stunned, int totalTicks) {
        ClientMatchState.setManiacStun(maniacId, stunned, totalTicks);
    }

    /** Відкрити екран міні-гри розкачування (див. {@code RopeMinigameOpenPacket}). */
    public static void onRopeMinigameOpen() {
        com.log_to_kot.maniacmod.client.screen.minigame.RopeSwingScreen.open();
    }

    /** Меню приймача (див. {@code ReceiverMenuOpenPacket}). */
    public static void onReceiverMenu(int state) {
        com.log_to_kot.maniacmod.client.evac.ReceiverMenuScreen.open(state);
    }

    /** Стан евакуації для екранного HUD (див. {@code EvacStatusPacket}). */
    public static void onEvacStatus(com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket packet) {
        com.log_to_kot.maniacmod.client.evac.ClientEvac.setStatus(packet);
    }

    /** Хтось втік — анімований банер і звук (див. {@code EscapeAnnouncePacket}). */
    public static void onEscapeAnnounce(String name, int escaped, int total) {
        com.log_to_kot.maniacmod.client.evac.ClientEvac.onEscape(name, escaped, total);
        com.log_to_kot.maniacmod.client.style.UiAnim.playTabGeneratorDone();
    }

    /** Розмітка галявин для жовтого контуру (див. {@code ClearingSyncPacket}). */
    public static void onClearings(java.util.List<com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket.Rect> rects) {
        com.log_to_kot.maniacmod.client.evac.ClientEvac.setClearings(rects);
    }

    /** Показати/сховати підказку "Натисни F" (див. {@code TrapEscapePromptPacket}). */
    public static void onTrapEscapePrompt(boolean active) {
        ClientMatchState.setTrapEscapePrompt(active);
    }

    /**
     * Почався/скінчився удар струмом (див. {@code WireShockPacket}).
     * Сам стан лише записуємо — камеру глушить міксин (як оглушення
     * маньяка), а тряску додає {@code WireShockCameraShake} щокадру.
     */
    public static void onWireShock(boolean active, double degrees, double hz) {
        ClientMatchState.setWireShocked(active);
        com.log_to_kot.maniacmod.client.WireShockCameraShake.configure(active, degrees, hz);
    }

    /**
     * Приглушити/повернути звуки в хмарі димової міни (див.
     * {@code SmokeMufflePacket}). Сам стан і застосування — у {@code SmokeMuffle}.
     */
    public static void onSmokeMuffle(boolean active, float factor) {
        com.log_to_kot.maniacmod.client.SmokeMuffle.set(active, factor);
    }

    /**
     * Жертва наступила на шипи (див. {@code SpikeHitPacket}): вмикає екранний
     * ефект — шипи знизу, кров, спалах, удар камери. Стан гри не чіпає.
     */
    public static void onSpikeHit(boolean downed) {
        com.log_to_kot.maniacmod.client.SpikeHitEffect.trigger(downed);
    }

    /**
     * Хтось почав/змінив/закінчив роботу з генератором (див.
     * {@code GeneratorInteractionPacket}). Тут лише запис: саму позу
     * рук малює {@code MixinGeneratorWorkThirdPerson}, читаючи
     * цим станом (він же обслуговує і ВЛАСНИЙ ремонт, тож джерело
     * правди має бути одне).
     *
     * Значення поза межами відомих трактуємо як {@code ACTION_NONE}:
     * клієнт не має падати чи показувати щось безглузде, якщо на сервері
     * новіша версія мода з третім видом роботи.
     */
    public static void onGeneratorInteraction(java.util.UUID playerId, int action) {
        ClientMatchState.setGeneratorInteraction(playerId, action);
    }

    // ── Меню налаштувань ─────────────────────────────────────────────────

    /**
     * Відкриває {@code SettingsMenuScreen}, або, якщо він уже відкритий
     * (гравець щойно змінив поле й чекає на підтверджене значення),
     * оновлює його на місці замість пересоздання — щоб скрол і фокус
     * поля не скидались при кожній зміні.
     */
    public static void onOpenSettingsMenu(
            com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.settings.SettingsMenuScreen settings) {
            settings.onValuesUpdated(packet.values());
        } else {
            Minecraft.getInstance().setScreen(
                new com.log_to_kot.maniacmod.client.screen.settings.SettingsMenuScreen(packet.values()));
        }
    }

    /**
     * "Темна зона" — сервер каже, чи гравець зараз усередині. Плавний
     * перехід яскравості lightmap рахує сама {@code DarkZoneEffect}
     * (shaurma-lib) локально щотік — тут лише передаємо цільовий стан
     * і параметри поточного конфігу (intensity/transitionTicks можуть
     * змінитись через {@code /maniac reload}, тому їдуть у кожному
     * пакеті, а не читаються з клієнтського кешу один раз).
     * <p>
     * Не викликає {@code DarkZoneEffect.setActive(...)} напряму —
     * передає пакет {@link com.log_to_kot.maniacmod.client.FlashlightClientHandler#onServerDarkZoneState}
     * (ліхтарик у руці тимчасово перекриває цей самий ключ ефекту, і
     * хендлеру потрібно знати РЕАЛЬНИЙ серверний стан, щоб коректно
     * повернути його, щойно гравець прибере ліхтарик — див. клас-
     * докстрінг {@code FlashlightClientHandler}, пункт 2).
     */
    public static void onDarkZoneState(boolean active, float intensity, int transitionTicks) {
        com.log_to_kot.maniacmod.client.FlashlightClientHandler.onServerDarkZoneState(active,
            new dev.shaurmalib.common.overlay.DarkZoneSpec(intensity, transitionTicks));
    }

    // ── Пастки: підсвітка жертви та міні-гра ───────────────────────────

    /**
     * Новий склад підсвічених жертв пасток (див. {@code TrapVictimGlowPacket}).
     * Несе ГОТОВІ id сутностей — пошук і таймер не потрібні, тож ланцюжок
     * «пакет → контур» тут прямий.
     */
    public static void onTrapVictimGlow(int color, int[] entityIds) {
        com.log_to_kot.maniacmod.client.glow.TrapVictimGlow.show(color, entityIds);
    }

    /**
     * Одна спроба в міні-грі визволення з капкана: вердикт + НОВА позиція
     * цілі. Це повідомлення призначене лише екрану міні-гри; якщо його
     * чомусь зараз відкрито інший екран — мовчки нічого не робимо (той
     * самий стиль, що решта обробників міні-ігор).
     */
    public static void onTrapEscapeAttempt(
            com.log_to_kot.maniacmod.net.s2c.minigame.TrapEscapeAttemptPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen target) {
            target.onTrapAttempt(packet.hitsSoFar(), packet.hit(), packet.targetPosition());
        }
    }

    /** Лічильник клієнтських тіків — база для локального відліку кулдаунів. */
    private static long clientTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }
}

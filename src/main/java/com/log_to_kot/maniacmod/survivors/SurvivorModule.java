package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.StandUpProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import com.log_to_kot.maniacmod.registry.ModSounds;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.forge.stamina.StaminaRules;
import dev.shaurmalib.forge.stamina.StaminaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.ChatFormatting;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import com.log_to_kot.maniacmod.loot.GroundItemSpawner;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.RescueProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.List;

/**
 * Хп, стаміна, падіння з поламаною ногою, непритомність і підняття,
 * HUD-показники власного гравця (SurvivorVitalsPacket).
 *
 * ── Що це замінює ─────────────────────────────────────────────────────
 * До цього модуля хп рахувався (MatchContext.damage/heal вже викликав
 * ManiacCombatModule на удар), але:
 *   • стаміна з shaurma-lib була увімкнена ЛИШЕ з дефолтними
 *     правилами (withStamina() без аргументів) — числа з
 *     survivors.yml (staminaDrainPerTick/staminaRegenPerTick) ніколи
 *     не застосовувались;
 *   • SurvivorVitalsPacket був описаний, але НІХТО його не слав —
 *     HUD клієнта завжди бачив нульові поля;
 *   • падіння й поламана нога не мали жодного коду (TODO прямо в
 *     ServerHooks.onEntityJoin сусідстві — LivingFallEvent).
 *
 * ── Чому Supplier<MatchOrchestrator> ──────────────────────────────────
 * Той самий патерн, що GeneratorModule/ManiacCombatModule: оркестратор
 * ще будується, коли створюється це поле.
 *
 * ── Що є локальним станом модуля, а що в MatchContext ─────────────────
 * SurvivorState (HEALTHY/BROKEN_LEG/CRAWLING/UNCONSCIOUS/ELIMINATED/
 * ESCAPED) і хп — це стан МАТЧУ (MatchContext), бо предмети (Шина,
 * Аптечка) й майбутні пастки теж повинні його читати/писати через
 * фасад оркестратора.
 * Проміжні лічильники ЦЬОГО модуля (останній надісланий HUD-знімок,
 * прогрес підняття непритомного) — локальні тут, бо нікому іншому
 * не потрібні.
 *
 * ── Життєвий цикл непритомного (UNCONSCIOUS) ─────────────────────────
 *   0 хп → {@link #onSurvivorDowned}: гравець лягає (поза — міксин
 *          {@code MixinPlayerDownedPose}), повзе повільно
 *          ({@code downedCrawlSpeed}), шкала стаміни на нулі, всім
 *          виживим видно мітку на всю карту, іде таймер
 *          {@code downedBleedOutTicks} (за замовчуванням 60 с).
 *   ┌ союзник утримує ПКМ ~{@code rescueTicks} (8 с); якщо відпустив —
 *   │   прогрес згасає до нуля за {@code rescueDecayTicks} (5 с) →
 *   │   {@link #reviveDowned}: гравець стоїть із {@code reviveHp} хп,
 *   │   без стадії CRAWLING — одразу може бігти (стаміна від нуля).
 *   └ таймер вичерпано → {@link #eliminateDowned}:
 *       предмети розсипаються навколо тіла, гравець стає глядачем
 *       ({@code GameType.SPECTATOR}), сама смерть іде через шов
 *       {@link SurvivorDeathSequence} — туди піде анімація.
 */
public final class SurvivorModule implements PhaseListener {

    private final Supplier<MatchOrchestrator> matchSupplier;
    private final Random rng = new Random();

    /** Останній HUD-знімок, надісланий кожному гравцю — щоб не слати пакет щотік без змін. */
    private final Map<UUID, SurvivorVitalsPacket> lastSentVitals = new HashMap<>();

    /** Хто зараз піднімає кого: жертва → множина рятівників, що утримують Shift біля неї. */
    private final Map<UUID, java.util.Set<UUID>> rescuers = new HashMap<>();

    /** Прогрес підняття жертви, у тіках утримання (накопичується, поки хоч один рятівник тримає). */
    /**
     * Прогрес підняття — double, а не int. Множник помічників дробовий
     * (двоє ≈ ×1.2), і з цілим {@code Math.round(1.2)} давав 1: бонус
     * з'являвся лише при чотирьох рятівниках, попри дизайн «двоє швидше».
     */
    private final Map<UUID, Double> rescueProgressTicks = new HashMap<>();

    /**
     * Причина локу руху для InteractionLock (shaurma-lib) — гравець
     * лежить (CRAWLING) чи непритомний (UNCONSCIOUS) і фізично не
     * може ходити/повертатись, доки не встане чи його не піднімуть.
     * Той самий підхід, що {@code ManiacCombatModule.LOCK_REASON}:
     * рядок-ключ, за яким саме цей лок знімається, а не чужий.
     */
    private static final String DOWNED_MOVEMENT_LOCK_REASON = "survivor_downed";

    /**
     * Скільки тіків лишилось до наступної підсвітки генераторів (клавіша 5)
     * для кожного виживого. Немає запису = підсвітка готова.
     *
     * Це стан САМЕ цього модуля, а не матчу: підсвітку більше ніхто не
     * читає (див. правило «чи це стан МАТЧУ» в AI_CODE_GUIDE, розділ 3.2).
     * Лічильник у ТІКАХ, що тікають лише в фазах, де гра йде, — тому пауза
     * між фазами (наприклад ROLE_REVEAL) не з'їдає перезарядку.
     */
    private final Map<UUID, Integer> highlightCooldownTicks = new HashMap<>();

    // ── Непритомні ───────────────────────────────────────────────────────

    /** Постійний id модифікатора швидкості: щоб зняти рівно свій, а не чужий. */
    private static final UUID DOWNED_SPEED_MODIFIER_ID =
        UUID.fromString("5b0e0a6c-3f1d-4c55-9a1e-6d2f8a7c1b90");

    /** Як часто оновлювати клієнтам позиції/таймери лежачих, поки список непорожній. */
    private static final int DOWNED_BROADCAST_INTERVAL_TICKS = 10;

    /** Запас до дальності підняття, вище якого рятівника скидають із сесії (гістерезис проти мерехтіння на межі). */
    private static final double RESCUE_RANGE_PRUNE_MARGIN = 1.0;

    /** Непритомний → скільки тіків лишилось до смерті. Ключі = хто зараз лежить. */
    private final Map<UUID, Integer> bleedOutTicksLeft = new HashMap<>();

    /**
     * Лічильник тіків, що йдуть разом із {@link #tickDowned}: використовується
     * лише як throttle для підказки маньяку (раз на секунду), щоб той не
     * отримував actionbar щотік.
     */
    private long downedTickCounter = 0;

    /** Склад списку, який клієнти бачили востаннє: за ним видно, що список ЗМІНИВСЯ. */
    private final Set<UUID> lastBroadcastDowned = new HashSet<>();

    /**
     * Лежачі, чий таймер ЗАРАЗ стоїть (маньяк у радіусі милосердя).
     * Перебудовується щотіка в {@link #tickDowned} — потрібна, щоб клієнт
     * показував зафіксований час, а не власний відлік.
     */
    private final Set<UUID> mercyPaused = new HashSet<>();
    /** Стан пауз, який клієнти бачили востаннє (разом із {@link #lastBroadcastDowned}). */
    private final Set<UUID> lastBroadcastPaused = new HashSet<>();
    private int downedBroadcastCounter = 0;

    /** Хто вже в процесі смерті — щоб удар/таймер не запустили її вдруге, поки йде анімація. */
    private final Set<UUID> dying = new HashSet<>();

    /** Режим гри ДО того, як гравця зробили глядачем — щоб повернути в лобі. */
    private final Map<UUID, GameType> gameModeBeforeSpectating = new HashMap<>();

    private final SurvivorDeathSequence deathSequence = new SurvivorDeathSequence();

    /** Таб-ростер оновлюється раз на секунду по hp, не щотік — той самий throttle, що config-watcher у ServerHooks. */
    private static final int ROSTER_BROADCAST_INTERVAL_TICKS = 20;
    private int rosterBroadcastCounter = 0;

    public SurvivorModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() {
        return "survivors";
    }

    // ── Фази ─────────────────────────────────────────────────────────────

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.ROLE_REVEAL) {
            // Ролі й хп вже призначені в MatchOrchestrator.start(...) —
            // тут лише вмикаємо стаміну й перший HUD-знімок, щоб HUD
            // виживого не блимав нулями до першого тіку HUNT.
            for (ServerPlayer player : players) {
                if (!match().isSurvivor(player.getUUID())) continue;
                enableStamina(player);
                sendVitals(player, true);
            }
        } else if (!phase.isGameplay()) {
            // Матч завершується (ENDING) або скидається (RESET/LOBBY) —
            // стаміна вимикається для всіх, рятувальні сесії обриваються.
            // Хп/стан лишаються в MatchContext до RESET: підсумковий екран
            // може захотіти показати останній стан.
            //
            // Раніше це стояло в onPhaseExit(будь-яка ігрова фаза), тобто
            // спрацьовувало й на HUNT→POWERED та POWERED→FINALE: щойно
            // ремонтувались усі генератори, стаміна вимикалась (правила
            // скидались на дефолтні active=false), шкала застигала на 100%,
            // а лежачих гравців розблоковувало. Тепер — лише при виході з
            // ігрового блоку в неігрову фазу.
            for (ServerPlayer player : players) {
                StaminaService.clear(player);
                unlockMovement(player);
                removeCrawlSpeed(player);
                setDownedGlow(player, false);
                hideStandUpProgress(player);
            }
            rescuers.clear();
            rescueProgressTicks.clear();
            legRulesApplied.clear();
            pendingLegBreak.clear();
            legIntegrity.clear();
            standUpPresses.clear();
            // Нова гра — підсвітка знову готова. Клієнтові окремо нічого
            // слати не треба: ClientMatchState.reset() гасить кулдауни
            // на виході з матчу.
            highlightCooldownTicks.clear();

            // Лежачі й «помираючі» більше нікуди не зникнуть самі: гасимо
            // мітки/позу в усіх клієнтів явним порожнім списком.
            bleedOutTicksLeft.clear();
            dying.clear();
            mercyPaused.clear();
            lastBroadcastDowned.clear();
            lastBroadcastPaused.clear();
            ModNetwork.toPlayers(players, new DownedSurvivorsPacket(List.of()));

            // Глядачі повертаються в свій звичайний режим лише коли гру
            // скинуто: на ENDING вони ще дивляться підсумок.
            if (phase == GamePhase.RESET || phase == GamePhase.LOBBY) {
                restoreGameModes(players);
            }
        }
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.RESET || phase == GamePhase.LOBBY) {
            lastSentVitals.clear();
            rosterBroadcastCounter = 0;
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        boolean vitalsPhase = phase.allows(PhaseRule.SURVIVOR_VITALS);
        // Фази, де HUD уже видно, а SURVIVOR_VITALS ще немає, але стаміна
        // вже ввімкнена й реально працює:
        //   • LOBBY — /maniac morph survivor навмисно робить гравця
        //     виживим посеред лобі (див. SurvivorVitalsOverlay);
        //   • ROLE_REVEAL — enableStamina() викликається саме на вході в
        //     цю фазу, HUD уже показується (PhaseRule.HUD), і гравець може
        //     бігти, поки йде екран ролей. Без цього винятку шкала
        //     стаміни на цей час застигала б на першому знімку, хоч
        //     сервер уже рахував витрату.
        // Обидва випадки — лише відправка HUD-знімків; рятування, втеча й
        // таб-ростер нижче лишаються тільки для справжніх ігрових фаз.
        boolean hudOnlyPhase = phase == GamePhase.LOBBY || phase == GamePhase.ROLE_REVEAL;
        if (!vitalsPhase && !hudOnlyPhase) return;

        tickHighlightCooldowns(players);

        ServerPlayer maniac = maniacOf(players);
        for (ServerPlayer player : players) {
            if (!match().isSurvivor(player.getUUID())) continue;

            tickBrokenLegStamina(player);
            tickLegIntegrity(player);
            float heartbeat = computeHeartbeat(player, maniac);
            sendVitals(player, heartbeat, false);
        }

        // Рятування, втеча й таб-ростер — лише справжня ігрова логіка.
        if (!vitalsPhase) return;

        if (phase.allows(PhaseRule.RESCUE)) tickRescues();
        tickDowned(players);
        tickStandUpProgress(players);
        if (phase.allows(PhaseRule.ESCAPE)) tickEscapes(players);

        // Throttled: hp міняється поступово (урон/лікування), не
        // щотік — раз на секунду досить, щоб таб не відставав помітно.
        if (++rosterBroadcastCounter >= ROSTER_BROADCAST_INTERVAL_TICKS) {
            rosterBroadcastCounter = 0;
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(players);
        }
    }

    // ── Підсвітка генераторів (сила, клавіша 5) ──────────────────────────

    /**
     * Спроба використати силу підсвітки. Викликається з
     * {@code ServerPacketHandler.onHighlightRequest} ПІСЛЯ перевірок ролі й
     * фази — тут лише кулдаун і сам запуск.
     *
     * Перезарядка — на сервері: клієнт міг би слати пакет щотік, а
     * локальна перевірка в {@code ClientInputHandler} — лише зручність
     * (щоб не засмічувати мережу), а не захист.
     *
     * Кулдаун стартує ТІЛЬКИ після успішного використання: відмова
     * (ще не готово) його не продовжує, інакше спам клавіші тримав би
     * силу заблокованою вічно.
     *
     * @return true, якщо підсвітку показано (кулдаун запущено)
     */
    public boolean tryUseHighlight(ServerPlayer player) {
        UUID id = player.getUUID();
        if (highlightCooldownTicks.getOrDefault(id, 0) > 0) return false;

        var role = match().survivorRoleOf(id);
        if (role == null) return false;
        int cooldown = role.flashlightCooldownTicks();

        match().generatorModule().sendHighlight(player);

        highlightCooldownTicks.put(id, cooldown);
        // Клієнт відлічує сам від цього значення (та сама форма, що в
        // кулдаунів маньяка) — тікових пакетів нема.
        ModNetwork.toPlayer(player, new AbilityCooldownPacket(AbilityCooldownPacket.HIGHLIGHT_ID, cooldown));
        return true;
    }

    /**
     * Раз на тік зменшує лічильники й повідомляє клієнт, коли сила знову
     * готова (той самий {@code ready}-сигнал, що в маньяка).
     */
    private void tickHighlightCooldowns(List<ServerPlayer> players) {
        if (highlightCooldownTicks.isEmpty()) return;

        var it = highlightCooldownTicks.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                continue;
            }
            it.remove();
            for (ServerPlayer player : players) {
                if (player.getUUID().equals(entry.getKey())) {
                    ModNetwork.toPlayer(player,
                        AbilityCooldownPacket.ready(AbilityCooldownPacket.HIGHLIGHT_ID));
                    break;
                }
            }
        }
    }

    // ── Втеча ────────────────────────────────────────────────────────────

    /**
     * Здоровий виживий, що зайшов у зону втечі, вибуває з матчу як
     * втеклий. UNCONSCIOUS/CRAWLING/BROKEN_LEG навмисно НЕ втікають
     * самі — непритомного/повзучого має винести інший гравець (дизайн
     * ще не визначає, як саме; поки що втекти можна лише на своїх ногах).
     */
    private void tickEscapes(List<ServerPlayer> players) {
        List<com.log_to_kot.maniacmod.map.zones.EscapeZoneArchetype> zones = match().escapeZones();
        if (zones.isEmpty()) return;

        for (ServerPlayer player : players) {
            UUID id = player.getUUID();
            if (!match().isSurvivor(id)) continue;
            if (match().survivorStateOf(id) != SurvivorState.HEALTHY
                && match().survivorStateOf(id) != SurvivorState.BROKEN_LEG) continue;

            for (var zone : zones) {
                if (!zone.contains(player)) continue;
                onSurvivorLeftMatch(player, SurvivorState.ESCAPED);
                match().markEscaped(player);
                // Подія важлива для табу й фіналу — не чекаємо
                // наступного throttled roster-тіку.
                com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(players);
                break;
            }
        }
    }

    // ── Стаміна ──────────────────────────────────────────────────────────

    private void enableStamina(ServerPlayer player) {
        // Правила беремо зі СТАНУ НОГИ, а не завжди "здорові". enableStamina
        // викликається не лише на старті матчу, а й з дебаг-morph і після
        // реконекту — тобто гравець уже може лежати з поламаною ногою.
        // Якщо поставити тут здорові правила, а legRulesApplied вже містить
        // його UUID, syncLegRules вважатиме правила "вже застосованими" і
        // ніколи їх не виправить: нога зламана, а стаміна відновлюється.
        boolean broken = staminaLocked(match().survivorStateOf(player.getUUID()));
        if (broken) {
            legRulesApplied.add(player.getUUID());
        } else {
            legRulesApplied.remove(player.getUUID());
        }
        StaminaService.setRules(player, buildStaminaRules(broken));
    }

    /**
     * Публічний вхід для {@code /maniac morph survivor} у лобі.
     *
     * ── Чому це потрібно окремо від ROLE_REVEAL ──────────────────────
     * Звичайний шлях (onPhaseEnter(ROLE_REVEAL)) вмикає StaminaService
     * і шле перший знімок vitals лише для гравців зі списку тікового
     * переходу фази. Дебаг-morph міняє роль ОДНОГО гравця посеред фази
     * LOBBY, тому без цього методу StaminaService для нього лишався б
     * вимкненим: спринт не витрачав би стаміну, а HUD показував би
     * захардкожені 100%, надіслані вручну з MatchOrchestrator.morphSurvivor,
     * замість реального значення.
     */
    public void onLobbyMorphToSurvivor(ServerPlayer player) {
        enableStamina(player);
        sendVitals(player, true);
    }

    /**
     * Публічний вхід для {@code /maniac morph maniac} і
     * {@code /maniac morph reset} у лобі: якщо гравець щойно був
     * дебаг-виживим, його StaminaService лишався б увімкненим і далі
     * рахував би стаміну (і виснажував голод через
     * forceFullHungerWhileActive) навіть після втрати ролі виживого.
     */
    public void onLobbyMorphAway(ServerPlayer player) {
        StaminaService.clear(player);
        lastSentVitals.remove(player.getUUID());
        legRulesApplied.remove(player.getUUID());
        pendingLegBreak.remove(player.getUUID());
        legIntegrity.remove(player.getUUID());
        standUpPresses.remove(player.getUUID());
        highlightCooldownTicks.remove(player.getUUID());
        bleedOutTicksLeft.remove(player.getUUID());
        removeCrawlSpeed(player);
        unlockMovement(player);
        hideStandUpProgress(player);
    }

    /**
     * Гравець вийшов із сервера — прибираємо ВЕСЬ його локальний стан.
     *
     * Лок руху, накопичені натискання, відкладений злам ноги й правила
     * стаміни ключуються за UUID і лишались би в мапах модуля та в
     * статичному реєстрі lib. Якщо гравець повернеться (реконект у
     * тому ж матчі), він отримав би замок, якого не пам'ятає: лежачого
     * без шкали вставання, яку клієнт уже не показує.
     */
    public void onPlayerLeft(ServerPlayer player) {
        UUID id = player.getUUID();
        unlockMovement(player);
        pendingLegBreak.remove(id);
        legIntegrity.remove(id);
        standUpPresses.remove(id);
        legRulesApplied.remove(id);
        lastSentVitals.remove(id);
        rescuers.remove(id);
        rescueProgressTicks.remove(id);
        removeRescuer(id);
        // Вихід НЕ дає обійти перезарядку: якщо гравець перезайде в тому ж
        // матчі, кулдаун має лишитись. Тому тут його свідомо НЕ чистимо —
        // він зникне сам, коли дотікає, або з кінцем матчу.
    }

    // ── Лок руху (лише CRAWLING після падіння; непритомний повзе — див. ensureCrawlSpeed) ──

    private void lockMovement(ServerPlayer player) {
        ManiacMod.lib().interactionLockModule()
            .lock(player.getUUID(), LockType.MOVEMENT, DOWNED_MOVEMENT_LOCK_REASON);
    }

    private void unlockMovement(ServerPlayer player) {
        ManiacMod.lib().interactionLockModule()
            .unlock(player.getUUID(), LockType.MOVEMENT, DOWNED_MOVEMENT_LOCK_REASON);
    }

    /**
     * Конфіг тримає темп "за тік" (staminaDrainPerTick — частка від
     * 0..1 шкали, помножена на 20 тіків/сек), StaminaRules очікує
     * "одиниць за секунду" на шкалі 0..100 (maxStamina=100 — той сам
     * вимір, що очікує SurvivorVitalsPacket.stamina() 0.0–1.0 після
     * ділення на maxStamina у sendVitals).
     */
    private StaminaRules buildStaminaRules() {
        return buildStaminaRules(false);
    }

    /**
     * @param brokenLeg {@code true} — правила для гравця з поламаною
     *                  ногою: відновлення стаміни ВИМКНЕНЕ на рівні
     *                  бібліотеки. Це не косметика, а єдиний надійний
     *                  спосіб: {@code StaminaService.tick} виконується на
     *                  {@code PlayerTickEvent.END}, тобто ПІСЛЯ нашого
     *                  {@link #onPhaseTick}, і без цього прапорця щотіка
     *                  додавав би стаміну назад одразу після того, як ми
     *                  її обнулили — шкала «дрижала» б між 0 і малим
     *                  значенням, а спринт міг би вмикатись на кадр.
     */
    private StaminaRules buildStaminaRules(boolean brokenLeg) {
        float drainPerSecond = (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_DRAIN_PER_TICK) * 20f * 100f;
        float regenPerSecond = (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_REGEN_PER_TICK) * 20f * 100f;
        return StaminaRules.builder()
            .active(true)
            .maxStamina(100f)
            .drainPerSecond(drainPerSecond)
            .recoveryPerSecond(regenPerSecond)
            // Повне виснаження (0 стаміни) — довша пауза перед
            // відновленням, ніж звичайна (recoveryDelaySeconds нижче):
            // синхронізовано з мінімальною тривалістю звуку задишки
            // (ExhaustedBreathModule, ≥3 сек) — гравець чує, що видихався,
            // і стаміна дійсно НЕ починає накопичуватись раніше, ніж він
            // встигає це почути.
            .emptyRecoveryDelaySeconds(3.0f)
            .recoveryDelaySeconds(0.5f)
            .recoveryEnabled(!brokenLeg)
            // Спринт блокує сама бібліотека (міксин на setSprinting, клієнт
            // + сервер): на 0 бігти не можна, а після повного виснаження —
            // доки шкала не відновиться до цієї частки (0.2 = 20%). Поки
            // стаміна є — спринт працює як у ваніли, але тратить її.
            .sprintResumeFraction(0.2f)
            .forceFullHungerWhileActive(true)
            // Поламана нога робить те саме, що "стрибати не можна",
            // тому один прапор бібліотеки покриває обидва правила
            // дизайну ("без стаміни не стрибнути" і "з поламаною
            // ногою не стрибнути" — друге гарантується tickBrokenLegStamina,
            // що тримає стаміну на нулі).
            .blockJumpWhenDepleted(true)
            .build();
    }

    /**
     * Поламана нога: стаміна тримається на нулі й не відновлюється,
     * доки предмет "Шина" не поверне стан у HEALTHY.
     *
     * ── Два шари, бо одного мало ─────────────────────────────────────
     * 1) {@link #applyLegRules}: на вході в BROKEN_LEG правила гравця
     *    міняються на ті, де {@code recoveryEnabled=false}. Без цього
     *    бібліотека відновлює стаміну сама на END-тіку — після нас.
     * 2) Тут: щотіка притискаємо значення до нуля (страховка на випадок
     *    зовнішнього {@code setStamina}, наприклад команди адміна) і
     *    тримаємо повне виснаження — тоді {@code EXHAUSTED} у бібліотеці
     *    гарантує, що спринт і стрибок заблоковані, навіть якщо
     *    прапорець відновлення хтось повернув.
     *
     * Ідемпотентно: викликається щотіка для кожного виживого, для
     * здорових нічого не робить (крім одноразового повернення звичайних
     * правил після лікування — див. {@link #syncLegRules}).
     */
    private void tickBrokenLegStamina(ServerPlayer player) {
        boolean broken = staminaLocked(match().survivorStateOf(player.getUUID()));
        syncLegRules(player, broken);
        if (!broken) return;
        if (StaminaService.getStamina(player) > 0) {
            StaminaService.setStamina(player, 0, false);
        }
    }

    /** Гравці, чиї правила стаміни зараз — "зламана нога" (без відновлення). */
    private final java.util.Set<UUID> legRulesApplied = new java.util.HashSet<>();

    /**
     * Приводить правила стаміни у відповідність до стану ноги. Викликається
     * щотіка, але реальну роботу ({@code setRules} + пакет синхронізації)
     * робить лише на ЗМІНІ стану — тому це не навантажує сервер і не
     * засмічує мережу.
     */
    private void syncLegRules(ServerPlayer player, boolean broken) {
        UUID id = player.getUUID();
        boolean applied = legRulesApplied.contains(id);
        if (broken == applied) return;

        if (broken) {
            legRulesApplied.add(id);
        } else {
            legRulesApplied.remove(id);
        }
        // setRules зберігає поточну стаміну (min(old, max)), тому
        // повернення звичайних правил після Шини НЕ дарує гравцю повну
        // шкалу — вона просто починає відновлюватись з нуля за
        // recoveryDelaySeconds, як і задумано дизайном.
        StaminaService.setRules(player, buildStaminaRules(broken));
    }

    // ── Хп: ізольовано у фасаді MatchOrchestrator, цей модуль лише читає ──
    // healSurvivor()/damageSurvivor() вже викликаються з ItemArchetype-
    // предметів і ManiacCombatModule — тут додається лише джерело
    // урону "падіння" (onFall нижче), бо ванільний LivingFallEvent
    // не проходить через DamageInterceptorRegistry (той ловить лише
    // LivingHurtEvent-шлях).

    // ── Падіння / поламана нога ─────────────────────────────────────────

    /**
     * Викликається з {@code ServerHooks} на {@code LivingFallEvent}.
     *
     * ── Політика: у грі ванільний урон від падіння не діє НІКОЛИ ──────
     * HP виживих рахує {@code MatchContext}, а ванільне HP гравця тут не
     * має ігрового сенсу — воно лише могло б вбити його «по-справжньому»
     * поза системою станів. Тому для виживого у фазі з
     * {@code SURVIVOR_VITALS} повертаємо {@code true} (викликач
     * скасовує ванільну подію) ЗАВЖДИ, а НАСЛІДОК (лежання, шанс
     * зламаної ноги) застосовуємо лише від порога висоти.
     *
     * Раніше нижче порога повертався {@code false}: ванільний урон від
     * невеликого падіння проходив поверх нашої системи й зменшував
     * ванільне HP, якого гра не показує й не лікує.
     *
     * Так само система розрізняє ДЖЕРЕЛО шкоди: падіння обробляється
     * тут (лежання, нога, БЕЗ втрати HP), удар маньяка — у
     * {@code ManiacCombatModule} (втрата HP, непритомність). Це два
     * різні шляхи з різними наслідками, а не один урон із різними
     * числами.
     *
     * @return true, якщо це падіння виживого в ігровій фазі — викликач
     *         скасовує ванільний урон. false — не наш випадок (лобі,
     *         маньяк, глядач): лишається поведінка за замовчуванням.
     */
    public boolean onFall(ServerPlayer player, float fallDistanceBlocks) {
        if (!match().phases().allows(PhaseRule.SURVIVOR_VITALS)) return false;
        if (!match().isSurvivor(player.getUUID())) return false;

        // БАГ (падіння рівно з 3 блоків зараховувалось як 4+): ванільний
        // fallDistance — не ціле число блоків, а накопичена фізична
        // висота падіння. Стрибок ПЕРЕД падінням (а не крок із краю)
        // додає до неї частку блока понад видиму різницю висот — гравець,
        // що стрибнув і впав із майданчика "3 блоки заввишки", легко
        // отримує fallDistance у районі 3.4-3.6, що вже НЕ менше
        // knockdownHeight=4 за старим строгим порівнянням лише на
        // дрібницю. floor() округлює до кількості ПОВНИХ пройдених
        // блоків — "три блоки" завжди дають рівно 3, стрибок чи ні.
        int wholeBlocks = (int) Math.floor(fallDistanceBlocks);

        int knockdownHeight = ManiacConfigs.get(ConfigSchema.FALL_KNOCKDOWN_HEIGHT);
        if (wholeBlocks < knockdownHeight) return true; // без наслідків, але й без ванільного урону

        // Уже лежить, повзе або непритомний — повторне падіння не
        // додає новий стан поверх наявного й не скидає прогрес
        // вставання (beginCrawling обнулив би лічильник натискань) та
        // не перегенеровує шанс зламаної ноги.
        SurvivorState current = match().survivorStateOf(player.getUUID());
        if (current == SurvivorState.UNCONSCIOUS || current == SurvivorState.CRAWLING) return true;

        double legBreakChance = legBreakChanceFor(
            wholeBlocks,
            ManiacConfigs.get(ConfigSchema.LEG_BREAK_MIN_HEIGHT),
            ManiacConfigs.get(ConfigSchema.LEG_BREAK_CHANCE_AT_MIN),
            ManiacConfigs.get(ConfigSchema.LEG_BREAK_MAX_HEIGHT),
            ManiacConfigs.get(ConfigSchema.LEG_BREAK_CHANCE_AT_MAX));
        boolean legBroken = legBreakChance > 0.0 && rng.nextDouble() < legBreakChance;

        // Падіння від fallKnockdownHeightBlocks завжди збиває з ніг:
        // гравець повзе, доки не натисне пробіл (onStandUpAttempt нижче).
        // Чи зламана нога — залежить від ВИСОТИ (legBreakChanceFor):
        // 4–5 блоків лише лягає, з 6 з'являється шанс, що росте до
        // максимуму. Рішення приймається зараз, але застосовується лише ПІСЛЯ вставання — так дизайн
        // "CRAWLING = лежить, BROKEN_LEG = ходить без стаміни" не
        // конфліктує: це два послідовні стани, не одночасні.
        pendingLegBreak.put(player.getUUID(), legBroken);
        beginCrawling(player);
        return true;
    }

    /**
     * Шанс зламати ногу залежно від ВИСОТИ падіння (а не фіксований).
     *
     * <pre>
     *   висота &lt; minHeight              → 0            (лежить, нога ціла)
     *   minHeight ≤ висота &lt; maxHeight  → лінійно від chanceAtMin до chanceAtMax
     *   висота ≥ maxHeight              → chanceAtMax  (далі не росте)
     * </pre>
     *
     * Зі значеннями за замовчуванням (6 / 20% / 10 / 90%): до 6 блоків
     * нога ціла, на 6 — 20%, на 8 — 55%, на 10 і вище — 90%.
     *
     * Чиста функція без стану — навмисно {@code static}: її можна
     * перевірити без сервера й без {@code ManiacConfigs}.
     *
     * Захист від некоректного конфігу (адмін може виставити
     * {@code maxHeight <= minHeight}): тоді інтерполяція неможлива, а
     * ділення на нуль дало б NaN → {@code rng.nextDouble() < NaN} завжди
     * false, тобто нога НІКОЛИ б не ламалась і ніхто не зрозумів би чому.
     * Тому при виродженому діапазоні береться {@code chanceAtMax} зі
     * ступінчастим порогом на {@code minHeight}.
     */
    static double legBreakChanceFor(float fallBlocks, int minHeight, double chanceAtMin,
                                    int maxHeight, double chanceAtMax) {
        if (fallBlocks < minHeight) return 0.0;
        if (maxHeight <= minHeight) return chanceAtMax;
        if (fallBlocks >= maxHeight) return chanceAtMax;
        double t = (fallBlocks - minHeight) / (double) (maxHeight - minHeight);
        return chanceAtMin + t * (chanceAtMax - chanceAtMin);
    }

    /**
     * Єдиний вхід у стан CRAWLING — з падіння ({@link #onFall}). Піднятий
     * союзником ({@link #reviveDowned}) сюди НЕ потрапляє: він встає одразу.
     *
     * Лежить, доки не набере потрібну кількість натискань пробілу
     * ({@link #onStandUpAttempt}); рух і поворот фізично заблоковані на
     * цей час — серверний лок (freeze щотіка) плюс клієнтський міксин
     * блокування руху й стрибка (див. {@code MixinLocalPlayerDownedMovement}).
     */
    private void beginCrawling(ServerPlayer player) {
        UUID id = player.getUUID();
        match().setSurvivorState(id, SurvivorState.CRAWLING);
        standUpPresses.remove(id);
        lockMovement(player);
        sendVitals(player, true);
        sendStandUpProgress(player, 0);
        broadcastRosterFor(player);
    }

    /** UUID → чи зламається нога, коли гравець підведеться з поточного CRAWLING. */
    private final Map<UUID, Boolean> pendingLegBreak = new HashMap<>();

    /**
     * UUID → ПРИХОВАНА міцність ніг, 0..{@link #LEG_INTEGRITY_MAX}. Немає запису = повна.
     *
     * Клієнту не надсилається НІКОЛИ: за дизайном гравець не знає, скільки
     * ще витримають ноги, — він дізнається лише коли хруснуло. Тому це
     * окреме поле, а не частина {@code VitalsPacket}/{@code RosterEntry}.
     * Живе поруч із {@link #pendingLegBreak}, бо це той самий домен —
     * «що стається з ногою» — просто інше джерело: падіння вирішує
     * ногу за ОДИН раз, пастки — накопичують шкоду.
     */
    private final Map<UUID, Float> legIntegrity = new HashMap<>();

    /** Повна міцність ніг. */
    public static final float LEG_INTEGRITY_MAX = 100f;

    /**
     * Пастка вдарила по ногах. Знімає {@code amount} з прихованої шкали;
     * якщо вона дійшла до нуля і нога ще ціла — ламає її.
     *
     * Ламання йде тим самим шляхом, що й після падіння (стан
     * {@code BROKEN_LEG} + звук), тож наслідки — без стаміни й стрибка —
     * не дублюються. Лежачому/непритомному ногу не ламаємо: його стан
     * уже важчий, а {@code BROKEN_LEG} перезаписав би CRAWLING.
     * Шкала при цьому все одно зменшується — це «накопичена втома ніг».
     *
     * @return true, якщо саме цим ударом нога зламалась
     */
    public boolean onTrapLegDamage(ServerPlayer player, float amount) {
        if (amount <= 0f) return false;
        UUID id = player.getUUID();
        if (!match().isSurvivor(id)) return false;

        float next = Math.max(0f, legIntegrity.getOrDefault(id, LEG_INTEGRITY_MAX) - amount);
        legIntegrity.put(id, next);
        if (next > 0f) return false;

        SurvivorState current = match().survivorStateOf(id);
        if (current != SurvivorState.HEALTHY) return false; // уже BROKEN_LEG / лежить

        match().setSurvivorState(id, SurvivorState.BROKEN_LEG);
        player.level().playSound(null, player.blockPosition(),
            ModSounds.BONE_BREAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        spawnLegBreakParticles(player);
        sendVitals(player, true);
        broadcastRosterFor(player);
        return true;
    }

    /**
     * Нога загоєна (шина): шкала повертається на максимум, інакше одразу
     * після лікування наступний капкан зламав би ногу знову.
     */
    private void restoreLegIntegrity(UUID id) {
        legIntegrity.remove(id);
    }

    /**
     * Партиклі перелому ноги — одна крапка виклику для ОБОХ джерел
     * (капкан {@link #onTrapLegDamage} і падіння {@link #onStandUpAttempt}),
     * щоб не дублювати рендер-код у двох місцях. Той самий підхід, що
     * {@code GeneratorModule#burst}: {@code sendParticles(player, ...)}
     * з примусовою видимістю для гравців поруч, а не покладання на те,
     * що клієнт сам вирішить малювати частинки на такій дистанції.
     */
    private void spawnLegBreakParticles(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            if (viewer.serverLevel() != level) continue;
            level.sendParticles(viewer, ParticleTypes.DAMAGE_INDICATOR, true,
                player.getX(), player.getY() + 1.0, player.getZ(),
                15, 0.3, 0.5, 0.3, 0.08);
        }
    }

    /** Повільна регенерація прихованої шкали. Тікається раз на тік у {@link #onPhaseTick}. */
    private void tickLegIntegrity(ServerPlayer player) {
        UUID id = player.getUUID();
        Float value = legIntegrity.get(id);
        if (value == null) return;
        if (match().traps().isTrapped(id)) return; // поки в пастці — не гоїться
        double perSecond = ManiacConfigs.get(ConfigSchema.LEG_INTEGRITY_REGEN_PER_SECOND);
        if (perSecond <= 0.0) return;

        float next = (float) Math.min(LEG_INTEGRITY_MAX, value + perSecond / 20.0);
        if (next >= LEG_INTEGRITY_MAX) legIntegrity.remove(id);
        else legIntegrity.put(id, next);
    }

    /**
     * Накопичений прогрес спроби встати. Дробовий, а не цілий: поки гравець
     * спамить пробіл, прогрес щотіка ЗГАСАЄ рівною швидкістю
     * ({@code standUpPresses / standUpDecayTicks} за тік), тож одним-двома
     * натисканнями встати неможливо — шкала встигає впасти назад, поки
     * палець не тисне знову. Це і є «спамити пробіл» замість «натисни N разів».
     */
    private final Map<UUID, Double> standUpPresses = new HashMap<>();

    /**
     * Спроба встати (пробіл). Викликається з {@code ServerPacketHandler}
     * — та сама перевірка стану CRAWLING вже зроблена там, тут лише
     * рахунок і застосування наслідку.
     *
     * Скільки натискань треба — {@code ConfigSchema.STAND_UP_PRESSES}.
     * Після КОЖНОГО натискання клієнт отримує оновлений прогрес, щоб
     * шкала вставання рухалась разом із гравцем, а не стрибала лише в
     * кінці.
     */
    public void onStandUpAttempt(ServerPlayer player) {
        UUID id = player.getUUID();
        int required = ManiacConfigs.get(ConfigSchema.STAND_UP_PRESSES);
        double presses = standUpPresses.merge(id, 1.0, Double::sum);

        if (presses < required) {
            sendStandUpProgress(player, (int) presses);
            return;
        }

        standUpPresses.remove(id);
        Boolean legBroken = pendingLegBreak.remove(id);
        SurvivorState next = Boolean.TRUE.equals(legBroken) ? SurvivorState.BROKEN_LEG : SurvivorState.HEALTHY;
        match().setSurvivorState(id, next);
        if (next == SurvivorState.BROKEN_LEG) {
            // Хрускіт кісток — гравець дізнається про перелом одразу, як
            // тільки встав (саме тут стан уперше стає BROKEN_LEG, не
            // раніше: до вставання гравець лежав/повзав і ще не знав,
            // ціла нога чи ні — те саме, що вирішує pendingLegBreak
            // вище). Ванільний позиційний playSound, а не SoundCenter:
            // подія серверна (тут, не в клієнтському хендлері), і має
            // бути чутна всім поруч, а не лише самому гравцю.
            player.level().playSound(null, player.blockPosition(),
                ModSounds.BONE_BREAK.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
            spawnLegBreakParticles(player);
        }
        // Гравець встав — рухається знову, з поламаною ногою чи без.
        unlockMovement(player);
        hideStandUpProgress(player);
        sendVitals(player, true);
        broadcastRosterFor(player);
    }

    /** Шле клієнту поточний прогрес вставання (0..required) — шкала показана. */
    private void sendStandUpProgress(ServerPlayer player, int presses) {
        int required = ManiacConfigs.get(ConfigSchema.STAND_UP_PRESSES);
        ModNetwork.toPlayer(player, new StandUpProgressPacket(Math.max(presses, 0), required));
    }

    /** Вставання не триває — клієнт ховає шкалу. */
    private void hideStandUpProgress(ServerPlayer player) {
        ModNetwork.toPlayer(player, StandUpProgressPacket.none());
    }

    /**
     * Викликається з {@link com.log_to_kot.maniacmod.items.SplintItem}
     * одразу ПІСЛЯ того, як {@code match().setSurvivorState(id, HEALTHY)}
     * зняв BROKEN_LEG. Сам перехід стану вже зупинив
     * {@link #tickBrokenLegStamina} (більше не BROKEN_LEG) — стаміна
     * відновлюється за звичайними правилами StaminaRules з наступного
     * тіку; тут лише форс-знімок HUD, щоб гравець одразу побачив зняту
     * іконку/розблоковану шкалу, не чекаючи throttled sendVitals.
     */
    public void onSplintApplied(ServerPlayer player) {
        restoreLegIntegrity(player.getUUID());
        sendVitals(player, true);
    }

    /**
     * Форс-знімок HUD одразу після дебаг-зміни хп ({@code /maniac hp set}) —
     * той самий патерн, що {@link #onSplintApplied}: команда вже змінила
     * число через {@code MatchOrchestrator}, тут лише гарантія, що клієнт
     * побачить нове значення негайно, а не після наступного throttled
     * тіку (sendVitals без force мовчить, якщо пакет "виглядає так само",
     * що між реальними ударами не проблема, але для дебаг-команди мало б
     * дивний вигляд "команда відпрацювала, а шкала не ворухнулась").
     */
    public void forceVitalsRefresh(ServerPlayer player) {
        sendVitals(player, true);
    }

    // ── Непритомність / підняття ────────────────────────────────────────

    /**
     * Гравець дійшов до 0 хп. Викликається звідти, де amount урону
     * фактично знімає хп (наразі — ManiacCombatModule.onAttack; коли
     * пастки перенесуть applyEffect, вони теж мають дзвонити сюди
     * замість напряму рішення "гравець вибув").
     */
    public void onSurvivorDowned(ServerPlayer player) {
        UUID id = player.getUUID();
        if (match().survivorStateOf(id) == SurvivorState.UNCONSCIOUS) return;
        match().setSurvivorState(id, SurvivorState.UNCONSCIOUS);
        rescuers.remove(id);
        rescueProgressTicks.remove(id);
        // Якщо гравця добили, поки він лежав і намагався встати —
        // прогрес вставання більше не має сенсу (він тепер непритомний,
        // а не "майже підвівся"), інакше шкала лишилась би на екрані.
        standUpPresses.remove(id);
        pendingLegBreak.remove(id);
        hideStandUpProgress(player);

        // Непритомний ПОВЗЕ, а не заморожений: знімаємо лок (якщо падав і
        // лежав у CRAWLING), замість нього — повільна швидкість. Позу
        // лежачого ставить міксин, стрибок забирає клієнтський міксин руху.
        unlockMovement(player);
        ensureCrawlSpeed(player);
        setDownedGlow(player, true);
        player.setSprinting(false);

        bleedOutTicksLeft.put(id, ManiacConfigs.get(ConfigSchema.DOWNED_BLEED_OUT_TICKS));
        sendVitals(player, true);
        broadcastRosterFor(player);
        // Мітку й позу всім клієнтам розішле найближчий tickDowned
        // (склад списку змінився) — окремо тут слати не треба.
    }

    /** Той самий сервер, що вже дає {@code onlineManiacOf} — зручність для форс-подій поза тіковим списком players. */
    private void broadcastRosterFor(ServerPlayer anyOnlinePlayerForServerAccess) {
        var server = anyOnlinePlayerForServerAccess.getServer();
        if (server != null) {
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(server.getPlayerList().getPlayers());
        }
    }

    /**
     * Гравця щойно добито/він щойно втік. Викликається ПЕРЕД
     * {@code match().markEliminated(...)}/{@code markEscaped(...)} —
     * після них {@code hpOf} уже повертає -1 і жертва не отримає
     * фінальний HUD-знімок свого стану.
     */
    public void onSurvivorLeftMatch(ServerPlayer player, SurvivorState finalState) {
        rescuers.remove(player.getUUID());
        rescueProgressTicks.remove(player.getUUID());
        pendingLegBreak.remove(player.getUUID());
        standUpPresses.remove(player.getUUID());
        legRulesApplied.remove(player.getUUID());
        bleedOutTicksLeft.remove(player.getUUID());
        removeCrawlSpeed(player);
        setDownedGlow(player, false);
        unlockMovement(player);
        hideStandUpProgress(player);
        sendVitals(player, finalState, 0f, true);
    }

    public void onRescueHold(ServerPlayer rescuer, boolean holding) {
        // Жертва — той непритомний гравець, біля якого зараз стоїть
        // рятівник. Позиція навмисно рахується тут, а не приймається
        // з пакета — той самий принцип, що вже застосований у
        // ServerPacketHandler.onTrapPlace (клієнт шле НАМІР, сервер
        // сам визначає ціль по факту, а не за словом гравця).
        if (!holding) {
            removeRescuer(rescuer.getUUID());
            return;
        }
        if (!canRescue(rescuer)) return;

        ServerPlayer victim = findNearestUnconscious(rescuer);
        if (victim == null) return;

        rescuers.computeIfAbsent(victim.getUUID(), k -> new HashSet<>()).add(rescuer.getUUID());
    }

    /**
     * Хто взагалі МОЖЕ піднімати: живий виживий, який сам стоїть. Раніше
     * цієї перевірки не було, і непритомний (або той, хто лежить після
     * падіння) міг піднімати іншого.
     */
    private boolean canRescue(ServerPlayer rescuer) {
        SurvivorState state = match().survivorStateOf(rescuer.getUUID());
        return state != null && !state.isCrawlOnly() && !state.isTerminal();
    }

    private void removeRescuer(UUID rescuerId) {
        for (var entry : rescuers.entrySet()) {
            entry.getValue().remove(rescuerId);
        }
    }

    private ServerPlayer findNearestUnconscious(ServerPlayer rescuer) {
        double rescueRange = ManiacConfigs.get(ConfigSchema.RESCUE_RANGE_BLOCKS);
        ServerPlayer nearest = null;
        double nearestDistSq = rescueRange * rescueRange;
        for (UUID survivorId : match().survivorIds()) {
            if (match().survivorStateOf(survivorId) != SurvivorState.UNCONSCIOUS) continue;
            if (survivorId.equals(rescuer.getUUID())) continue;
            ServerPlayer candidate = match().onlinePlayer(survivorId);
            if (candidate == null) continue;
            double distSq = rescuer.distanceToSqr(candidate);
            if (distSq <= nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    /**
     * Просуває всі активні рятувальні сесії на один тік. Кілька
     * рятівників пришвидшують НЕЛІНІЙНО (дизайн: двоє ≈ ×1.2, а не
     * ×2) — множник рахується як 1 + 0.2 * (rescuers - 1), рівно те
     * число, що назване в GAME_DESIGN.md, узагальнене на будь-яку
     * кількість рятівників через RESCUE_HELPER_BONUS з конфігу.
     *
     * Кожен тік з рятівників знімаються ті, хто відійшов далі за
     * дальність, помер чи сам ліг: клієнт шле «тримаю» лише при ЗМІНІ
     * (плюс рідкий повтор), тож без цього рятівник, що відійшов, лишався б
     * у сесії, поки не відпустить кнопку.
     *
     * ── Згасання ─────────────────────────────────────────────────────
     * Поки НІХТО не тримає, набраний прогрес щотіка спадає й за
     * {@code rescueDecayTicks} (5 с) доходить до нуля. Раніше він лишався
     * назавжди — почати підняття, піти й повернутись через хвилину було б
     * безкоштовним «збереженням».
     */
    private void tickRescues() {
        int requiredTicks = ManiacConfigs.get(ConfigSchema.RESCUE_TICKS);
        double helperBonus = ManiacConfigs.get(ConfigSchema.RESCUE_HELPER_BONUS);
        double rescueRange = ManiacConfigs.get(ConfigSchema.RESCUE_RANGE_BLOCKS);
        double pruneRange = rescueRange + RESCUE_RANGE_PRUNE_MARGIN;
        // Швидкість згасання стала: повний прогрес зникає рівно за
        // rescueDecayTicks, а неповний — відповідно швидше.
        double decayPerTick = (double) requiredTicks
            / Math.max(1, ManiacConfigs.get(ConfigSchema.RESCUE_DECAY_TICKS));

        var iterator = rescuers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID victimId = entry.getKey();
            Set<UUID> activeRescuers = entry.getValue();

            if (match().survivorStateOf(victimId) != SurvivorState.UNCONSCIOUS) {
                rescueProgressTicks.remove(victimId);
                iterator.remove();
                continue;
            }
            ServerPlayer victim = match().onlinePlayer(victimId);
            pruneRescuers(activeRescuers, victim, pruneRange);

            if (activeRescuers.isEmpty()) {
                // Ніхто не тримає — прогрес спадає. Порожній запис
                // прибираємо, коли прогрес дійшов до нуля: наступне
                // утримання створить його заново.
                if (!decayRescue(victimId, victim, decayPerTick, requiredTicks, rescueRange)) {
                    iterator.remove();
                }
                continue;
            }

            double multiplier = 1.0 + helperBonus * (activeRescuers.size() - 1);
            double progressed = rescueProgressTicks.merge(victimId, multiplier, Double::sum);

            sendRescueProgress(victim, activeRescuers, (int) progressed, requiredTicks);

            if (progressed >= requiredTicks) {
                rescueProgressTicks.remove(victimId);
                iterator.remove();
                if (victim != null) reviveDowned(victim);
            }
        }
    }

    /**
     * Знімає з прогресу один тік згасання. Шкалу бачать лежачий і ті, хто
     * поруч і міг би підняти: вони бачать, як вона тане, і розуміють, що
     * треба тримати далі (без цього рятівник, що відпустив, бачив би
     * підказку «Утримуй ПКМ» і не знав, що прогрес утікає).
     *
     * @return true, якщо прогрес ще лишився; false — дійшов до нуля
     */
    private boolean decayRescue(UUID victimId, ServerPlayer victim, double decayPerTick,
                                int requiredTicks, double range) {
        Double current = rescueProgressTicks.get(victimId);
        if (current == null) return false;

        double next = current - decayPerTick;
        if (next <= 0) {
            rescueProgressTicks.remove(victimId);
            return false;
        }
        rescueProgressTicks.put(victimId, next);
        sendRescueProgress(victim, survivorsWhoCanRescueNear(victim, range), (int) next, requiredTicks);
        return true;
    }

    /** Виживі, які стоять достатньо близько до лежачого й самі здатні піднімати. */
    private Set<UUID> survivorsWhoCanRescueNear(ServerPlayer victim, double range) {
        Set<UUID> result = new HashSet<>();
        if (victim == null) return result;
        double rangeSq = range * range;
        for (UUID id : match().survivorIds()) {
            if (id.equals(victim.getUUID())) continue;
            ServerPlayer candidate = match().onlinePlayer(id);
            if (candidate != null && canRescue(candidate) && candidate.distanceToSqr(victim) <= rangeSq) {
                result.add(id);
            }
        }
        return result;
    }

    private void pruneRescuers(Set<UUID> active, ServerPlayer victim, double range) {
        double rangeSq = range * range;
        active.removeIf(rescuerId -> {
            ServerPlayer rescuer = match().onlinePlayer(rescuerId);
            if (rescuer == null || victim == null) return true;
            if (!canRescue(rescuer)) return true;
            return rescuer.distanceToSqr(victim) > rangeSq;
        });
    }

    private void sendRescueProgress(ServerPlayer victim, Set<UUID> rescuerIds, int progress, int required) {
        if (victim != null) {
            ModNetwork.toPlayer(victim, new RescueProgressPacket(progress, required, true));
        }
        for (UUID rescuerId : rescuerIds) {
            ServerPlayer rescuer = match().onlinePlayer(rescuerId);
            if (rescuer != null) {
                ModNetwork.toPlayer(rescuer, new RescueProgressPacket(progress, required, false));
            }
        }
    }

    /**
     * Піднімає непритомного: він СТОЇТЬ, має {@code reviveHp} хп і може
     * бігти. Стадії CRAWLING (пробіл × N) тут немає — це шлях лише після
     * падіння; піднятий союзником одразу на ногах.
     *
     * Стаміна лишається нульовою (її тримало правило «непритомний»), а
     * щойно стан стає HEALTHY, звичайні правила повертаються і вона
     * відновлюється — піднятий гравець виснажений, а не свіжий.
     *
     * Не чіпає {@link #rescuers}: викликається під час ітерації по ній.
     */
    private void reviveDowned(ServerPlayer victim) {
        UUID id = victim.getUUID();
        bleedOutTicksLeft.remove(id);
        pendingLegBreak.remove(id);
        standUpPresses.remove(id);
        removeCrawlSpeed(victim);
        setDownedGlow(victim, false);

        match().setSurvivorState(id, SurvivorState.HEALTHY);
        match().healSurvivor(id, ManiacConfigs.get(ConfigSchema.REVIVE_HP));

        hideStandUpProgress(victim);
        sendVitals(victim, true);
        broadcastRosterFor(victim);
    }

    /**
     * Дебаг: піднімає непритомного гравця ТІЄЮ Ж дорогою, що звичайний
     * rescue іншим гравцем — викликає {@link #reviveDowned} напряму,
     * минаючи чекання {@code rescueTicks} утримання. Для
     * {@code /maniac revive} (перевірка HUD/станів без другого гравця,
     * що стоїть і тримає ПКМ вісім секунд).
     *
     * <p>Прибирає жертву з будь-якої активної сесії {@link #rescuers} /
     * {@link #rescueProgressTicks} ПЕРЕД підняттям — інакше наступний
     * {@link #tickRescues} тика пізніше побачив би запис про вже не
     * {@code UNCONSCIOUS} жертву в мапі рятівників і сам би це
     * прибрав, але зайвий тік розсилав би застарілий
     * {@code RescueProgressPacket} рятівнику, що й далі тримає кнопку.</p>
     *
     * @return true, якщо гравець дійсно був непритомний і його підняли;
     *         false — гравець не в стані UNCONSCIOUS, піднімати нічого.
     */
    public boolean debugRevive(ServerPlayer victim) {
        UUID id = victim.getUUID();
        if (match().survivorStateOf(id) != SurvivorState.UNCONSCIOUS) return false;

        rescueProgressTicks.remove(id);
        rescuers.remove(id);

        reviveDowned(victim);
        return true;
    }

    // ── Таймер до смерті непритомного ────────────────────────────────────

    /**
     * Раз на тік: віднімає час у кожного лежачого, вбиває тих, у кого він
     * вийшов, і розсилає клієнтам склад/позиції лежачих.
     *
     * Час стоїть для гравця, що вийшов із сервера, — він не може померти
     * офлайн, поки союзники не мають змоги його підняти.
     */
    private void tickDowned(List<ServerPlayer> players) {
        List<UUID> expired = new ArrayList<>();
        List<UUID> stale = new ArrayList<>();
        downedTickCounter++;
        mercyPaused.clear();
        int mercyRadius = ManiacConfigs.get(ConfigSchema.MANIAC_MERCY_RADIUS_BLOCKS);

        for (var entry : bleedOutTicksLeft.entrySet()) {
            UUID id = entry.getKey();
            if (match().survivorStateOf(id) != SurvivorState.UNCONSCIOUS) {
                // Стан змінили не ми (морф, вихід з ролі) — таймер більше
                // нічого не значить.
                stale.add(id);
                continue;
            }
            ServerPlayer player = match().onlinePlayer(id);
            if (player == null) continue; // офлайн: час стоїть

            // Ідемпотентно й дешево: повертає повільність після респавну/
            // релогу, які transient-модифікатор не переживають.
            ensureCrawlSpeed(player);

            // Маньяк стоїть над непритомним — час до смерті СТОЇТЬ, а маньяк
            // отримує підказку відійти. Так добити лежачого не можна, стоячи
            // поряд: треба фізично відпустити й перечекати поза радіусом.
            ServerPlayer maniac = maniacNear(player, players, mercyRadius);
            if (maniac != null) {
                mercyPaused.add(id);
                notifyManiacToStepAway(maniac, mercyRadius);
                continue;
            }

            int left = entry.getValue() - 1;
            if (left <= 0) {
                expired.add(id);
            } else {
                entry.setValue(left);
            }
        }

        // Видалення лише ПІСЛЯ ітерації: eliminateDowned чіпає bleedOutTicksLeft.
        for (UUID id : stale) bleedOutTicksLeft.remove(id);
        for (UUID id : expired) {
            ServerPlayer player = match().onlinePlayer(id);
            if (player != null) eliminateDowned(player);
        }

        broadcastDowned(players);
    }

    /**
     * Онлайн-маньяк у радіусі {@code radius} блоків від {@code victim}, або
     * {@code null}. Ітеруємо саме переданий список тіку, а не серверний — це
     * дешевше й не тягне новий пошук щоразу.
     */
    private ServerPlayer maniacNear(ServerPlayer victim, List<ServerPlayer> players, int radius) {
        if (radius <= 0) return null;
        MatchOrchestrator match = match();
        double radiusSq = (double) radius * radius;
        for (ServerPlayer candidate : players) {
            if (!match.isManiac(candidate.getUUID())) continue;
            if (candidate.level() != victim.level()) continue;
            if (candidate.distanceToSqr(victim) <= radiusSq) return candidate;
        }
        return null;
    }

    /**
     * Підказка маньяку в actionbar (shaurma-lib) — раз на секунду, поки він
     * стоїть над непритомним. Мова береться з клієнта (пакет несе ключ і
     * аргументи, не готовий текст) — той самий контракт, що в
     * {@code ManiacStunModule.notify}.
     */
    private void notifyManiacToStepAway(ServerPlayer maniac, int radius) {
        if (downedTickCounter % 20 != 0) return;
        ModNetwork.toPlayer(maniac, new ActionBarPacket(
            ActionBarMessageType.INFO, "maniacmod.maniac.step_away",
            new String[] { String.valueOf(radius) }));
    }

    /**
     * Згасання прогресу вставання. Поки гравець у CRAWLING і не тисне пробіл,
     * прогрес щотіка спадає й за {@code standUpDecayTicks} доходить до нуля;
     * саме тому встати одним-двома натисканнями неможливо. Прогрес
     * надсилається клієнту лише коли змінюється ЙОГО ЦІЛА частина (те, що
     * видно на шкалі), а не щотік.
     */
    private void tickStandUpProgress(List<ServerPlayer> players) {
        if (standUpPresses.isEmpty()) return;
        int required = ManiacConfigs.get(ConfigSchema.STAND_UP_PRESSES);
        double decayPerTick = required
            / (double) Math.max(1, ManiacConfigs.get(ConfigSchema.STAND_UP_DECAY_TICKS));

        var it = standUpPresses.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID id = entry.getKey();
            ServerPlayer player = match().onlinePlayer(id);
            // Стан змінився (встав/збили) або гравець зник — прогрес більше
            // нічого не значить.
            if (player == null || match().survivorStateOf(id) != SurvivorState.CRAWLING) {
                it.remove();
                continue;
            }

            double next = entry.getValue() - decayPerTick;
            if (next <= 0.0) {
                it.remove();
                hideStandUpProgress(player);
                continue;
            }

            int before = entry.getValue().intValue();
            int after = (int) next;
            entry.setValue(next);
            if (after != before) sendStandUpProgress(player, after);
        }
    }

    /**
     * Шле всім клієнтам список лежачих: одразу, коли склад змінився, і раз на
     * {@link #DOWNED_BROADCAST_INTERVAL_TICKS}, поки він непорожній (щоб
     * позиції й таймери не застарівали). Порожній список, що змінився
     * (останнього підняли/він помер), теж іде — він гасить мітки й позу.
     */
    private void broadcastDowned(List<ServerPlayer> players) {
        Set<UUID> current = bleedOutTicksLeft.keySet();
        // Стан паузи — теж частина «що бачать клієнти»: щойно маньяк підійшов,
        // треба негайно повідомити клієнтам зафіксувати таймер, не чекаючи
        // наступного разу, коли зміниться склад лежачих.
        boolean changed = !current.equals(lastBroadcastDowned)
            || !mercyPaused.equals(lastBroadcastPaused);
        if (!changed) {
            if (current.isEmpty()) return;
            if (++downedBroadcastCounter < DOWNED_BROADCAST_INTERVAL_TICKS) return;
        }
        downedBroadcastCounter = 0;
        lastBroadcastDowned.clear();
        lastBroadcastDowned.addAll(current);
        lastBroadcastPaused.clear();
        lastBroadcastPaused.addAll(mercyPaused);

        List<DownedSurvivorsPacket.Entry> entries = new ArrayList<>();
        for (var entry : bleedOutTicksLeft.entrySet()) {
            ServerPlayer player = match().onlinePlayer(entry.getKey());
            if (player == null) continue;
            entries.add(new DownedSurvivorsPacket.Entry(
                entry.getKey(), player.getX(), player.getY(), player.getZ(), entry.getValue(),
                mercyPaused.contains(entry.getKey())));
        }
        ModNetwork.toPlayers(players, new DownedSurvivorsPacket(entries));
    }

    // ── Смерть ───────────────────────────────────────────────────────────

    /**
     * Непритомний помирає: вичерпано таймер ({@link #tickDowned}). Удар
     * маньяка лежачого НЕ вбиває — {@code ManiacCombatModule.onAttack}
     * такі удари ігнорує, тож цей метод — єдина дорога до смерті лежачого.
     *
     * Саме вмирання йде через {@link SurvivorDeathSequence}: коли там
     * з'явиться анімація, {@link #finishElimination} запуститься по її
     * кінці. Мітка {@link #dying} не пускає другу смерть, поки перша триває.
     */
    private void eliminateDowned(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!match().isSurvivor(id)) return;
        if (!dying.add(id)) return;

        bleedOutTicksLeft.remove(id);
        Vec3 bodyPosition = player.position();
        deathSequence.play(player, bodyPosition, () -> finishElimination(player));
    }

    private void finishElimination(ServerPlayer player) {
        UUID id = player.getUUID();
        dying.remove(id);
        // Анімація могла тривати довше, ніж гравець лишався в матчі.
        if (!match().isSurvivor(id)) return;

        dropInventoryAround(player);

        // onSurvivorLeftMatch — ДО markEliminated: після нього hpOf уже -1
        // і жертва не отримала б фінальний знімок стану.
        onSurvivorLeftMatch(player, SurvivorState.ELIMINATED);
        match().markEliminated(player);

        becomeSpectator(player);
        broadcastRosterFor(player);
    }

    /**
     * Розсипає ВЕСЬ інвентар навколо тіла — так само, як гравець викидає
     * предмет на Q, тільки не вперед, а довкола. Якщо світ не прийняв
     * сутність, предмет усе одно не губиться: падає ванільним спавном.
     */
    private void dropInventoryAround(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            inventory.setItem(slot, ItemStack.EMPTY);
            if (!GroundItemSpawner.dropAround(player, stack)) {
                player.spawnAtLocation(stack);
            }
        }
    }

    /**
     * Загиблий стає глядачем. Клієнт отримує роль SPECTATOR, щоб HUD виживого
     * зник, а вихідний режим гри запам'ятовується — у лобі його повертає
     * {@link #restoreGameModes}, інакше гравець лишався б глядачем назавжди.
     */
    private void becomeSpectator(ServerPlayer player) {
        gameModeBeforeSpectating.putIfAbsent(player.getUUID(), player.gameMode.getGameModeForPlayer());
        player.setGameMode(GameType.SPECTATOR);
        ModNetwork.toPlayer(player, new RoleSyncPacket(RoleSyncPacket.Role.SPECTATOR, "", 0.0));
    }

    private void restoreGameModes(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            GameType previous = gameModeBeforeSpectating.remove(player.getUUID());
            if (previous != null) player.setGameMode(previous);
        }
        // Хто був офлайн — лишається в мапі: режим повернеться, коли його
        // побачить наступний скид.
    }

    // ── Повзання ─────────────────────────────────────────────────────────

    /**
     * Непритомному потрібен рух, але повільний: замість лока — множник
     * швидкості. Це атрибутний модифікатор на СЕРВЕРІ, який ванільна
     * синхронізація атрибутів сама доставляє клієнту (тому клієнт рухається
     * повільно без окремого пакета).
     *
     * ── Чому перевіряємо саме ЗНАЧЕННЯ, а не лише наявність ────────────
     * Раніше тут був ранній вихід лише за фактом наявності модифікатора:
     * якщо конфіг {@code downedCrawlSpeed} змінювався, поки гравець УЖЕ
     * непритомний (а не при новому падінні), старий коефіцієнт лишався
     * приліпленим до атрибута аж до наступного разу. Тепер застарілий
     * модифікатор знімається й ставиться заново з поточним конфігом.
     */
    private void ensureCrawlSpeed(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        double factor = ManiacConfigs.get(ConfigSchema.DOWNED_CRAWL_SPEED);

        AttributeModifier existing = speed.getModifier(DOWNED_SPEED_MODIFIER_ID);
        if (existing != null) {
            if (existing.getAmount() == factor - 1.0) return;
            speed.removeModifier(DOWNED_SPEED_MODIFIER_ID);
        }
        speed.addTransientModifier(new AttributeModifier(
            DOWNED_SPEED_MODIFIER_ID, "maniacmod_downed_crawl",
            factor - 1.0, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private void removeCrawlSpeed(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(DOWNED_SPEED_MODIFIER_ID);
    }

    /** Назва команди, чий колір контуру — той, яким підсвічено непритомних. Спільна для всіх гравців. */
    private static final String DOWNED_GLOW_TEAM = "maniac_survivor_downed";

    /**
     * Вмикає/вимикає ванільний контур непритомності на гравцеві —
     * той самий підхід, що {@code GeneratorEntity.setExplosionGlow}:
     * {@code setGlowingTag} малює контур крізь стіни всім, хто бачить
     * сутність, без жодного клієнтського рендер-коду. Колір контуру —
     * колір команди, тому на час непритомності гравець входить у
     * спільну команду цього кольору.
     *
     * На відміну від генераторної підсвітки (навмисно лише клієнту, що
     * натиснув 5, бо там приховувати стан від маньяка — частина
     * дизайну), тут видимість УСІМ — саме те, що потрібно: непритомний
     * і без того показаний маньяку через {@link DownedSurvivorMarker}
     * (екранна мітка), тож світіння нічого додатково не розкриває, а
     * лише робить самого гравця видимим крізь стіни так само, як мітку.
     */
    private void setDownedGlow(ServerPlayer player, boolean on) {
        player.setGlowingTag(on);

        Scoreboard scoreboard = player.serverLevel().getScoreboard();
        String member = player.getScoreboardName();
        PlayerTeam team = scoreboard.getPlayerTeam(DOWNED_GLOW_TEAM);
        if (on) {
            if (team == null) {
                team = scoreboard.addPlayerTeam(DOWNED_GLOW_TEAM);
                team.setColor(ChatFormatting.GOLD);
            }
            scoreboard.addPlayerToTeam(member, team);
        } else if (team != null && scoreboard.getPlayersTeam(member) == team) {
            scoreboard.removePlayerFromTeam(member, team);
        }
    }

    /**
     * Стани, у яких шкала стаміни ЗАВЖДИ на нулі й не відновлюється: поламана
     * нога (до Шини) і непритомний (не бігає взагалі). Одне джерело правди
     * для правил стаміни, щотікового притискання й того, що бачить HUD.
     */
    private static boolean staminaLocked(SurvivorState state) {
        return state == SurvivorState.BROKEN_LEG || state == SurvivorState.UNCONSCIOUS;
    }

    // ── Серцебиття ───────────────────────────────────────────────────────

    /**
     * 0.0 за межами радіуса, 1.0 впритул до маньяка — крива, а не
     * лінія: близькість відчутніша, ніж пропорційна відстань (той
     * самий ефект, що дає гучність у грі — останні кілька блоків
     * "б'ють" сильніше, ніж перші).
     */
    private float computeHeartbeat(ServerPlayer survivor, ServerPlayer maniac) {
        if (maniac == null) return 0f;
        int range = ManiacConfigs.get(ConfigSchema.HEARTBEAT_RANGE_BLOCKS);
        if (range <= 0) return 0f;

        double distance = survivor.distanceTo(maniac);
        if (distance >= range) return 0f;

        float linear = 1f - (float) (distance / range);
        return Mth.clamp(linear * linear, 0f, 1f);
    }

    private ServerPlayer maniacOf(List<ServerPlayer> players) {
        MatchOrchestrator match = match();
        for (ServerPlayer player : players) {
            if (match.isManiac(player.getUUID())) return player;
        }
        return null;
    }

    // ── HUD (SurvivorVitalsPacket) ──────────────────────────────────────

    private void sendVitals(ServerPlayer player, boolean force) {
        sendVitals(player, computeHeartbeat(player, onlineManiacOf(player)), force);
    }

    private void sendVitals(ServerPlayer player, float heartbeat, boolean force) {
        sendVitals(player, match().survivorStateOf(player.getUUID()), heartbeat, force);
    }

    /**
     * @param stateOverride стан, що йде в пакет замість
     *                       {@code match().survivorStateOf(id)} — потрібен
     *                       рівно один раз, для {@link #onSurvivorLeftMatch},
     *                       коли контекст уже не знає про гравця як про
     *                       виживого, але HUD жертви все одно має побачити
     *                       ELIMINATED/ESCAPED, а не мовчки застиглий стан.
     */
    private void sendVitals(ServerPlayer player, SurvivorState stateOverride, float heartbeat, boolean force) {
        UUID id = player.getUUID();
        int hp = match().hpOf(id);
        int maxHp = match().maxHpOf(id);
        boolean leavingMatch = stateOverride != null && stateOverride.isTerminal();
        if ((hp < 0 || maxHp < 0) && !leavingMatch) return; // не виживий — нема що слати

        // Поламана нога: шкала стаміни ЗАВЖДИ на нулі, доки ногу не
        // вилікують — незалежно від того, що зараз каже StaminaService
        // (див. tickBrokenLegStamina: між нашим тіком і END-тіком
        // бібліотеки стаміна могла встигнути трохи відновитись, і
        // HUD блимав би ненульовим значенням). Правило дизайну живе
        // в одному місці — тут воно застосовується до того, що бачить
        // гравець, а в tickBrokenLegStamina — до того, що рахує сервер.
        SurvivorState state = stateOverride != null ? stateOverride : match().survivorStateOf(id);
        if (state == null) state = SurvivorState.HEALTHY;

        float stamina = staminaLocked(state)
            ? 0f
            : StaminaService.isEnabled() && StaminaService.getMaxStamina(player) > 0
                ? StaminaService.getStamina(player) / StaminaService.getMaxStamina(player)
                : 0f;

        boolean trapped = match().traps().isTrapped(id);
        SurvivorVitalsPacket packet = new SurvivorVitalsPacket(
            Math.max(hp, 0), Math.max(maxHp, 0), stamina, state, heartbeat, trapped);
        if (!force && packet.equals(lastSentVitals.get(id))) return;

        lastSentVitals.put(id, packet);
        ModNetwork.toPlayer(player, packet);
    }

    /**
     * Маньяк щойно вдарив виживого (не добив до непритомності).
     *
     * ── Дизайн ────────────────────────────────────────────────────────
     * Удар дає жертві коротке вікно адреналіну: ефект швидкості I
     * ({@code maniacHitSpeedTicks}, за замовчуванням 5 с) і ПОВНУ стаміну.
     * Поранений може рвонути геть — саме тому удар вигідний маньяку лише
     * тоді, коли поруч немає куди тікати.
     *
     * Для непритомного не застосовується: його стаміна все одно замкнена
     * на нулі ({@link #staminaLocked}), а швидкість ходьби замінена на
     * повзання — ефект був би невидимим і лише збивав би стан.
     */
    public void onManiacHit(ServerPlayer victim) {
        UUID id = victim.getUUID();
        if (!match().isSurvivor(id)) return;
        SurvivorState state = match().survivorStateOf(id);
        if (state == null || state.isCrawlOnly() || state.isTerminal()) return;

        int speedTicks = ManiacConfigs.get(ConfigSchema.MANIAC_HIT_SPEED_TICKS);
        if (speedTicks > 0) {
            // ambient=false, visible=false, showIcon=true: у куточку ефектів
            // гравець має бачити, що отримав прискорення, але без частинок навколо.
            victim.addEffect(new MobEffectInstance(
                MobEffects.MOVEMENT_SPEED, speedTicks, 0, false, false, true));
        }

        if (StaminaService.isEnabled() && StaminaService.getMaxStamina(victim) > 0) {
            StaminaService.setStamina(victim, StaminaService.getMaxStamina(victim), true);
        }
        sendVitals(victim, true);
    }

    /**
     * Шукає онлайн-маньяка через сервер гравця-довідника — використовується
     * лише у force-подіях (падіння, вставання, downed, rescue), де під
     * рукою немає готового {@code List<ServerPlayer>} з поточного тіку.
     * Гаряча щотікова розсилка (onPhaseTick) використовує maniacOf(players)
     * і не викликає це.
     */
    private ServerPlayer onlineManiacOf(ServerPlayer anyOnlinePlayerForServerAccess) {
        var server = anyOnlinePlayerForServerAccess.getServer();
        if (server == null) return null;
        MatchOrchestrator match = match();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (match.isManiac(p.getUUID())) return p;
        }
        return null;
    }

    private MatchOrchestrator match() {
        return matchSupplier.get();
    }
}

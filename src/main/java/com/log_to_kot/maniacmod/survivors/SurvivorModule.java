package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.StandUpProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.forge.stamina.StaminaRules;
import dev.shaurmalib.forge.stamina.StaminaService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

import java.util.HashMap;
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
 */
public final class SurvivorModule implements PhaseListener {

    private final Supplier<MatchOrchestrator> matchSupplier;
    private final Random rng = new Random();

    /** Останній HUD-знімок, надісланий кожному гравцю — щоб не слати пакет щотік без змін. */
    private final Map<UUID, SurvivorVitalsPacket> lastSentVitals = new HashMap<>();

    /** Хто зараз піднімає кого: жертва → множина рятівників, що утримують Shift біля неї. */
    private final Map<UUID, java.util.Set<UUID>> rescuers = new HashMap<>();

    /** Прогрес підняття жертви, у тіках утримання (накопичується, поки хоч один рятівник тримає). */
    private final Map<UUID, Integer> rescueProgressTicks = new HashMap<>();

    /**
     * Причина локу руху для InteractionLock (shaurma-lib) — гравець
     * лежить (CRAWLING) чи непритомний (UNCONSCIOUS) і фізично не
     * може ходити/повертатись, доки не встане чи його не піднімуть.
     * Той самий підхід, що {@code ManiacCombatModule.LOCK_REASON}:
     * рядок-ключ, за яким саме цей лок знімається, а не чужий.
     */
    private static final String DOWNED_MOVEMENT_LOCK_REASON = "survivor_downed";

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
                hideStandUpProgress(player);
            }
            rescuers.clear();
            rescueProgressTicks.clear();
            legRulesApplied.clear();
            pendingLegBreak.clear();
            standUpPresses.clear();
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

        ServerPlayer maniac = maniacOf(players);
        for (ServerPlayer player : players) {
            if (!match().isSurvivor(player.getUUID())) continue;

            tickBrokenLegStamina(player);
            float heartbeat = computeHeartbeat(player, maniac);
            sendVitals(player, heartbeat, false);
        }

        // Рятування, втеча й таб-ростер — лише справжня ігрова логіка.
        if (!vitalsPhase) return;

        if (phase.allows(PhaseRule.RESCUE)) tickRescues();
        if (phase.allows(PhaseRule.ESCAPE)) tickEscapes(players);

        // Throttled: hp міняється поступово (урон/лікування), не
        // щотік — раз на секунду досить, щоб таб не відставав помітно.
        if (++rosterBroadcastCounter >= ROSTER_BROADCAST_INTERVAL_TICKS) {
            rosterBroadcastCounter = 0;
            com.log_to_kot.maniacmod.server.ServerHooks.broadcastRoster(players);
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
        boolean broken = match().survivorStateOf(player.getUUID()) == SurvivorState.BROKEN_LEG;
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
        standUpPresses.remove(player.getUUID());
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
        standUpPresses.remove(id);
        legRulesApplied.remove(id);
        lastSentVitals.remove(id);
        rescuers.remove(id);
        rescueProgressTicks.remove(id);
        removeRescuer(id);
    }

    // ── Лок руху (CRAWLING / UNCONSCIOUS) ───────────────────────────────

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
        boolean broken = match().survivorStateOf(player.getUUID()) == SurvivorState.BROKEN_LEG;
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
     * Єдиний вхід у стан CRAWLING — і з падіння ({@link #onFall}), і з
     * порятунку непритомного ({@link #tickRescues}). Раніше ці два шляхи
     * дублювали одні й ті самі чотири рядки, і порятунок забував би
     * будь-який новий крок (наприклад показ шкали вставання).
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

    /** Накопичені натискання пробілу поточної спроби встати. */
    private final Map<UUID, Integer> standUpPresses = new HashMap<>();

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
        int presses = standUpPresses.merge(id, 1, Integer::sum);

        if (presses < required) {
            sendStandUpProgress(player, presses);
            return;
        }

        standUpPresses.remove(id);
        Boolean legBroken = pendingLegBreak.remove(id);
        SurvivorState next = Boolean.TRUE.equals(legBroken) ? SurvivorState.BROKEN_LEG : SurvivorState.HEALTHY;
        match().setSurvivorState(id, next);
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
        if (match().survivorStateOf(player.getUUID()) == SurvivorState.UNCONSCIOUS) return;
        match().setSurvivorState(player.getUUID(), SurvivorState.UNCONSCIOUS);
        rescuers.remove(player.getUUID());
        rescueProgressTicks.remove(player.getUUID());
        // Якщо гравця добили, поки він лежав і намагався встати —
        // прогрес вставання більше не має сенсу (він тепер непритомний,
        // а не "майже підвівся"), інакше шкала лишилась би на екрані.
        standUpPresses.remove(player.getUUID());
        pendingLegBreak.remove(player.getUUID());
        hideStandUpProgress(player);
        // Непритомний не рухається сам — той самий лок, що CRAWLING
        // (той самий reason: ідемпотентно, якщо вже лежав із поламаною
        // ногою до цього, зняття станеться рівно один раз).
        lockMovement(player);
        sendVitals(player, true);
        broadcastRosterFor(player);
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

        ServerPlayer victim = findNearestUnconscious(rescuer);
        if (victim == null) return;

        rescuers.computeIfAbsent(victim.getUUID(), k -> new java.util.HashSet<>()).add(rescuer.getUUID());
    }

    private void removeRescuer(UUID rescuerId) {
        for (var entry : rescuers.entrySet()) {
            entry.getValue().remove(rescuerId);
        }
    }

    private ServerPlayer findNearestUnconscious(ServerPlayer rescuer) {
        double rescueRange = 3.0;
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
     */
    private void tickRescues() {
        int requiredTicks = ManiacConfigs.get(ConfigSchema.RESCUE_TICKS);
        double helperBonus = ManiacConfigs.get(ConfigSchema.RESCUE_HELPER_BONUS);

        var iterator = rescuers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID victimId = entry.getKey();
            java.util.Set<UUID> activeRescuers = entry.getValue();

            if (match().survivorStateOf(victimId) != SurvivorState.UNCONSCIOUS) {
                rescueProgressTicks.remove(victimId);
                iterator.remove();
                continue;
            }
            if (activeRescuers.isEmpty()) continue;

            double multiplier = 1.0 + helperBonus * (activeRescuers.size() - 1);
            int progressed = rescueProgressTicks.merge(victimId, (int) Math.round(multiplier), Integer::sum);

            if (progressed >= requiredTicks) {
                ServerPlayer victim = match().onlinePlayer(victimId);
                rescueProgressTicks.remove(victimId);
                iterator.remove();
                if (victim != null) {
                    // Піднятий гравець завжди встає HEALTHY: onFall не
                    // викликався для нього (він втратив свідомість від
                    // удару/пастки, не від падіння), тому pendingLegBreak
                    // для нього порожній і onStandUpAttempt однаково
                    // повернув би HEALTHY — прибираємо тут явно.
                    pendingLegBreak.remove(victimId);
                    beginCrawling(victim);
                }
            }
        }
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

        float stamina = state == SurvivorState.BROKEN_LEG
            ? 0f
            : StaminaService.isEnabled() && StaminaService.getMaxStamina(player) > 0
                ? StaminaService.getStamina(player) / StaminaService.getMaxStamina(player)
                : 0f;

        SurvivorVitalsPacket packet = new SurvivorVitalsPacket(Math.max(hp, 0), Math.max(maxHp, 0), stamina, state, heartbeat);
        if (!force && packet.equals(lastSentVitals.get(id))) return;

        lastSentVitals.put(id, packet);
        ModNetwork.toPlayer(player, packet);
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

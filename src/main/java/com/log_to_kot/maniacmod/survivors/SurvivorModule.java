package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket;
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

    /** Накопичені натискання пробілу для вставання після CRAWLING. */
    private static final int STAND_UP_PRESSES_REQUIRED = 1;

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
        }
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        if (phase.isGameplay()) {
            // Матч завершується (перехід у ENDING) — стаміна вимикається
            // для всіх, рятувальні сесії обриваються. Хп/стан лишаються
            // в MatchContext до RESET: підсумковий екран може захотіти
            // показати останній стан.
            for (ServerPlayer player : players) StaminaService.clear(player);
            rescuers.clear();
            rescueProgressTicks.clear();
        }
        if (phase == GamePhase.RESET || phase == GamePhase.LOBBY) {
            lastSentVitals.clear();
            rosterBroadcastCounter = 0;
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (!phase.allows(PhaseRule.SURVIVOR_VITALS)) return;

        ServerPlayer maniac = maniacOf(players);
        for (ServerPlayer player : players) {
            if (!match().isSurvivor(player.getUUID())) continue;

            tickBrokenLegStamina(player);
            float heartbeat = computeHeartbeat(player, maniac);
            sendVitals(player, heartbeat, false);
        }

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
        StaminaService.setRules(player, buildStaminaRules());
    }

    /**
     * Конфіг тримає темп "за тік" (staminaDrainPerTick — частка від
     * 0..1 шкали, помножена на 20 тіків/сек), StaminaRules очікує
     * "одиниць за секунду" на шкалі 0..100 (maxStamina=100 — той сам
     * вимір, що очікує SurvivorVitalsPacket.stamina() 0.0–1.0 після
     * ділення на maxStamina у sendVitals).
     */
    private StaminaRules buildStaminaRules() {
        float drainPerSecond = (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_DRAIN_PER_TICK) * 20f * 100f;
        float regenPerSecond = (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_REGEN_PER_TICK) * 20f * 100f;
        return StaminaRules.builder()
            .active(true)
            .maxStamina(100f)
            .drainPerSecond(drainPerSecond)
            .recoveryPerSecond(regenPerSecond)
            .emptyRecoveryDelaySeconds(1.0f)
            .recoveryDelaySeconds(0.5f)
            .recoveryEnabled(true)
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
     * доки предмет "Шина" не поверне стан у HEALTHY (див. TODO у
     * items/README.md — SplintItem ще не мігровано; коли з'явиться,
     * він викликає match.setSurvivorState(uuid, HEALTHY), і цей метод
     * з наступного тіку перестає притискати стаміну).
     */
    private void tickBrokenLegStamina(ServerPlayer player) {
        if (match().survivorStateOf(player.getUUID()) != SurvivorState.BROKEN_LEG) return;
        if (StaminaService.getStamina(player) > 0) {
            StaminaService.setStamina(player, 0, false);
        }
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
     * @return true, якщо цей модуль обробив падіння сам — викликач
     *         скасовує ванільний урон від падіння в будь-якому разі
     *         (тут або тому, що впав виживий і ми замінили ефект своїм,
     *         або тому, що ванільний урон у грі не діє взагалі).
     */
    public boolean onFall(ServerPlayer player, float fallDistanceBlocks) {
        if (!match().phases().allows(PhaseRule.SURVIVOR_VITALS)) return false;
        if (!match().isSurvivor(player.getUUID())) return false;

        int knockdownHeight = ManiacConfigs.get(ConfigSchema.FALL_KNOCKDOWN_HEIGHT);
        if (fallDistanceBlocks < knockdownHeight) return false;

        // Уже лежить/повзе — повторне падіння з тієї самої висоти не
        // додає новий стан поверх наявного.
        SurvivorState current = match().survivorStateOf(player.getUUID());
        if (current == SurvivorState.UNCONSCIOUS) return true;

        double legBreakChance = ManiacConfigs.get(ConfigSchema.LEG_BREAK_CHANCE);
        boolean legBroken = rng.nextDouble() < legBreakChance;

        // Падіння завжди збиває з ніг: гравець повзе, доки не натисне
        // пробіл (onStandUpAttempt нижче). Чи зламана нога вирішується
        // зараз, але застосовується лише ПІСЛЯ вставання — так дизайн
        // "CRAWLING = лежить, BROKEN_LEG = ходить без стаміни" не
        // конфліктує: це два послідовні стани, не одночасні.
        pendingLegBreak.put(player.getUUID(), legBroken);
        match().setSurvivorState(player.getUUID(), SurvivorState.CRAWLING);
        standUpPresses.remove(player.getUUID());
        sendVitals(player, true);
        return true;
    }

    /** UUID → чи зламається нога, коли гравець підведеться з поточного CRAWLING. */
    private final Map<UUID, Boolean> pendingLegBreak = new HashMap<>();

    /** Накопичені натискання пробілу поточної спроби встати. */
    private final Map<UUID, Integer> standUpPresses = new HashMap<>();

    /**
     * Спроба встати (пробіл). Викликається з {@code ServerPacketHandler}
     * — та сама перевірка стану CRAWLING вже зроблена там, тут лише
     * рахунок і застосування наслідку.
     */
    public void onStandUpAttempt(ServerPlayer player) {
        UUID id = player.getUUID();
        int presses = standUpPresses.merge(id, 1, Integer::sum);
        if (presses < STAND_UP_PRESSES_REQUIRED) return;

        standUpPresses.remove(id);
        Boolean legBroken = pendingLegBreak.remove(id);
        SurvivorState next = Boolean.TRUE.equals(legBroken) ? SurvivorState.BROKEN_LEG : SurvivorState.HEALTHY;
        match().setSurvivorState(id, next);
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
                    match().setSurvivorState(victimId, SurvivorState.CRAWLING);
                    standUpPresses.remove(victimId);
                    sendVitals(victim, true);
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

        float stamina = StaminaService.isEnabled() && StaminaService.getMaxStamina(player) > 0
            ? StaminaService.getStamina(player) / StaminaService.getMaxStamina(player)
            : 0f;
        SurvivorState state = stateOverride != null ? stateOverride : SurvivorState.HEALTHY;

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

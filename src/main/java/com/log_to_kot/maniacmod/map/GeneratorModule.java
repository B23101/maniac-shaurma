package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Генератори: сесії ремонту, залив бензину, підсвітка.
 *
 * ── Що це замінює з v3 ───────────────────────────────────────────────
 * ManiacGameManager.refreshRepair / tickRepairSessions / doTickRepair /
 * hideRepairProgress / deactivateGenerator — приблизно 120 рядків,
 * вплетених між пастками й боєм. Тепер це самостійний модуль, який
 * можна читати цілком, не гортаючи чужу логіку.
 *
 * ── Сесія ремонту ────────────────────────────────────────────────────
 * Блок генератора викликає {@link #refreshRepair} щоразу, поки гравець
 * тримає ПКМ. Модуль сам розуміє, що гравець відпустив кнопку: якщо
 * виклику не було більше ніж GRACE_TICKS, сесія закривається.
 *
 * Це той самий підхід, що у v3, і він тут навмисно збережений — він
 * працює й не вимагає окремого пакета «я відпустив».
 *
 * ── Чому Supplier<MatchOrchestrator>, а не Supplier<MatchContext> ────
 * MatchContext навмисно package-private (core.match) — цей модуль
 * живе в іншому пакеті й фізично не може його імпортувати. Увесь
 * доступ до стану матчу йде через вузькі фасадні методи оркестратора
 * ({@code generators()}, {@code generatorAt(pos)}...). Якщо колись
 * знадобиться нове поле стану — метод додається на фасад, а не
 * пробивається окремий імпорт MatchContext.
 */
public final class GeneratorModule implements PhaseListener {

    /** Скільки тіків без виклику вважати «гравець відпустив ПКМ». */
    private static final int GRACE_TICKS = 6;

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** UUID гравця → сесія ремонту. */
    private final Map<UUID, RepairSession> sessions = new HashMap<>();

    private long tick = 0;

    public GeneratorModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private static final class RepairSession {
        final BlockPos pos;
        long lastTouchTick;

        RepairSession(BlockPos pos, long tick) {
            this.pos = pos;
            this.lastTouchTick = tick;
        }
    }

    @Override
    public String id() {
        return "generators";
    }

    // ── Фази ─────────────────────────────────────────────────────────────

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // Будь-який вихід з ігрової фази закриває всі сесії. Інакше
        // гравець, що ремонтував у момент завершення матчу, лишався б
        // із висячим прогрес-баром (саме це робив v3).
        if (!phase.isGameplay()) return;
        for (ServerPlayer player : players) hideProgress(player);
        sessions.clear();
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        tick++;
        if (!phase.allows(PhaseRule.GENERATOR_REPAIR)) {
            if (!sessions.isEmpty()) sessions.clear();
            return;
        }
        tickSessions(players);
        tickGenerators();
    }

    // ── Ремонт ───────────────────────────────────────────────────────────

    /** Викликається з GeneratorBlock.use() поки гравець тримає ПКМ. */
    public void refreshRepair(ServerPlayer player, BlockPos pos) {
        sessions.compute(player.getUUID(), (uuid, existing) -> {
            if (existing != null && existing.pos.equals(pos)) {
                existing.lastTouchTick = tick;
                return existing;
            }
            // Гравець переключився на інший генератор — старий бар геть.
            if (existing != null) hideProgress(player);
            return new RepairSession(pos, tick);
        });
    }

    private void tickSessions(List<ServerPlayer> players) {
        Iterator<Map.Entry<UUID, RepairSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RepairSession> entry = it.next();

            ServerPlayer player = find(players, entry.getKey());
            if (player == null) { it.remove(); continue; }

            if (tick - entry.getValue().lastTouchTick > GRACE_TICKS) {
                hideProgress(player);
                it.remove();
                continue;
            }

            tickOne(player, entry.getValue().pos);
        }
    }

    /**
     * Один тік роботи над генератором.
     *
     * На відміну від v3, генератор НЕ створюється на льоту, якщо його
     * немає в списку: генератори існують тільки там, де їх розмітили.
     * У v3 через це кількість «активних генераторів» могла перевищити
     * задуману.
     */
    private void tickOne(ServerPlayer player, BlockPos pos) {
        GeneratorPoi generator = findGenerator(pos);
        if (generator == null) {
            hideProgress(player);
            sessions.remove(player.getUUID());
            return;
        }
        if (generator.isCompleted()) {
            hideProgress(player);
            sessions.remove(player.getUUID());
            return;
        }

        // TODO(дизайн): тут вмикається міні-гра ремонту (стадія REPAIR)
        // і залив каністри (стадія FUEL). Числа — з ConfigSchema.
        // Модуль уже знає, у якій стадії генератор, і решті мода не
        // треба цього знати.

        sendProgress(player, generator);
    }

    private void tickGenerators() {
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            generator.tick();
        }
    }

    // ── Підсвітка ────────────────────────────────────────────────────────

    /**
     * Відповідь на клавішу 5. Один пакет зі станами всіх генераторів;
     * клієнт сам гасить підсвітку через HIGHLIGHT_DURATION_TICKS.
     */
    public void sendHighlight(ServerPlayer player) {
        List<GeneratorHighlightPacket.Entry> entries = new ArrayList<>();
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            entries.add(new GeneratorHighlightPacket.Entry(
                generator.pos(), generator.visualState()));
        }
        ModNetwork.toPlayer(player, new GeneratorHighlightPacket(
            ManiacConfigs.get(ConfigSchema.HIGHLIGHT_DURATION_TICKS), entries));
    }

    // ── Саботаж ──────────────────────────────────────────────────────────

    /** Маньяк зламав генератор — скидається залив, ремонт лишається. */
    public void sabotage(BlockPos pos) {
        GeneratorPoi generator = findGenerator(pos);
        if (generator != null) generator.sabotage();
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    public GeneratorPoi findGenerator(BlockPos pos) {
        return matchSupplier.get().generatorAt(pos);
    }

    private void sendProgress(ServerPlayer player, GeneratorPoi generator) {
        boolean repairStage = generator.stage() == GeneratorPoi.Stage.REPAIR;
        ModNetwork.toPlayer(player, new GeneratorProgressPacket(
            true,
            repairStage ? 0 : 1,
            generator.progressPercent(),
            generator.minigamesPassed(),
            ManiacConfigs.get(ConfigSchema.MINIGAMES_PER_GENERATOR),
            generator.fuelPercent()));
    }

    private void hideProgress(ServerPlayer player) {
        ModNetwork.toPlayer(player, GeneratorProgressPacket.hidden());
    }

    private static ServerPlayer find(List<ServerPlayer> players, UUID uuid) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) return player;
        }
        return null;
    }
}

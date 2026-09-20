package com.log_to_kot.maniacmod.map.minigame;

import net.minecraft.core.BlockPos;

/**
 * Стан однієї активної міні-гри на сервері — одна на гравця, живе в
 * {@link com.log_to_kot.maniacmod.map.GeneratorModule} доти, доки
 * гравець не завершить її (успіх/провал/розрив зв'язку/вихід за межі
 * дистанції). Поки ця сесія існує, {@link com.log_to_kot.maniacmod.map.GeneratorModule}
 * НЕ додає REPAIR-прогрес від імені цього гравця через звичайне
 * утримання ПКМ — його внесок призупинений (див. клас-докстрінг
 * {@link com.log_to_kot.maniacmod.map.zones.GeneratorPoi}), інші
 * гравці на тому самому генераторі тим часом лагодять як завжди.
 */
public final class ActiveRepairMinigame {

    private final BlockPos generatorPos;
    private final RepairMinigameType type;

    /** Ненульове лише для TARGET — параметри конкретного запуску. */
    private final TargetMinigameSpec targetSpec;
    /** Ненульове лише для WIRES — розкладка конкретного запуску. */
    private final WireMinigameLayout wireLayout;

    /** Скільки влучень уже зараховано (лише TARGET). */
    private int targetHits = 0;

    /** З яким правим слотом зараз з'єднаний кожен лівий слот (лише WIRES); -1 = не з'єднаний. */
    private final int[] wireConnections;

    /** Скільки тіків минуло з моменту старту — для ліміту часу WIRES. */
    private int ticksElapsed = 0;

    /**
     * Коли (годинник сервера, мс) міні-гру відкрито. Потрібен для
     * перевірки кліку TARGET у РЕАЛЬНОМУ часі, а не в тіках: за лагу
     * сервера тіки розтягуються, а клієнт рахує повзунок за реальним
     * годинником.
     */
    private final long openedAtMillis = System.currentTimeMillis();

    public ActiveRepairMinigame(BlockPos generatorPos, TargetMinigameSpec spec) {
        this.generatorPos = generatorPos;
        this.type = RepairMinigameType.TARGET;
        this.targetSpec = spec;
        this.wireLayout = null;
        this.wireConnections = null;
    }

    public ActiveRepairMinigame(BlockPos generatorPos, WireMinigameLayout layout) {
        this.generatorPos = generatorPos;
        this.type = RepairMinigameType.WIRES;
        this.targetSpec = null;
        this.wireLayout = layout;
        this.wireConnections = new int[WireMinigameLayout.SLOT_COUNT];
        for (int i = 0; i < wireConnections.length; i++) {
            wireConnections[i] = layout.initialRightSlotForLeft(i);
        }
    }

    public BlockPos generatorPos()          { return generatorPos; }
    public RepairMinigameType type()        { return type; }
    public TargetMinigameSpec targetSpec()  { return targetSpec; }
    public WireMinigameLayout wireLayout()  { return wireLayout; }
    public int targetHits()                 { return targetHits; }

    /** Один тік — лише для ліміту часу WIRES. @return true якщо час вийшов (провал). */
    public boolean tickAndCheckTimeout() {
        if (type != RepairMinigameType.WIRES) return false;
        ticksElapsed++;
        return ticksElapsed >= com.log_to_kot.maniacmod.config.ManiacConfigs.get(
            com.log_to_kot.maniacmod.config.ConfigSchema.WIRE_MINIGAME_TICKS);
    }

    /**
     * Реєструє клік по цілі в міні-грі TARGET.
     *
     * Позиція з пакета НЕ береться на віру: спершу перевіряється, чи
     * могла вона бути позицією повзунка (див.
     * {@link TargetMinigameSpec#isPlausible}); вигадана позиція — це
     * промах. Лише потім звіряється з зоною влучання.
     *
     * @param claimedCursorPosition позиція повзунка (0.0-1.0), яку
     *                              заявив клієнт на момент кліку.
     * @param lagToleranceMs        допуск затримки мережі, мс.
     * @return {@code HIT_PROGRESS} якщо влучив і ще не вистачає
     *         влучень, {@code SUCCESS} якщо це було останнє потрібне
     *         влучення, {@code FAIL} якщо промах або неправдива позиція.
     */
    public Result registerTargetClick(double claimedCursorPosition, long lagToleranceMs) {
        if (type != RepairMinigameType.TARGET) return Result.FAIL;
        long elapsedMs = System.currentTimeMillis() - openedAtMillis;
        if (!targetSpec.isPlausible(claimedCursorPosition, elapsedMs, lagToleranceMs)) return Result.FAIL;
        if (!targetSpec.isHit(claimedCursorPosition)) return Result.FAIL;
        targetHits++;
        return targetHits >= targetSpec.hitsRequired() ? Result.SUCCESS : Result.HIT_PROGRESS;
    }

    /**
     * Реєструє перетягування дроту {@code leftSlot} на правий контакт
     * {@code targetRightSlot}.
     *
     * @return {@code SUCCESS} якщо це був останній правильний дріт,
     *         {@code WIRE_CONNECTED} якщо колір правильний, але це не
     *         останній дріт, {@code FAIL} якщо колір неправильний.
     */
    public Result registerWireDrop(int leftSlot, int targetRightSlot) {
        if (type != RepairMinigameType.WIRES) return Result.FAIL;
        if (!wireLayout.isCorrectPair(leftSlot, targetRightSlot)) return Result.FAIL;
        wireConnections[leftSlot] = targetRightSlot;
        for (int i = 0; i < wireConnections.length; i++) {
            if (!wireLayout.isCorrectPair(i, wireConnections[i])) return Result.WIRE_CONNECTED;
        }
        return Result.SUCCESS;
    }

    public enum Result { HIT_PROGRESS, WIRE_CONNECTED, SUCCESS, FAIL }
}

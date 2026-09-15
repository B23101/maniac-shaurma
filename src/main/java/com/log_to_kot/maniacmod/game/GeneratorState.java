package com.log_to_kot.maniacmod.game;

import com.log_to_kot.maniacmod.config.ManiacConfig;
import net.minecraft.core.BlockPos;

import java.util.Random;

/**
 * Відстежує прогрес ремонту одного генератора.
 *
 * Генератор потребує N етапів ремонту (налаштовується в конфігу).
 * Після кожного успішного етапу — лічильник stageDone++.
 * Є шанс зриву (failChance) — при зриві поточний прогрес скидається.
 *
 * Налаштування (config/maniacmod-server.toml):
 *   repairsRequired     — кількість етапів (default 5)
 *   repairFailChance    — шанс зриву 0.0–1.0 (default 0.15)
 *   repairTicksPerStage — тіків на 1 етап (default 200 = 10 сек)
 */
public class GeneratorState {

    public static final int TOTAL_GENERATORS = 5;

    private static final Random RNG = new Random();

    private final BlockPos pos;

    /** Скільки етапів вже завершено */
    private int  stagesDone     = 0;

    /** Прогрес поточного етапу в тіках */
    private int  stageTicks     = 0;

    /** Чи вже повністю активний */
    private boolean active      = false;

    /** Чи щойно стався зрив (для повідомлення гравцю) */
    private boolean justFailed  = false;

    public GeneratorState(BlockPos pos) { this.pos = pos; }

    public BlockPos getPos()       { return pos; }
    public boolean  isActive()     { return active; }
    public boolean  justFailed()   { boolean v = justFailed; justFailed = false; return v; }
    public int      getStagesDone(){ return stagesDone; }
    public int      getStagesRequired() { return ManiacConfig.getRepairsRequired(); }

    /**
     * Загальний відсоток прогресу генератора (0–100).
     * Враховує всі етапи.
     */
    public int getTotalProgress() {
        int required = ManiacConfig.getRepairsRequired();
        int ticksPerStage = ManiacConfig.getRepairTicksPerStage();
        int totalTicks = required * ticksPerStage;
        int doneTicks  = stagesDone * ticksPerStage + stageTicks;
        return Math.min(100, doneTicks * 100 / totalTicks);
    }

    /**
     * Відсоток прогресу поточного етапу (0–100).
     */
    public int getStageProgress() {
        int ticksPerStage = ManiacConfig.getRepairTicksPerStage();
        return Math.min(100, stageTicks * 100 / ticksPerStage);
    }

    /**
     * Додає 1 тік ремонту.
     * @return true коли генератор повністю активований
     */
    public boolean addProgress(int ticks) {
        if (active) return false;

        stageTicks += ticks;

        int ticksPerStage = ManiacConfig.getRepairTicksPerStage();

        if (stageTicks >= ticksPerStage) {
            // Етап завершений — перевіряємо шанс зриву
            double failChance = ManiacConfig.getRepairFailChance();
            if (failChance > 0.0 && RNG.nextDouble() < failChance) {
                // ── ЗРИВ ──────────────────────────────────────────────────
                stageTicks = 0;
                justFailed = true;
                return false;
            }

            // ── УСПІХ ─────────────────────────────────────────────────────
            stageTicks = 0;
            stagesDone++;

            if (stagesDone >= ManiacConfig.getRepairsRequired()) {
                active = true;
                return true;
            }
        }

        return false;
    }
}

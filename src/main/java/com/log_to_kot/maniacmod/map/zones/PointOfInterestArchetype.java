package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;

/**
 * Базовий контракт для ТОЧКОВИХ об'єктів карти — на відміну від
 * ZoneArchetype (просторова зона з радіусом), точка інтересу — це
 * один конкретний блок з прогресом (генератор, двері виходу).
 *
 * Спільне для генератора й виходу: прогрес 0-100%, лічильник
 * тіків взаємодії, скидання прогресу. v3 game/GeneratorState.java
 * і game/ExitState.java дублювали цю логіку кожен по-своєму
 * (getProgress() рахувався по-різному) — тут один спільний метод.
 */
public abstract class PointOfInterestArchetype {

    protected final BlockPos pos;
    protected int progressTicks = 0;
    protected boolean completed = false;

    protected PointOfInterestArchetype(BlockPos pos) {
        this.pos = pos;
    }

    /** Тіків, потрібних для завершення (100% прогресу) — задає підклас. */
    protected abstract int ticksNeeded();

    /** Прогрес у відсотках (0-100) — одна формула для генератора й виходу. */
    public final int progressPercent() {
        return Math.min(100, progressTicks * 100 / ticksNeeded());
    }

    /**
     * Не final навмисно: генератор завершується не за прогресом, а
     * за проходженням ДВОХ стадій (ремонт + бензин) — див. GeneratorPoi.
     * Вихід (ExitPoi) лишає базову поведінку "прогрес дійшов до 100%".
     */
    public boolean isCompleted() { return completed; }
    public final BlockPos pos()        { return pos; }

    /** Додає тіки прогресу; повертає true якщо щойно досягнуто 100%. */
    public boolean addProgress(int ticks) {
        if (completed) return false;
        progressTicks += ticks;
        if (progressTicks >= ticksNeeded()) {
            completed = true;
            return true;
        }
        return false;
    }
}

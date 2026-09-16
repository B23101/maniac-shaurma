package com.log_to_kot.maniacmod.map.zones;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;

/**
 * Генератор. v3-еквівалент: game/GeneratorState.java, але модель
 * складніша — зі схеми "Генератори" генератор проходить ДВІ стадії:
 *
 *   Стадія 1 — REPAIR: лагодження. Складається з міні-ігор.
 *              Провалена міні-гра скидає прогрес ПОТОЧНОЇ міні-гри,
 *              але вже пройдені зберігаються (у v3 зрив скидав
 *              тільки поточний етап — поведінка збігається).
 *   Стадія 2 — FUEL: залив бензину. Треба залити 200% запасу,
 *              одна каністра дає 100%.
 *
 * Генератор вважається завершеним лише після обох стадій.
 *
 * Підсвітка (клавіша "5" у виживого) читає visualState():
 *   IDLE     білий   — звичайна підсвітка
 *   REPAIRED жовтий  — генератор зараз ремонтують
 *   FAILED   червоний— щойно стався зрив (коротко)
 *   DONE     зелений — повністю готовий
 * Колір рахується ТУТ, а не в рендері — щоб клієнт і сервер не
 * могли розійтися в тому, що показано.
 */
public class GeneratorPoi extends PointOfInterestArchetype {

    public enum Stage { REPAIR, FUEL, DONE }

    public enum VisualState { IDLE, IN_PROGRESS, FAILED, DONE }

    private Stage stage = Stage.REPAIR;

    /** Скільки міні-ігор ремонту вже пройдено. */
    private int minigamesPassed = 0;

    /** Залитий бензин у відсотках (0 … FUEL_REQUIRED_PERCENT). */
    private int fuelPercent = 0;

    private VisualState visualState = VisualState.IDLE;

    /** Скільки тіків ще показувати червону підсвітку після зриву. */
    private int failedFlashTicks = 0;

    public GeneratorPoi(BlockPos pos) {
        super(pos);
    }

    @Override
    protected int ticksNeeded() {
        return ManiacConfigs.get(ConfigSchema.MINIGAMES_PER_GENERATOR)
             * ManiacConfigs.get(ConfigSchema.MINIGAME_TICKS);
    }

    /** Скільки відсотків треба залити. Читається з конфігу щоразу. */
    public static int fuelRequiredPercent() {
        return ManiacConfigs.get(ConfigSchema.FUEL_REQUIRED_PERCENT);
    }

    // ── Стадії ───────────────────────────────────────────────────────────

    public Stage stage()                { return stage; }
    public int minigamesPassed()        { return minigamesPassed; }
    public int fuelPercent()            { return fuelPercent; }
    public VisualState visualState()    { return visualState; }

    /** Успішно пройдена міні-гра ремонту. */
    public void passMinigame() {
        if (stage != Stage.REPAIR) return;
        minigamesPassed++;
        visualState = VisualState.IN_PROGRESS;
        if (minigamesPassed >= ManiacConfigs.get(ConfigSchema.MINIGAMES_PER_GENERATOR)) {
            stage = Stage.FUEL;
        }
    }

    /**
     * Провалена міні-гра. За дизайном уже пройдені міні-ігри НЕ
     * скидаються — втрачається лише поточна.
     */
    public void failMinigame() {
        if (stage != Stage.REPAIR) return;
        visualState = VisualState.FAILED;
        failedFlashTicks = ManiacConfigs.get(ConfigSchema.FAIL_FLASH_TICKS);
    }

    /** Залив однієї каністри. @return true якщо генератор щойно завершено. */
    public boolean addFuel(int percent) {
        if (stage != Stage.FUEL) return false;
        int required = fuelRequiredPercent();
        fuelPercent = Math.min(required, fuelPercent + percent);
        visualState = VisualState.IN_PROGRESS;
        if (fuelPercent >= required) {
            stage = Stage.DONE;
            visualState = VisualState.DONE;
            return true;
        }
        return false;
    }

    @Override
    public boolean isCompleted() {
        return stage == Stage.DONE;
    }

    /** Маньяк зламав генератор — скидає стадію заливу, ремонт лишається. */
    public void sabotage() {
        if (stage == Stage.DONE) return;
        fuelPercent = 0;
        visualState = VisualState.FAILED;
    }

    public void tick() {
        if (failedFlashTicks > 0 && --failedFlashTicks == 0 && stage != Stage.DONE) {
            visualState = VisualState.IDLE;
        }
    }
}

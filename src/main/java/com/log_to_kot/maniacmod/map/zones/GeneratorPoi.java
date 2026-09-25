package com.log_to_kot.maniacmod.map.zones;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;

/**
 * Генератор. v3-еквівалент: game/GeneratorState.java, але модель
 * складніша — зі схеми "Генератори" генератор проходить ДВІ стадії:
 *
 *   Стадія 1 — REPAIR: лагодження. Пряме утримання ПКМ дає прогрес
 *              0-100% (див. {@link #addRepairProgress}). Відпустив —
 *              прогрес НЕ скидається, просто чекає, поки продовжать.
 *
 *              Поверх цього прогресу час від часу (шанс на тік, окремо
 *              для кожного гравця, що зараз лагодить) на КОНКРЕТНОГО
 *              гравця випадає одна з двох міні-ігор —
 *              {@link com.log_to_kot.maniacmod.map.minigame.RepairMinigameType#WIRES}
 *              ("з'єднай кольорові дроти за 10 сек") або
 *              {@link com.log_to_kot.maniacmod.map.minigame.RepairMinigameType#TARGET}
 *              ("влуч у ціль повзунком, що бігає, 3 влучення без жодного
 *              промаху"). Поки цей гравець не завершить міні-гру, ЙОГО
 *              особистий внесок у прогрес призупинений, а сам ремонт
 *              генератора — тимчасово недоступний для ВСІХ (див.
 *              {@code ConfigSchema.MINIGAME_BLOCKS_REPAIR}): решта
 *              отримує actionbar-повідомлення «тут іде міні-гра» й
 *              упирається в паузу, бо інакше скілл-чек одного гравця
 *              нічого не важив би — прогресу додали б чужі руки.
 *              Провал будь-якої з двох ігор — це ВИБУХ
 *              генератора (не знищення сутності): -10% (за
 *              замовчуванням) від вже накопиченого СПІЛЬНОГО
 *              REPAIR-прогресу, партикли вибуху й вогню на самому
 *              генераторі та червона підсвітка на 5 секунд, яку
 *              бачать УСІ гравці, включно з маньяком.
 *              Бензин (FUEL) вибух не чіпає взагалі.
 *              {@link #explode(int)} — єдина точка входу для обох
 *              міні-ігор.
 *   Стадія 2 — FUEL: залив бензину. Треба залити FUEL_REQUIRED_PERCENT
 *              (за дизайном 200%) запасу. Бензин береться із заряду
 *              каністри в руці гравця, 1 до 1: скільки відсотків
 *              додалось генератору, стільки ж списалось із каністри
 *              (див. {@code FuelCanisterItem} і
 *              {@code GeneratorModule.tickFuel}). Каністра постійна й має
 *              власний заряд 0-100%, тож скільки їх знадобиться —
 *              залежить лише від заряду, що є в гравців; ця модель про
 *              розмір каністри нічого не знає й знати не повинна.
 *
 * Генератор вважається завершеним лише після обох стадій.
 *
 * Підсвітка (клавіша "5" у виживого) — кольори станів:
 *   IDLE        білий   — не полагоджений, ніхто не працює
 *   IN_PROGRESS жовтий  — хтось лагодить, заливає бензин чи розбирається з
 *                         міні-грою ПРОСТО ЗАРАЗ
 *   FAILED      червоний— щойно вибухнув (коротко)
 *   DONE        зелений — повністю готовий
 *
 * ⚠ Колір для підсвітки обирає {@code GeneratorModule.highlightStateOf}, а НЕ
 * поле {@link #visualState()}: воно ставиться при будь-якому прогресі й не
 * гасне, коли всі пішли, тож недоремонтований генератор лишався б жовтим
 * назавжди. «Працюють зараз» знають лише активні сесії модуля. Саме поле
 * {@link #visualState()} тут лишається як внутрішній маркер прогресу.
 * Палітру (самі кольори) задає {@code ClientMatchState.highlightColor} —
 * єдине місце, щоб HUD і світ не розійшлися.
 */
public class GeneratorPoi extends PointOfInterestArchetype {

    public enum Stage { REPAIR, FUEL, DONE }

    public enum VisualState { IDLE, IN_PROGRESS, FAILED, DONE }

    private Stage stage = Stage.REPAIR;

    /**
     * Прогрес лагодження у ТІКАХ утримання (0 … {@link #repairTicksRequired()}).
     *
     * ── Чому тіки, а не цілі відсотки ─────────────────────────────
     * Раніше прогрес був цілим відсотком, а швидкість рахувалась як
     * {@code max(1, ceil(100 / (сек·20)))}. Для будь-яких налаштувань
     * від 5 с і вище це давало 1%/тік, тобто рівно 100 тіків (5 с)
     * незалежно від {@code repairSecondsPerStage}: 90 с у конфігу
     * ніколи не діяли. Тік — найменша одиниця часу, яку взагалі можна
     * «додати», тож облік у тіках точний за будь-якого значення, а
     * відсоток лише виводиться ({@link #repairPercent()}).
     */
    private int repairTicks = 0;

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
        // Лишається для сумісності з PointOfInterestArchetype (ExitPoi
        // теж читає ticksNeeded через прогрес-формулу) — GeneratorPoi
        // рахує власний прогрес у repairTicks і цю формулу не використовує.
        return 100;
    }

    /** Скільки відсотків треба залити. Читається з конфігу щоразу. */
    public static int fuelRequiredPercent() {
        return ManiacConfigs.get(ConfigSchema.FUEL_REQUIRED_PERCENT);
    }

    // ── Стадії ───────────────────────────────────────────────────────────

    public Stage stage()                { return stage; }
    /** Прогрес лагодження, 0-100 (виводиться з тіків, округлення вниз). */
    public int repairPercent() {
        return (int) Math.min(100L, (long) repairTicks * 100L / repairTicksRequired());
    }
    public int fuelPercent()            { return fuelPercent; }
    public VisualState visualState()    { return visualState; }

    /**
     * Скільки тіків суцільного утримання потрібно, щоб пройти REPAIR
     * з 0% до 100%. Джерело правди — {@code repairSecondsPerStage}.
     */
    public static int repairTicksRequired() {
        return Math.max(1, ManiacConfigs.get(ConfigSchema.REPAIR_SECONDS_PER_STAGE) * 20);
    }

    /**
     * Один тік утримання Shift+ПКМ під час лагодження. Прогрес іде
     * ЛИШЕ поки гравець тримає кнопку; відпустив — не скидається
     * (лагодження, на відміну від міні-гри, не карає за паузу).
     *
     * @param ticks скільки тіків додати. Зараз завжди 1; параметр
     *              лишається, щоб бонуси інструментів (викрутка)
     *              могли множити внесок, не змінюючи цю модель.
     */
    public void addRepairProgress(int ticks) {
        if (stage != Stage.REPAIR) return;
        repairTicks = Math.min(repairTicksRequired(), repairTicks + Math.max(0, ticks));
        // Червоний стан вибуху не затираємо, поки він триває: інші гравці
        // можуть далі лагодити, і без цього FAILED зникав би за один тік.
        if (failedFlashTicks == 0) visualState = VisualState.IN_PROGRESS;
        if (repairTicks >= repairTicksRequired()) {
            stage = Stage.FUEL;
            // FUEL — це наступна стадія, а не завершення: підсвітка
            // лишається "в процесі", а не "готово", доки не заллють бак.
        }
    }

    /**
     * ВИБУХ генератора — наслідок проваленої міні-гри ремонту (промах у
     * «ціль», невірний дріт чи вихід за час у «дроти»). Знімає
     * частину вже накопиченого СПІЛЬНОГО прогресу лагодження і вмикає
     * червоний стан {@link VisualState#FAILED} на {@code failFlashTicks}
     * (за замовчуванням 100 тіків = 5 с). Тут лише дані: партикли, звук,
     * контур сутності й екранний маркер запускає {@code GeneratorModule}
     * за {@link #explosionTicksLeft()}.
     *
     * Бензин (FUEL) вибух не чіпає: {@code fuelPercent} не змінюється.
     * Вибухнути можна лише на стадії REPAIR — саме тоді існують
     * міні-ігри.
     *
     * @param lossPercent скільки відсотків ПОВНОГО ремонту зняти
     *                    (за замовчуванням 10 — MINIGAME_FAIL_LOSS_PERCENT).
     * @return true, якщо вибух відбувся (стадія REPAIR).
     */
    public boolean explode(int lossPercent) {
        if (stage != Stage.REPAIR) return false;
        int lossTicks = (int) ((long) repairTicksRequired() * Math.max(0, lossPercent) / 100L);
        repairTicks = Math.max(0, repairTicks - lossTicks);
        visualState = VisualState.FAILED;
        failedFlashTicks = Math.max(1, ManiacConfigs.get(ConfigSchema.FAIL_FLASH_TICKS));
        return true;
    }

    /**
     * Додає {@code percent} відсотків бензину, затискаючи підсумок до
     * {@link #fuelRequiredPercent()}. Скільки саме реально прийнято —
     * викликач бачить як різницю {@link #fuelPercent()} до/після (саме
     * так {@code GeneratorModule.tickFuel} вираховує, скільки списати із
     * каністри).
     *
     * @return true якщо генератор щойно повністю завершено (обидві
     *         стадії пройдено).
     */
    public boolean addFuel(int percent) {
        if (stage != Stage.FUEL) return false;
        int required = fuelRequiredPercent();
        fuelPercent = Math.min(required, fuelPercent + percent);
        if (failedFlashTicks == 0) visualState = VisualState.IN_PROGRESS;
        if (fuelPercent >= required) {
            stage = Stage.DONE;
            visualState = VisualState.DONE;
            completed = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean isCompleted() {
        return stage == Stage.DONE;
    }

    /**
     * Дебаг: перестрибує ОБИДВІ стадії одразу до DONE, минаючи звичайний
     * шлях {@link #addRepairProgress}/{@link #addFuel} — для
     * {@code /maniac generators complete}. На відміну від виклику обох
     * методів по черзі (довелося б підганяти під точні порогові значення
     * тіків/відсотків), тут стан просто ставиться напряму: прогрес
     * REPAIR і FUEL — на максимумі, стадія й видимий стан — DONE.
     *
     * Немає ефекту, якщо генератор уже завершено (idempotent — команда,
     * що зачіпає всі генератори матчу, не має сенсу "довершувати" вже
     * готові вдруге).
     *
     * @return true, якщо цей виклик щойно завершив генератор (був не-DONE).
     */
    public boolean forceComplete() {
        if (stage == Stage.DONE) return false;
        repairTicks = repairTicksRequired();
        fuelPercent = fuelRequiredPercent();
        stage = Stage.DONE;
        visualState = VisualState.DONE;
        failedFlashTicks = 0;
        completed = true;
        return true;
    }

    /**
     * Раз на тік. Гасить червоний стан після вибуху; повертає
     * {@link VisualState#IN_PROGRESS}, якщо в генератора вже є прогрес,
     * інакше {@link VisualState#IDLE}.
     */
    public void tick() {
        if (failedFlashTicks > 0 && --failedFlashTicks == 0 && stage != Stage.DONE) {
            boolean hasProgress = repairTicks > 0 || fuelPercent > 0 || stage == Stage.FUEL;
            visualState = hasProgress ? VisualState.IN_PROGRESS : VisualState.IDLE;
        }
    }

    /** Скільки тіків ще триває червоний стан вибуху (0 — не вибухає). */
    public int explosionTicksLeft() {
        return failedFlashTicks;
    }
}

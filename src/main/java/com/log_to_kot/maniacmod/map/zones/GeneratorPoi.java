package com.log_to_kot.maniacmod.map.zones;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;

/**
 * Генератор. v3-еквівалент: game/GeneratorState.java, але модель
 * складніша — зі схеми "Генератори" генератор проходить ДВІ стадії.
 *
 * ── Порядок стадій ───────────────────────────────────────────────────
 * Типово (ConfigSchema.GENERATOR_STAGE_ORDER = FUEL_THEN_REPAIR) спершу
 * заливається бензин, і лише потім генератор лагодиться; значення
 * REPAIR_THEN_FUEL повертає зворотний порядок. Порядок читається з
 * конфігу ОДИН РАЗ — при створенні генератора — і далі не змінюється:
 * зміна конфігу посеред матчу не повинна перевертати стадії в
 * генератора, що вже частково залитий чи полагоджений.
 *
 * Нижче стадії описані за змістом, а не за порядком. Міні-ігри, вибух і
 * викрутка стосуються лише REPAIR — незалежно від того, першою вона йде
 * чи другою.
 *
 *   Стадія REPAIR: лагодження. Пряме утримання ПКМ дає прогрес
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
 *   Стадія FUEL: залив бензину. Треба залити FUEL_REQUIRED_PERCENT
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

    /** true — спершу бензин, потім ремонт (див. «Порядок стадій» у докстрінгу класу). */
    private final boolean fuelFirst;

    private Stage stage;

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

    /**
     * true, якщо ЦЬОГО тіку {@code GeneratorModule.tickFuel} реально
     * викликався для когось (заряджена каністра в руці, стадія FUEL) —
     * прямий сигнал "зараз ліють", а не вгадування з приросту
     * округленого {@code stagePercent()}. Раніше клієнтська поза
     * "idle_fuel" (GeneratorEntity) визначала залив за тим, чи виріс
     * округлений 0-100 відсоток між тіками — але fuelGainForTick
     * (Бресенхем-розклад rate/сек на 20 тіків) реально приймає
     * ненульовий приріст лише РАЗ НА N тіків, тож округлений відсоток
     * стоїть на місці довше за будь-яке розумне grace-вікно, і поза
     * блимала idle_fuel/idle посеред безперервної заливки. Прапор тут —
     * пряма правда з того самого місця, де заряд ФАКТИЧНО списується,
     * без жодного округлення чи grace.
     */
    private boolean fillingThisTick = false;

    private VisualState visualState = VisualState.IDLE;

    /** Скільки тіків ще показувати червону підсвітку після зриву. */
    private int failedFlashTicks = 0;

    public GeneratorPoi(BlockPos pos) {
        super(pos);
        this.fuelFirst = "FUEL_THEN_REPAIR".equals(ManiacConfigs.get(ConfigSchema.GENERATOR_STAGE_ORDER));
        this.stage = fuelFirst ? Stage.FUEL : Stage.REPAIR;
    }

    /** Стадія, з якої генератор починає (FUEL або REPAIR — залежно від порядку). */
    public Stage firstStage() {
        return fuelFirst ? Stage.FUEL : Stage.REPAIR;
    }

    /** Стадія, якою генератор закінчується (протилежна {@link #firstStage()}). */
    public Stage lastStage() {
        return fuelFirst ? Stage.REPAIR : Stage.FUEL;
    }

    /**
     * Скільки стадій уже ПРОЙДЕНО: 0 — на першій, 1 — на другій, 2 — готово.
     * Клієнту це потрібно для підпису «етапи», не вгадуючи порядок сам.
     */
    public int stagesDone() {
        if (stage == Stage.DONE) return 2;
        return stage == firstStage() ? 0 : 1;
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

    /** Хтось РЕАЛЬНО ліє прямо зараз (див. {@link #fillingThisTick} докстрінг). */
    public boolean isFillingThisTick()  { return fillingThisTick; }

    /** Викликає {@code GeneratorModule.tickFuel} на кожному реальному тіку заливки. */
    public void markFillingThisTick()   { fillingThisTick = true; }

    /**
     * Скидає прапор перед фазою тіку сесій — викликає
     * {@code GeneratorModule} ПЕРЕД тим, як пройтись по гравцях, щоб
     * якщо цього тіку ніхто не тримає заливку, прапор лишився
     * {@code false} без ручного стеження за "хто саме" вже перевіряв.
     */
    public void resetFillingTick()      { fillingThisTick = false; }

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
     * @return true, якщо генератор щойно повністю завершено — тобто ремонт
     *         був ДРУГОЮ стадією (порядок FUEL_THEN_REPAIR). Коли ремонт
     *         іде першим, завершення (перехід до заливу) — не кінець, і
     *         повертається false.
     */
    public boolean addRepairProgress(int ticks) {
        if (stage != Stage.REPAIR) return false;
        repairTicks = Math.min(repairTicksRequired(), repairTicks + Math.max(0, ticks));
        // Червоний стан вибуху не затираємо, поки він триває: інші гравці
        // можуть далі лагодити, і без цього FAILED зникав би за один тік.
        if (failedFlashTicks == 0) visualState = VisualState.IN_PROGRESS;
        if (repairTicks < repairTicksRequired()) return false;

        if (fuelFirst) return finish();
        // Ремонт першою стадією: далі заливка, а не завершення — підсвітка
        // лишається "в процесі", а не "готово", доки не заллють бак.
        stage = Stage.FUEL;
        return false;
    }

    /** Ставить остаточний стан «готово». @return true — щойно завершено. */
    private boolean finish() {
        stage = Stage.DONE;
        visualState = VisualState.DONE;
        completed = true;
        return true;
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
     * Бензин (FUEL) вибух не чіпає: {@code fuelPercent} не змінюється
     * (за типового порядку він на той час уже повністю залитий).
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
     * @return true якщо генератор щойно повністю завершено — тобто заливка
     *         була ДРУГОЮ стадією (порядок REPAIR_THEN_FUEL). Коли бензин
     *         іде першим, повний бак відкриває ремонт і повертається false.
     */
    public boolean addFuel(int percent) {
        if (stage != Stage.FUEL) return false;
        int required = fuelRequiredPercent();
        fuelPercent = Math.min(required, fuelPercent + percent);
        if (failedFlashTicks == 0) visualState = VisualState.IN_PROGRESS;
        if (fuelPercent < required) return false;

        if (fuelFirst) {
            // Бак повний — далі ремонт. Підсвітка лишається "в процесі".
            stage = Stage.REPAIR;
            return false;
        }
        return finish();
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
            // Друга стадія означає, що перша вже пройдена — прогрес є.
            boolean hasProgress = repairTicks > 0 || fuelPercent > 0 || stage != firstStage();
            visualState = hasProgress ? VisualState.IN_PROGRESS : VisualState.IDLE;
        }
    }

    /** Скільки тіків ще триває червоний стан вибуху (0 — не вибухає). */
    public int explosionTicksLeft() {
        return failedFlashTicks;
    }
}
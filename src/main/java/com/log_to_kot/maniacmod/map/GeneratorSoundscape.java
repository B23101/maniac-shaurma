package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import com.log_to_kot.maniacmod.sound.WorldSound;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Звукове життя генераторів: запуск, гул роботи й залив бензину.
 *
 * ── Три звуки ────────────────────────────────────────────────────────
 * <pre>
 *   подія             коли                             радіус
 *   ───────────────   ──────────────────────────────   ──────
 *   generator_start   генератор щойно завершено         10
 *   generator_loop    одразу після start і до кінця гри  10
 *   fuel_fill         поки в генератор ллється бензин    5
 * </pre>
 * «Луп» тут — не режим звукової системи (його не існує: сервер грає
 * звук рівно один раз), а РОЗКЛАД: файл програється знову рівно тоді,
 * коли скінчилось попереднє програвання. Довжину файлу бере
 * {@link OggSoundLength} — з самого ogg, а не з константи в коді, тож
 * заміна асета нічого не ламає.
 *
 * ── Чому рахунок саме тут, а не в {@code GeneratorModule} ────────────
 * Модуль генераторів — про ремонт, бензин і стадії; розклад озвучення
 * не має з ними нічого спільного й читається окремо. Зв'язок між ними —
 * три виклики ({@link #completed}, {@link #fuelPoured}, {@link #tick}),
 * по одному на подію, яку модуль і так знає.
 *
 * ── Що чує гравець, який прийшов на готовий генератор ────────────────
 * Гул продовжується: розклад іде відносно СЕРВЕРНОГО тіка, а не
 * відносно того, хто поруч. Прийшов у радіус — почув із середини петлі,
 * як і має бути для звуку, що вже давно грає.
 */
public final class GeneratorSoundscape {

    /**
     * Радіус чутності працюючого генератора (10 блоків). Гул позначає
     * точку, до якої треба йти, але не чути на півкарти.
     */
    public static final double RUNNING_RADIUS_BLOCKS = 10.0;

    /**
     * Радіус чутності заливу бензину (5 блоків). Це звук ЗАДІЯНОСТІ
     * (хтось поряд щось робить), а не орієнтир у світі, тож далі нього
     * йому нема чого «розповідати».
     */
    public static final double FUEL_RADIUS_BLOCKS = 5.0;

    private static final String START_FILE = "generator_start";
    private static final String LOOP_FILE = "generator_loop";
    private static final String FUEL_FILE = "fuel_fill";

    /** Тривалості у тіках; 0 — файл недоступний, луп на ньому неможливий. */
    private final int startTicks;
    private final int loopTicks;
    private final int fuelTicks;

    /** Генератори, чий гул роботи вже йде (або от-от почнеться). */
    private final Map<BlockPos, Loop> running = new HashMap<>();

    /** Генератори, у які ллється бензин. */
    private final Map<BlockPos, Pour> pours = new HashMap<>();

    /** Позначки заливу ЦЬОГО тіка — збираються під час ремонта, читаються в {@link #tick}. */
    private final Set<BlockPos> pouredThisTick = new HashSet<>();

    /** Монотонний лічильник тіків: усі розклади рахуються від нього. */
    private long ticks;

    public GeneratorSoundscape() {
        this.startTicks = OggSoundLength.ticksOf(START_FILE);
        this.loopTicks = OggSoundLength.ticksOf(LOOP_FILE);
        this.fuelTicks = OggSoundLength.ticksOf(FUEL_FILE);
    }

    // ── Події ────────────────────────────────────────────────────────────

    /**
     * Генератор щойно завершено (обидві стадії): старт-звук зараз, гул —
     * одразу після нього.
     *
     * Старт грає тут-таки, а не з черги в {@link #tick}: «запустився»
     * мусить звучати тієї ж миті, коли гравець долив останню каністру,
     * інакше звук відстає від події й подія вже не читається як його
     * причина.
     */
    public void completed(GeneratorPoi generator, ServerLevel level) {
        if (startTicks <= 0 || loopTicks <= 0) return; // немає файлів — немає лупа
        running.put(generator.pos(), new Loop(generator.pos(), startTicks, loopTicks));
        WorldSound.playInRadius(level, ModSounds.GENERATOR_START.get(), SoundSource.BLOCKS,
            Vec3.atCenterOf(generator.pos()), RUNNING_RADIUS_BLOCKS, 1.0f, 1.0f);
    }

    /**
     * У цей тік генератор реально прийняв бензин.
     *
     * Позначка, а не звук: залив іде тік за тіком, і програвати звук на
     * КОЖНОМУ з них не можна — лунав би тріск із накладених копій. Звук
     * ставить {@link #tick} за розкладом, тож десять тіків заливки (і
     * навіть кілька гравців одночасно) дають ОДИН гул.
     */
    public void fuelPoured(GeneratorPoi generator) {
        pouredThisTick.add(generator.pos());
    }

    /** Раз на тік, у будь-якій фазі: рухає розклади й прибирає зайве. */
    public void tick(List<GeneratorPoi> generators, ServerLevel level) {
        ticks++;
        pruneRunning(generators);
        advanceRunning(level);
        advancePours(level);
        pouredThisTick.clear();
    }

    // ── Гул роботи ───────────────────────────────────────────────────────

    /**
     * Поки генератор у списку й завершений — луп живий. Як тільки його
     * там немає (новий матч: {@code map().clear()}) або він більше не
     * завершений, запис зникає сам.
     *
     * Тому «грає до кінця гри» виражено станом САМОГО генератора, а не
     * окремою ознакою «матч іде», яку довелося б гасити в кожній фазі
     * окремо і яку легко забути в наступній.
     */
    private void pruneRunning(List<GeneratorPoi> generators) {
        if (running.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Loop>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next().getKey();
            boolean alive = false;
            for (GeneratorPoi generator : generators) {
                if (generator.pos().equals(pos)) {
                    alive = generator.isCompleted();
                    break;
                }
            }
            if (!alive) it.remove();
        }
    }

    /**
     * Немає рівня — немає кому грати (жодного гравця онлайн), тож таймери
     * не рухаються: гул продовжиться з того самого місця, коли хтось
     * зайде. Це не «пауза звуку» — просто розклад не має кому звучати.
     */
    private void advanceRunning(ServerLevel level) {
        if (running.isEmpty() || level == null) return;
        for (Loop loop : running.values()) {
            if (!loop.advance()) continue;
            WorldSound.playInRadius(level, ModSounds.GENERATOR_LOOP.get(), SoundSource.BLOCKS,
                Vec3.atCenterOf(loop.pos), RUNNING_RADIUS_BLOCKS, 1.0f, 1.0f);
        }
    }

    // ── Гул заливу ───────────────────────────────────────────────────────

    /**
     * Гул заливу: іде, поки бензин тече, і замовкає сам.
     *
     * Запис живе не «поки ллють», а поки триває ОСТАННЄ програвання:
     * гравець, який відпустив кнопку, чує звук, що дограв до кінця — як і
     * будь-який інший звук у грі; обірваний на півслові, він читався б
     * як збій. Хвіст при цьому не довший за сам файл, бо ретрайгер
     * ставиться лише активному заливу.
     */
    private void advancePours(ServerLevel level) {
        if (fuelTicks <= 0 || level == null) {
            pours.clear();
            return;
        }

        for (BlockPos pos : pouredThisTick) {
            pours.computeIfAbsent(pos, key -> new Pour()).lastPouredTick = ticks;
        }

        Iterator<Map.Entry<BlockPos, Pour>> it = pours.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Pour> entry = it.next();
            Pour pour = entry.getValue();

            // Останнє програвання дограло — запис більше ні на що не
            // впливає. Умова суворо «більше», щоб ніколи не обірвати
            // звук, який ще грає.
            if (ticks - pour.lastPouredTick > fuelTicks) {
                it.remove();
                continue;
            }

            if (pour.ticksLeft > 0) pour.ticksLeft--;
            boolean pouring = ticks - pour.lastPouredTick <= 1;
            if (pour.ticksLeft > 0 || !pouring) continue;

            WorldSound.playInRadius(level, ModSounds.FUEL_FILL.get(), SoundSource.BLOCKS,
                Vec3.atCenterOf(entry.getKey()), FUEL_RADIUS_BLOCKS, 1.0f, 1.0f);
            pour.ticksLeft = Math.max(1, fuelTicks);
        }
    }

    // ── Стан ─────────────────────────────────────────────────────────────

    /** Петля одного генератора: коли наступне програвання. */
    private static final class Loop {
        private final BlockPos pos;
        private final int lengthTicks;
        private int ticksLeft;

        Loop(BlockPos pos, int startDelayTicks, int lengthTicks) {
            this.pos = pos;
            this.ticksLeft = Math.max(1, startDelayTicks);
            this.lengthTicks = lengthTicks;
        }

        /**
         * true — час програти САМЕ ЦЬОГО тіка.
         *
         * Тік, у якому ми граємо, теж «витрачається» на зменшення
         * лічильника, тому встановлюємо повну довжину: тоді наступне
         * програвання станеться рівно тоді, коли скінчилося попереднє —
         * ні шва (наклалися б дві копії), ні паузи.
         */
        boolean advance() {
            if (ticksLeft > 0) ticksLeft--;
            if (ticksLeft > 0) return false;
            ticksLeft = Math.max(1, lengthTicks);
            return true;
        }
    }

    /**
     * Стан заливу в один генератор.
     *
     * ── Чому саме два числа, а не одне ───────────────────────────────
     * {@code ticksLeft} веде РОЗКЛАД (коли наступне програвання),
     * {@code lastPouredTick} — ЗАДІЯНІСТЬ (чи ллють ще). Це різні речі:
     * між двома програваннями файлу гравці можуть як лити безперервно,
     * так і відпустити кнопку — і тоді петлю треба не продовжити, а
     * дограти. Одним числом ці два випадки не розрізнити.
     */
    private static final class Pour {
        private int ticksLeft;
        private long lastPouredTick;
    }
}

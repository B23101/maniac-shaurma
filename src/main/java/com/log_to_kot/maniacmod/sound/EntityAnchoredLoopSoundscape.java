package com.log_to_kot.maniacmod.sound;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Базовий "звуковий рушій механізму" — робить для БУДЬ-ЯКОЇ сутності-
 * механізму те саме, що двигунний рушій робить у справжніх іграх: звук
 * не летить з фіксованої точки карти, а щотік бере СВОЮ позицію з живої
 * сутності (так він рухається разом із нею, якщо вона колись рухається,
 * і зникає разом з нею — без висячих джерел). Радіус, напрям і згасання
 * з відстанню дає {@link WorldSound#playInRadius}: клієнт бачить звук як
 * звичайний позиційний звук світу, а не як загальний "почули всі".
 *
 * ── Один клас — дві звукові поведінки ─────────────────────────────────
 * Будь-який працюючий механізм (генератор, у майбутньому — двигун
 * машини, бензопила тощо) описується тими самими двома примітивами:
 * <pre>
 *   START_THEN_LOOP  — одноразовий "запуск", і одразу за ним, без шва,
 *                       гул, що триває, доки механізм живий
 *                       (generator_start → generator_loop).
 *   SUSTAIN_WHILE_FED — гул триває, поки щось активно ГОДУЄ подію щотік
 *                       (заливання бензину), і дограє до кінця останнє
 *                       програвання, коли годування припинилось —
 *                       не обривається на півслові.
 * </pre>
 * Підклас лише називає свої два звуки й дає доступ до списку живих
 * сутностей та рівня; увесь розклад (коли саме програти, коли
 * ретригернути, коли надіслати {@code StopSound}) — тут, один раз,
 * замість переписування в кожному новому механізмі.
 *
 * ── Чому клас параметризовано типом сутності ──────────────────────────
 * {@code <T extends Entity>} — не заради дженерик-екзотики: кожен
 * підклас працює з КОНКРЕТНИМ типом сутності свого механізму
 * (генератор — з {@code GeneratorEntity}), і сигнатура {@link
 * #findEntity} про це прямо каже, замість приймати {@code Entity} і
 * сподіватись, що виклик передасть правильний тип.
 */
public abstract class EntityAnchoredLoopSoundscape<T extends Entity> {

    /** Ключ запису — позиція точки механізму на карті (не сутності: сутність можна перепитати нею). */
    private final Map<BlockPos, RunningLoop> running = new HashMap<>();
    private final Map<BlockPos, SustainWhileFed> sustained = new HashMap<>();
    private final Set<BlockPos> fedThisTick = new HashSet<>();

    private long ticks;

    // ── Точки розширення для підкласу ──────────────────────────────────

    /** Подія "запуск" — грає рівно один раз, коли механізм щойно ожив. */
    protected abstract SoundEvent startSound();

    /** Подія "гул роботи" — грає в лупі, поки механізм живий. */
    protected abstract SoundEvent runningLoopSound();

    /** Подія "годування" (наприклад заливка палива) — лупиться, поки годують. */
    protected abstract SoundEvent sustainSound();

    protected abstract double runningRadiusBlocks();

    protected abstract double sustainRadiusBlocks();

    protected abstract SoundSource soundSource();

    /**
     * Актуальна сутність механізму в точці {@code pos}, або {@code null},
     * якщо вона зараз не завантажена/не заспавнена (наприклад чанк
     * вивантажено чи механізм щойно переспавнили). {@code null} — не
     * помилка: розклад просто не рухається цього тіку для цього запису,
     * так само як {@link #tick} нічого не рухає без {@code level}.
     */
    protected abstract T findEntity(ServerLevel level, BlockPos pos);

    /**
     * Позиція, З ЯКОЇ звук лунає ЦЬОГО тіку. За замовчуванням — точний
     * центр хітбокса сутності ({@code entity.position()}, зсунутий на
     * половину висоти): звук рухається разом із сутністю, а не з
     * запам'ятованої точки карти. Підклас може перевизначити, якщо його
     * механізм має інше "звукове осердя" (наприклад приціл на певну
     * частину моделі).
     */
    protected Vec3 soundOrigin(T entity) {
        return entity.position().add(0, entity.getBbHeight() * 0.5, 0);
    }

    // ── Події від власника (підклас-фасад типу GeneratorModule) ─────────

    /**
     * Механізм у точці {@code pos} щойно ожив: старт зараз, гул — одразу
     * після нього, доки живий. Немає файлів (довжина 0) — немає лупа:
     * підклас не повинен викликати це, якщо {@link OggSoundLength}
     * повернув 0 для обох звуків, інакше цикл рахує 0-тіковий інтервал
     * і виродиться в "грати щотіка".
     */
    protected final void started(BlockPos pos, T entity, ServerLevel level,
                                 int startTicks, int loopTicks) {
        if (startTicks <= 0 || loopTicks <= 0 || entity == null) return;
        running.put(pos, new RunningLoop(startTicks, loopTicks));
        WorldSound.playInRadius(level, startSound(), soundSource(),
            soundOrigin(entity), runningRadiusBlocks(), 1.0f, 1.0f);
    }

    /**
     * Точку {@code pos} ЦЬОГО тіку годують (заливають). Лише позначка:
     * фактичне програвання ставить {@link #tick} за розкладом, щоб N
     * тіків заливки (і кілька гравців одночасно) дали ОДИН гул, а не
     * тріск накладених копій.
     */
    protected final void fed(BlockPos pos) {
        fedThisTick.add(pos);
    }

    /**
     * Раз на тік: рухає обидва розклади, прибирає записи механізмів, що
     * зникли ({@code alivePositions} їх більше не містить), і шле
     * {@code StopSound} на прибраний луп гулу — див. {@link
     * WorldSound#stopForAll}, чому цей стоп потрібен окремо від самого
     * видалення запису.
     *
     * @param alivePositions точки механізмів, чий гул МАЄ тривати цього
     *                       тіку (для генератора — {@code isCompleted()}
     *                       з живого {@code GeneratorPoi}); чого тут
     *                       немає — гул того зникає.
     */
    public final void tick(Set<BlockPos> alivePositions, ServerLevel level) {
        ticks++;
        pruneRunning(alivePositions, level);
        advanceRunning(level);
        advanceSustained(level);
        fedThisTick.clear();
    }

    private void pruneRunning(Set<BlockPos> alivePositions, ServerLevel level) {
        if (running.isEmpty()) return;
        boolean removedAny = false;
        Iterator<BlockPos> it = running.keySet().iterator();
        while (it.hasNext()) {
            if (!alivePositions.contains(it.next())) {
                it.remove();
                removedAny = true;
            }
        }
        if (removedAny) WorldSound.stopForAll(level, runningLoopSound(), soundSource());
    }

    private void advanceRunning(ServerLevel level) {
        if (running.isEmpty() || level == null) return;
        for (Map.Entry<BlockPos, RunningLoop> entry : running.entrySet()) {
            if (!entry.getValue().advance()) continue;
            T entity = findEntity(level, entry.getKey());
            if (entity == null) continue; // сутність тимчасово не завантажена — пропускаємо цей такт, не ламаємо розклад
            WorldSound.playInRadius(level, runningLoopSound(), soundSource(),
                soundOrigin(entity), runningRadiusBlocks(), 1.0f, 1.0f);
        }
    }

    private void advanceSustained(ServerLevel level) {
        int sustainTicks = sustainLengthTicks();
        if (sustainTicks <= 0 || level == null) {
            sustained.clear();
            return;
        }

        for (BlockPos pos : fedThisTick) {
            sustained.computeIfAbsent(pos, key -> new SustainWhileFed()).lastFedTick = ticks;
        }

        Iterator<Map.Entry<BlockPos, SustainWhileFed>> it = sustained.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, SustainWhileFed> entry = it.next();
            SustainWhileFed state = entry.getValue();

            // Останнє програвання дограло — запис більше ні на що не впливає.
            if (ticks - state.lastFedTick > sustainTicks) {
                it.remove();
                continue;
            }

            if (state.ticksLeft > 0) state.ticksLeft--;
            boolean feeding = ticks - state.lastFedTick <= 1;
            if (state.ticksLeft > 0 || !feeding) continue;

            T entity = findEntity(level, entry.getKey());
            if (entity != null) {
                WorldSound.playInRadius(level, sustainSound(), soundSource(),
                    soundOrigin(entity), sustainRadiusBlocks(), 1.0f, 1.0f);
            }
            state.ticksLeft = Math.max(1, sustainTicks);
        }
    }

    /** Довжина файлу sustain-звуку в тіках; підклас кешує через {@link OggSoundLength}. */
    protected abstract int sustainLengthTicks();

    /**
     * Пошук сутності в невеликому боксі навколо {@code pos} — спільна
     * реалізація для підкласів, чиї механізми не тримають прямого
     * посилання на свою сутність (той самий підхід, що {@code
     * GeneratorPoi}: запис прогресу живе окремо від "тіла" в світі, тож
     * тіло доводиться питати за позицією, а не тримати застаріле Java-
     * посилання, яке пережило переспавн).
     */
    protected static <E extends Entity> E findByBox(ServerLevel level, BlockPos pos,
                                                     Class<E> type, double padding) {
        AABB box = new AABB(pos).inflate(padding);
        for (E entity : level.getEntitiesOfClass(type, box)) {
            return entity; // одна сутність механізму на точку за дизайном
        }
        return null;
    }

    // ── Стан розкладів ───────────────────────────────────────────────────

    private static final class RunningLoop {
        private final int lengthTicks;
        private int ticksLeft;

        RunningLoop(int startDelayTicks, int lengthTicks) {
            this.ticksLeft = Math.max(1, startDelayTicks);
            this.lengthTicks = lengthTicks;
        }

        boolean advance() {
            if (ticksLeft > 0) ticksLeft--;
            if (ticksLeft > 0) return false;
            ticksLeft = Math.max(1, lengthTicks);
            return true;
        }
    }

    private static final class SustainWhileFed {
        private int ticksLeft;
        private long lastFedTick;
    }
}

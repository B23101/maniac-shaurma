package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.EntityAnchoredLoopSoundscape;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Звукове життя генераторів: запуск, гул роботи й залив бензину —
 * підклас {@link EntityAnchoredLoopSoundscape}, який лише називає свої
 * звуки, радіуси й уміє знайти {@link GeneratorEntity} за позицією
 * генератора. Увесь розклад ("коли програти", "коли ретригернути",
 * "коли зупинити луп") — у батьківському класі, спільному з будь-яким
 * іншим механізмом мода.
 *
 * ── Три звуки ────────────────────────────────────────────────────────
 * <pre>
 *   подія             коли                             радіус
 *   ───────────────   ──────────────────────────────   ──────
 *   generator_start   генератор щойно завершено         10
 *   generator_loop    одразу після start і до кінця гри  10
 *   fuel_fill         поки в генератор ллється бензин    5
 * </pre>
 *
 * ── Чому звук лунає ВІД СУТНОСТІ, а не від точки карти ────────────────
 * Раніше джерелом координат був {@code GeneratorPoi.pos()} — статичний
 * запис прогресу, який ніколи не рухається й не має нічого спільного з
 * видимим "тілом" генератора в світі. Тепер кожне програвання бере
 * позицію в АКТУАЛЬНОЇ {@link GeneratorEntity} (через {@link
 * #findEntity}) — так само, як зброя чи снаряд у нормальному моді грає
 * звук зі своєї сутності, а не з координат, де вона колись заспавнилась.
 * Різниці на практиці майже немає (сутність стоїть на місці), але це
 * правильне джерело істини: якщо колись генератор переспавнять в іншій
 * точці в межах матчу, звук про це одразу дізнається, а не грає з
 * координат мертвої сутності.
 *
 * ── Чому рахунок саме тут, а не в {@code GeneratorModule} ────────────
 * Модуль генераторів — про ремонт, бензин і стадії; розклад озвучення
 * не має з ними нічого спільного й читається окремо. Зв'язок між ними —
 * три виклики ({@link #completed}, {@link #fuelPoured}, {@link #tick}),
 * по одному на подію, яку модуль і так знає.
 */
public final class GeneratorSoundscape extends EntityAnchoredLoopSoundscape<GeneratorEntity> {

    /** Радіус чутності працюючого генератора (10 блоків). */
    public static final double RUNNING_RADIUS_BLOCKS = 10.0;

    /** Радіус чутності заливу бензину (5 блоків). */
    public static final double FUEL_RADIUS_BLOCKS = 5.0;

    private static final String START_FILE = "generator_start";
    private static final String LOOP_FILE = "generator_loop";
    private static final String FUEL_FILE = "fuel_fill";

    /**
     * Половина сторони боксу пошуку сутності навколо {@code GeneratorPoi.pos()}
     * (див. {@link #findByBox}). Генератор — статичний, не рухається, тож
     * досить трохи ширше за його власний хітбокс (1.2×1.2), щоб пережити
     * дробові зсуви позиції спавну.
     */
    private static final double ENTITY_SEARCH_PADDING = 1.5;

    private final int startTicks;
    private final int loopTicks;
    private final int fuelTicks;

    public GeneratorSoundscape() {
        this.startTicks = OggSoundLength.ticksOf(START_FILE);
        this.loopTicks = OggSoundLength.ticksOf(LOOP_FILE);
        this.fuelTicks = OggSoundLength.ticksOf(FUEL_FILE);
    }

    // ── Події ────────────────────────────────────────────────────────────

    /**
     * Генератор щойно завершено (обидві стадії): старт-звук зараз, гул —
     * одразу після нього. Грає тут-таки, а не з черги в {@link #tick}:
     * «запустився» мусить звучати тієї ж миті, коли гравець долив
     * останню каністру, інакше звук відстає від події.
     */
    public void completed(GeneratorPoi generator, ServerLevel level) {
        if (level == null) return;
        GeneratorEntity entity = findEntity(level, generator.pos());
        started(generator.pos(), entity, level, startTicks, loopTicks);
    }

    /**
     * У цей тік генератор реально прийняв бензин. Позначка, а не звук:
     * залив іде тік за тіком, і програвати звук на КОЖНОМУ з них не
     * можна — лунав би тріск із накладених копій. Звук ставить
     * {@link #tick} за розкладом.
     */
    public void fuelPoured(GeneratorPoi generator) {
        fed(generator.pos());
    }

    /** Раз на тік, у будь-якій фазі: рухає розклади й прибирає зайве. */
    public void tick(List<GeneratorPoi> generators, ServerLevel level) {
        Set<BlockPos> alive = new HashSet<>();
        for (GeneratorPoi generator : generators) {
            if (generator.isCompleted()) alive.add(generator.pos());
        }
        tick(alive, level);
    }

    // ── EntityAnchoredLoopSoundscape ────────────────────────────────────

    @Override
    protected SoundEvent startSound() {
        return ModSounds.GENERATOR_START.get();
    }

    @Override
    protected SoundEvent runningLoopSound() {
        return ModSounds.GENERATOR_LOOP.get();
    }

    @Override
    protected SoundEvent sustainSound() {
        return ModSounds.FUEL_FILL.get();
    }

    @Override
    protected double runningRadiusBlocks() {
        return RUNNING_RADIUS_BLOCKS;
    }

    @Override
    protected double sustainRadiusBlocks() {
        return FUEL_RADIUS_BLOCKS;
    }

    @Override
    protected SoundSource soundSource() {
        return SoundSource.BLOCKS;
    }

    @Override
    protected int sustainLengthTicks() {
        return fuelTicks;
    }

    @Override
    protected GeneratorEntity findEntity(ServerLevel level, BlockPos pos) {
        return findByBox(level, pos, GeneratorEntity.class, ENTITY_SEARCH_PADDING);
    }
}

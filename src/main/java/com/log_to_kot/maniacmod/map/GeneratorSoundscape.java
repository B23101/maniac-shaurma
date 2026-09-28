package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.WorldSound;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Звукове життя генераторів: тут лишається лише одноразовий "запуск".
 * Залив бензину більше НЕ йде звідси — він живе як власний серверний
 * розклад прямо в {@link GeneratorEntity#tick()}
 * ({@code sound.GeneratorFuelSoundscape}), за принципом
 * {@code RadioBlockEntity.resendSoundToClient} із Let's Do: Beachparty
 * — сервер щотік стежить, кому саме звук уже підтверджено, і
 * персонально ресинкає його кожному новому глядачеві, а не шле один
 * пакет одразу всім на момент активації.
 *
 * Гул роботи (generator_loop) тепер узагалі не серверний: щоб мати
 * СПРАВЖНІЙ плавний fade гучності і на наближення, і на віддалення
 * (не лише в один бік), він переїхав на клієнт —
 * {@code client.GeneratorLoopClientSoundHandler} — де рушій може
 * плавно міняти гучність уже граючого звуку щокадру, замість
 * пересилки нового старт-пакета щоразу (пакет — завжди рестарт семпла
 * з нуля, тому мережевий фейд і плавно наростав через збіг з природним
 * повтором лупа, і сіпався на спаданні). Детальніше —
 * {@code client.GeneratorLoopClientSound}.
 *
 * ── БАГФІКС історії: чому "один пакет всім при активації" не годився ──
 * Перша версія слала {@code generator_loop} одним {@link WorldSound#playInRadius}
 * у момент активації — це чув лише той, хто ВЖЕ був у радіусі САМЕ ТІЄЇ
 * миті; хтось, хто підійшов пізніше, поки генератор давно активний,
 * жодного пакета не отримував і чув тишу. Друга версія (клієнтський
 * {@code AbstractTickableSoundInstance}) полагодила плавне
 * загасання й миттєвий стоп, але успадкувала ту саму хворобу: клієнт
 * заводив {@code SoundInstance} лише в мить переходу
 * {@code isActive()} false→true, а не для "я зараз бачу вже активний
 * генератор". {@code GeneratorLoopSoundscape}/{@code GeneratorFuelSoundscape}
 * прибирають цю прив'язку до моменту зовсім: щотік перевіряють усіх
 * гравців у радіусі й шлють старт-пакет саме тим, у кого його ще
 * нема — незалежно від того, коли конкретно генератор став активним.
 *
 * ── {@code generator_start} лишається тут, на сервері ─────────────────
 * Це одноразова подія за задумом ("запуск щойно стався") — "доспівати
 * до кінця" тут не баг, а очікувана поведінка, тому лишається на
 * старому надійному {@link WorldSound#playInRadius}: сервер знає, коли
 * подія сталась, і всім у радіусі чутно однаковий момент запуску.
 */
public final class GeneratorSoundscape {

    /** Радіус чутності одноразового звуку запуску (той самий, що раніше мав гул роботи). */
    public static final double START_RADIUS_BLOCKS = 10.0;

    /**
     * Половина сторони боксу пошуку сутності навколо {@code GeneratorPoi.pos()}.
     * Генератор — статичний, не рухається, тож досить трохи ширше за
     * його власний хітбокс (1.2×1.2), щоб пережити дробові зсуви позиції
     * спавну.
     */
    private static final double ENTITY_SEARCH_PADDING = 1.5;

    /**
     * Генератор щойно завершено (обидві стадії): одноразовий звук
     * запуску з позиції сутності. Гул роботи, що йде ЗА ним, більше не
     * запускає сервер — клієнт сам заводить його, щойно бачить
     * {@link GeneratorEntity#isActive()} true (виставляється щотік у
     * {@code GeneratorEntity.syncFromPoi()} з того самого
     * {@code GeneratorPoi.isCompleted()}, що й раніше рухало цей клас).
     */
    public void completed(GeneratorPoi generator, ServerLevel level) {
        if (level == null) return;
        GeneratorEntity entity = findEntity(level, generator);
        if (entity == null) return;
        Vec3 origin = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
        WorldSound.playInRadius(level, ModSounds.GENERATOR_START.get(), SoundSource.BLOCKS,
            origin, START_RADIUS_BLOCKS, 1.0f, 1.0f);
    }

    private static GeneratorEntity findEntity(ServerLevel level, GeneratorPoi generator) {
        AABB box = new AABB(generator.pos()).inflate(ENTITY_SEARCH_PADDING);
        for (GeneratorEntity entity : level.getEntitiesOfClass(GeneratorEntity.class, box)) {
            return entity;
        }
        return null;
    }
}

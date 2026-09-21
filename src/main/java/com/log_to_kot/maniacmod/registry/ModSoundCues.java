package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import dev.shaurmalib.common.sound.SoundCategoryId;
import dev.shaurmalib.common.sound.SoundCategoryRegistry;
import dev.shaurmalib.common.sound.SoundCue;
import dev.shaurmalib.common.sound.SoundCueRegistry;
import dev.shaurmalib.common.sound.SoundStage;
import dev.shaurmalib.common.sound.VolumeSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.sounds.SoundEvent;

/**
 * Опис звуків для {@code SoundCenter} із shaurma-lib.
 *
 * ── Що це дає ────────────────────────────────────────────────────────
 * Мод більше ніде не будує {@code SimpleSoundInstance} руками і не
 * вирішує, 2D це чи 3D. Кожен звук один раз описаний тут: до якої
 * категорії належить (яким слайдером регулюється) і як звучить у
 * світі. Далі будь-де достатньо {@code SoundCenter.play(...)}.
 *
 * ── Три категорії ────────────────────────────────────────────────────
 *   AMBIENCE — напруга: серцебиття, «маньяк поруч»
 *   WORLD    — події світу: генератори, пастки, ворота
 *   UI       — інтерфейс: відлік, старт матчу
 *
 * Розділені навмисно: гравець, якому заважає серцебиття, має мати
 * змогу прибрати саме його, не глушачи звук генераторів, від якого
 * залежить гра.
 *
 * ⚠ Гучність категорій поки фіксована (1.0). Коли з'явиться екран
 * налаштувань мода, {@code VolumeSource.always(1f)} замінюється
 * читанням слайдера — і це єдине місце, яке треба буде змінити.
 */
public final class ModSoundCues {

    public static final SoundCategoryId AMBIENCE =
        SoundCategoryId.of(ManiacMod.MOD_ID, "ambience");

    public static final SoundCategoryId WORLD =
        SoundCategoryId.of(ManiacMod.MOD_ID, "world");

    public static final SoundCategoryId UI =
        SoundCategoryId.of(ManiacMod.MOD_ID, "ui");

    private ModSoundCues() {}

    /** Викликається один раз на старті сервера, після реєстрації звуків. */
    public static void register() {
        // VolumeSource.always(1f) — поки в мода немає власних слайдерів.
        // Коли з'явиться екран налаштувань, замінюється читанням слайдера;
        // SoundCenter питає гучність щоразу, тому зміна діє миттєво.
        SoundCategoryRegistry.register(AMBIENCE, VolumeSource.always(1f));
        SoundCategoryRegistry.register(WORLD, VolumeSource.always(1f));
        SoundCategoryRegistry.register(UI, VolumeSource.always(1f));

        // ── Інтерфейс ────────────────────────────────────────────────────
        cue(ModSounds.GAME_START,      SoundStage.NON_POSITIONAL, UI, 1.0f);
        cue(ModSounds.COUNTDOWN_BEEP,  SoundStage.NON_POSITIONAL, UI, 0.8f);
        cue(ModSounds.COUNTDOWN_FINAL, SoundStage.NON_POSITIONAL, UI, 1.0f);

        // ── Світ ─────────────────────────────────────────────────────────
        cue(ModSounds.GENERATOR_REPAIR, SoundStage.POSITIONAL_FIXED, WORLD, 0.8f);
        cue(ModSounds.GENERATOR_ON,     SoundStage.POSITIONAL_FIXED, WORLD, 1.0f);
        cue(ModSounds.POWER_ON,         SoundStage.NON_POSITIONAL,   WORLD, 1.0f);
        cue(ModSounds.EXIT_OPEN,        SoundStage.POSITIONAL_FIXED, WORLD, 1.0f);
        cue(ModSounds.SURVIVOR_ESCAPED, SoundStage.NON_POSITIONAL,   WORLD, 1.0f);
        cue(ModSounds.TRAP_SNAP,        SoundStage.POSITIONAL_FIXED, WORLD, 1.0f);
        cue(ModSounds.WIRE_ZAP,         SoundStage.POSITIONAL_FIXED, WORLD, 1.0f);

        // ── Напруга ──────────────────────────────────────────────────────
        // Маньяк рухається, тому звук має слідувати за джерелом.
        cue(ModSounds.MANIAC_NEARBY, SoundStage.POSITIONAL_MOVING, AMBIENCE, 1.0f);

        // Важке дихання без стаміни — звук ТІЛА гравця, тому позиційний
        // (ExhaustedBreathClientHooks кличе SoundCenter.playAt з координатами
        // mc.player, оновленими щовиклик) — на відміну від COUNTDOWN_BEEP,
        // що лишається суто 2D-диктором. AMBIENCE, не WORLD: це особисте
        // відчуття гравця (напруга/втома), а не подія світу на кшталт
        // генератора чи пастки.
        cue(ModSounds.EXHAUSTED_BREATH, SoundStage.POSITIONAL_FIXED, AMBIENCE, 1.0f);

        // Серцебиття від близькості маньяка (HeartbeatSoundHandler) — та сама
        // логіка "звук ТІЛА гравця", що й задишка: позиційний, від координат
        // mc.player, категорія AMBIENCE (особисте відчуття напруги, не подія
        // світу). Перенесено з мода "снайпери" (EnhancedVisuals HeartbeatHandler).
        cue(ModSounds.HEARTBEAT_IN,  SoundStage.POSITIONAL_FIXED, AMBIENCE, 1.0f);
        cue(ModSounds.HEARTBEAT_OUT, SoundStage.POSITIONAL_FIXED, AMBIENCE, 1.0f);

        // Хрускіт кісток при переломі ноги — подія ТІЛА гравця в момент
        // конкретної події (вставання після падіння), а не постійне
        // відчуття на кшталт серцебиття/задишки, тому WORLD (як TRAP_SNAP),
        // не AMBIENCE.
        cue(ModSounds.BONE_BREAK, SoundStage.POSITIONAL_FIXED, WORLD, 1.0f);
    }

    private static void cue(RegistryObject<SoundEvent> sound, SoundStage stage,
                            SoundCategoryId category, float volume) {
        ResourceLocation id = sound.getId();
        SoundCueRegistry.register(SoundCue.builder(id.toString())
            .stage(stage)
            .category(category)
            .baseVolume(volume)
            .build());
    }
}

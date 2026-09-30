package com.log_to_kot.maniacmod.client.intro;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * ЗАГОТОВКИ звуків вступу. Жодного .ogg зараз немає — і це нормально: кожна
 * подія перевіряє, чи є її ключ у {@code sounds.json}, і мовчить, якщо нема.
 * Щоб озвучити, достатньо покласти файл і додати запис у sounds.json —
 * Java міняти не треба. Список подій і що куди класти — у
 * {@code assets/maniacmod/sounds/intro/README.md}.
 *
 * ── Музика ───────────────────────────────────────────────────────────
 * {@code intro_music} стартує РАЗОМ із першим затемненням і плавно гасне під
 * час фінального розтемнення (коли все вже завантажено). Гучністю керує
 * {@link #tick}: наростання на початку, згасання від {@link #beginMusicFadeOut}.
 */
final class IntroAudio {

    /** Довга композиція (можна ogg будь-якої тривалості; не loop). */
    static final String MUSIC = "intro_music";
    /** Низький «гул» на першому затемненні. */
    static final String DARKEN = "intro_darken";
    /** Удар у момент, коли екран повністю чорний і стартує камера. */
    static final String BLACKOUT = "intro_blackout";
    /** Кінець чорного: камера з'являється, letterbox їде. */
    static final String REVEAL_CAMERA = "intro_camera_in";
    /** Легкий «свіш» при зміні поради. */
    static final String TIP_SWAP = "intro_tip_swap";
    /** Наростання перед фінальним розтемненням. */
    static final String RISER = "intro_riser";
    /** Удар/вдих у момент розтемнення разом із початком гри. */
    static final String GAME_START = "intro_game_start";

    private static final float MUSIC_FADE_IN_S = 1.6f;
    private static final float MUSIC_VOLUME = 0.9f;

    private MusicInstance music;

    /** Одноразовий звук інтерфейсу; тихо нічого не робить, якщо його немає в sounds.json. */
    void cue(String name, float volume, float pitch) {
        ResourceLocation id = new ResourceLocation(ManiacMod.MOD_ID, name);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager().getSoundEvent(id) == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), pitch, volume));
    }

    void startMusic() {
        stopMusic();
        ResourceLocation id = new ResourceLocation(ManiacMod.MOD_ID, MUSIC);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager().getSoundEvent(id) == null) return;
        music = new MusicInstance(SoundEvent.createVariableRangeEvent(id));
        mc.getSoundManager().play(music);
    }

    /** Починає плавне згасання музики за {@code seconds} секунд. */
    void beginMusicFadeOut(float seconds) {
        if (music != null) music.fadeOut(seconds);
    }

    void stopMusic() {
        if (music != null) {
            Minecraft.getInstance().getSoundManager().stop(music);
            music = null;
        }
    }

    boolean musicFinished() {
        return music == null || music.isStopped();
    }

    private static final class MusicInstance extends AbstractTickableSoundInstance {
        private float age;
        private float fadeLeft = -1f;
        private float fadeTotal = 1f;

        MusicInstance(SoundEvent event) {
            super(event, SoundSource.MUSIC, RandomSource.create());
            this.looping = false;
            this.delay = 0;
            this.volume = 0.001f;
            this.pitch = 1f;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
        }

        void fadeOut(float seconds) {
            if (fadeLeft >= 0f) return;
            fadeTotal = Math.max(0.05f, seconds);
            fadeLeft = fadeTotal;
        }

        @Override
        public void tick() {
            age += 0.05f;
            float v = MUSIC_VOLUME * Math.min(1f, age / MUSIC_FADE_IN_S);
            if (fadeLeft >= 0f) {
                fadeLeft -= 0.05f;
                v *= Math.max(0f, fadeLeft / fadeTotal);
                if (fadeLeft <= 0f) {
                    this.volume = 0f;
                    stop();
                    return;
                }
            }
            this.volume = Math.max(0.0001f, v);
        }
    }
}

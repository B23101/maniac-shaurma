package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.registry.ModSounds;
import dev.shaurmalib.forge.sound.SoundCenter;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

/**
 * Звук серцебиття, коли поруч маньяк — озвучує те саме значення
 * {@code ClientMatchState.heartbeat()} (0..1, 0 = маньяка не чути,
 * 1 = впритул), яке вже смикає розмір іконки серця в
 * {@link com.log_to_kot.maniacmod.client.overlay.vitals.SurvivorVitalsOverlay#renderHeart}.
 *
 * <p><b>Перенесено з мода "снайпери"</b> (система
 * {@code EnhancedVisuals HeartbeatHandler}): один цикл серцебиття —
 * два коротких удари, {@code heartbeat_out} одразу на старті циклу й
 * {@code heartbeat_in} за {@link #IN_OFFSET_MS} до його кінця — той
 * самий двотактний "тук-тук", що й там (в оригіналі — буфер тіків
 * {@code effectBufferTicks} з другим ударом на позначці {@code == 5}
 * із 20-тікового циклу; тут той самий інтервал ({@code 5 тіків = 250мс})
 * перевиражений у мілісекундах, бо цей клас працює від
 * {@code System.currentTimeMillis()}, а не від лічильника тіків).</p>
 *
 * <h3>Чому НЕ окрема система, а той самий {@code heartbeat}</h3>
 * У мода снайперів heartbeat реагував на НИЗЬКЕ ХП. У маніяк-моді це
 * ім'я вже зайняте іншим сенсом — "наскільки близько маньяк" (див.
 * {@code SurvivorModule.computeHeartbeat}), і саме цей сенс тут
 * озвучується: HUD уже показує пульс, тепер його ще й чути.
 *
 * <h3>Частота</h3>
 * Один період = {@code 900 - 640 * heartbeat} мс — та сама формула,
 * що й пульс іконки серця в {@code SurvivorVitalsOverlay.renderHeart}
 * (900мс на дальній межі, 260мс впритул до маньяка): звук і візуал
 * завжди б'ються в такт, бо рахуються з однієї точки й одного вхідного
 * значення. Що ближче маньяк — то частіше серце.
 *
 * <h3>Чому клієнтський тригер, не сервер + пакет</h3>
 * Той самий принцип, що {@link ExhaustedBreathClientHooks}: сервер
 * уже щотік шле {@code heartbeat} у {@code SurvivorVitalsPacket} —
 * тут лише читається те, що вже прийшло, без додаткового мережевого
 * трафіку.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeartbeatSoundHandler {

    /**
     * Скільки мс до кінця циклу грає другий удар (IN). В оригіналі —
     * 5 тіків із 20-тікового циклу за замовчуванням, тобто чверть
     * циклу до кінця; тут той самий інтервал у мс (5 тіків × 50мс).
     */
    private static final long IN_OFFSET_MS = 250;

    /** Момент (мс), коли почався поточний цикл серцебиття. -1 = цикл не йде. */
    private static long cycleStartMs = -1L;

    /** Чи вже програвався другий удар (IN) у поточному циклі — щоб не повторити його щотік. */
    private static boolean inPlayedThisCycle = false;

    private HeartbeatSoundHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            cycleStartMs = -1L;
            return;
        }

        boolean vitalsAllowed = ClientMatchState.allows(PhaseRule.SURVIVOR_VITALS)
            || ClientMatchState.phase() == com.log_to_kot.maniacmod.core.phase.GamePhase.LOBBY;
        if (!ClientMatchState.isSurvivor() || !vitalsAllowed) {
            cycleStartMs = -1L;
            return;
        }

        float heartbeat = ClientMatchState.heartbeat();
        if (heartbeat <= 0f) {
            cycleStartMs = -1L; // маньяк вийшов за межі радіуса — серце затихло, наступний вхід стартує з чистого циклу
            return;
        }

        long periodMs = Math.round(900 - 640 * heartbeat); // та сама формула, що SurvivorVitalsOverlay.renderHeart
        long now = System.currentTimeMillis();

        if (cycleStartMs < 0) {
            // Новий цикл: перший удар (OUT) грає одразу, як і в оригіналі
            // (effectBufferTicks <= 0 стартує overlay+blur+OUT в одному кадрі).
            cycleStartMs = now;
            inPlayedThisCycle = false;
            playHeartbeatSound(ModSounds.HEARTBEAT_OUT, heartbeat);
            return;
        }

        long elapsed = now - cycleStartMs;

        if (elapsed >= periodMs) {
            // Цикл завершився — новий одразу з тим самим "миттєвим OUT".
            cycleStartMs = now;
            inPlayedThisCycle = false;
            playHeartbeatSound(ModSounds.HEARTBEAT_OUT, heartbeat);
            return;
        }

        // Другий удар (IN) за IN_OFFSET_MS до кінця циклу, рівно один раз.
        // periodMs на близькій межі (260мс) коротший за IN_OFFSET_MS (250мс)
        // лише-лише — clamp знизу нулем не дає "негативного" вікна.
        long inOffset = Math.min(IN_OFFSET_MS, periodMs);
        if (!inPlayedThisCycle && elapsed >= periodMs - inOffset) {
            inPlayedThisCycle = true;
            playHeartbeatSound(ModSounds.HEARTBEAT_IN, heartbeat);
        }
    }

    /**
     * @param heartbeat 0..1 — гучність росте разом із близькістю
     *                  маньяка (тихе далеке серце, гучне впритул), той
     *                  самий діапазон {@code Math.min(0.7f, intensity)}
     *                  підхід, що в оригінальному {@code EVHeartbeatHandler},
     *                  тільки джерело інтенсивності тут — дистанція,
     *                  не хп.
     */
    private static void playHeartbeatSound(RegistryObject<SoundEvent> sound, float heartbeat) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float volume = 0.4f + 0.6f * Math.min(1f, heartbeat);
        SoundCenter.playAt(sound.getId().toString(), SoundSource.PLAYERS, volume, 1.0f,
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}

package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.client.overlay.vitals.LowHpVignetteOverlay;
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
 * Звук серцебиття, коли поруч маньяк, + крок {@link HeartbeatClock}.
 *
 * <p>Озвучує те саме значення {@code ClientMatchState.heartbeat()} (0..1,
 * 0 = маньяка не чути, 1 = впритул), яким живиться іконка серця у
 * {@code SurvivorVitalsOverlay}. <b>Перенесено з мода "снайпери"</b>
 * (двотактний "тук-тук": {@code heartbeat_out} → {@code heartbeat_in}).</p>
 *
 * <h3>Що змінено (звук "дивний впритул до маньяка")</h3>
 * <ul>
 *   <li>Раніше цикл рахувався в {@code ClientTickEvent} (крок 50 мс) з періодом
 *       {@code 900 - 640*hb} → 260 мс впритул, а другий удар ставився за
 *       250 мс до кінця циклу, тобто через ~10 мс після першого. Обидва
 *       семпли по 300 мс — вони накладались у кашу й тремтіли на джитері
 *       тіків. Тепер ритм веде {@link HeartbeatClock}: покадровий, з
 *       мінімальним проміжком ~170 мс між ударами.</li>
 *   <li>Гучність і висота тону ростуть плавно зі згладженим рівнем, тому
 *       серце не "стрибає" при кожному пакеті від сервера.</li>
 *   <li>Та сама фаза читається оверлеєм — звук і візуал синхронні за
 *       побудовою.</li>
 * </ul>
 *
 * <p>Клієнтський тригер, а не сервер + пакет: сервер уже щотік шле
 * {@code heartbeat} у {@code SurvivorVitalsPacket} — той самий принцип, що
 * {@link ExhaustedBreathClientHooks}.</p>
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeartbeatSoundHandler {

    /** Нижче цього згладженого рівня серце не озвучується (край радіуса). */
    private static final float AUDIBLE_LEVEL = 0.02f;

    private static long lastFrameNanos = 0L;

    private HeartbeatSoundHandler() {}

    /**
     * Покадровий крок — саме кадр, а не клієнтський тік: тік дає 50 мс
     * гранулярності, яка на швидкому ритмі чутна як тремтіння.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();
        long nowNanos = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (nowNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = nowNanos;

        if (mc.player == null || mc.level == null || !ClientMatchState.isSurvivor()) {
            HeartbeatClock.reset();
            proximitySmoothed = 0f;
            return;
        }
        // Пауза (одиночна гра): годинник стоїть, звук не накопичується.
        if (mc.isPaused()) return;

        // Серце б'ється не лише від маньяка поруч, а й від малого хп:
        // нижче порога віньєтки воно з'являється тихо й повільно, а на
        // межі смерті — гучно й швидко. Береться більше з двох джерел,
        // тож близький маньяк усе ще прискорює ритм сильніше.
        float proximity = ClientMatchState.heartbeat();
        float lowHp = lowHpHeartbeat();
        float target = Math.max(proximity, lowHp);
        int events = HeartbeatClock.update(dt, target);

        // Гучність веде ДЖЕРЕЛО серцебиття, а не спільний рівень: раніше мале хп
        // піднімало {@code level} до 0.75, а гучність росла від нього ж — серце
        // гриміло як при маньяку впритул. Тепер близькість маньяка озвучується
        // як і раніше (згладжена), а мале хп має власну, значно тихішу шкалу.
        proximitySmoothed += (proximity - proximitySmoothed) * (1f - (float) Math.exp(-SMOOTH_RATE * Math.min(0.1f, dt)));
        if (Math.abs(proximity - proximitySmoothed) < 0.001f) proximitySmoothed = proximity;
        float lowHpSeverity = LowHpVignetteOverlay.lowHpSeverity();

        boolean soundAllowed = ClientMatchState.allows(PhaseRule.SURVIVOR_VITALS)
            || ClientMatchState.phase() == GamePhase.LOBBY;
        float level = HeartbeatClock.level();
        if (!soundAllowed || level < AUDIBLE_LEVEL) return;

        float volume = volumeFor(proximitySmoothed, lowHpSeverity);
        if ((events & HeartbeatClock.EVENT_LUB) != 0) {
            playHeartbeatSound(ModSounds.HEARTBEAT_OUT, volume, level, DEEP_PITCH);
        }
        if ((events & HeartbeatClock.EVENT_DUB) != 0) {
            // Другий удар трохи тихіший і вищий — природне «тук-ТУК».
            playHeartbeatSound(ModSounds.HEARTBEAT_IN, volume * 0.88f, level, DEEP_PITCH + 0.03f);
        }
    }

    /** Швидкість згладжування близькості маньяка для гучності, 1/с (як у {@link HeartbeatClock}). */
    private static final float SMOOTH_RATE = 6f;

    /** Гучність від малого хп: тихий фон на порозі ({@link #LOW_HP_VOLUME_MIN}) і лише помірна на нулі хп ({@link #LOW_HP_VOLUME_MAX}). */
    private static final float LOW_HP_VOLUME_MIN = 0.10f;
    private static final float LOW_HP_VOLUME_MAX = 0.32f;

    /** Базова висота тону серця: низький, глибокий звук (ванільний мінімум — 0.5). */
    private static final float DEEP_PITCH = 0.62f;

    private static float proximitySmoothed = 0f;

    /**
     * Підсумкова гучність удару (0..1): більше з двох незалежних джерел.
     * Близькість маньяка — стара формула без змін (0.30..1.0); мале хп —
     * окрема, значно тихіша шкала.
     */
    private static float volumeFor(float proximity, float lowHpSeverity) {
        float fromManiac = proximity < AUDIBLE_LEVEL ? 0f : 0.30f + 0.70f * Math.min(1f, proximity);
        float fromLowHp = lowHpSeverity <= 0f ? 0f
            : LOW_HP_VOLUME_MIN + (LOW_HP_VOLUME_MAX - LOW_HP_VOLUME_MIN) * lowHpSeverity;
        return Math.max(fromManiac, fromLowHp);
    }

    /** Рівень серцебиття (0..1) від малого хп: 0 вище порога, від {@link #LOW_HP_MIN_LEVEL} до {@link #LOW_HP_MAX_LEVEL} нижче. */
    private static final float LOW_HP_MIN_LEVEL = 0.18f;
    private static final float LOW_HP_MAX_LEVEL = 0.75f;

    private static float lowHpHeartbeat() {
        float severity = LowHpVignetteOverlay.lowHpSeverity();
        if (severity <= 0f) return 0f;
        return LOW_HP_MIN_LEVEL + (LOW_HP_MAX_LEVEL - LOW_HP_MIN_LEVEL) * severity;
    }

    /**
     * @param volume       готова гучність 0..1 ({@link #volumeFor})
     * @param rhythmLevel  згладжений рівень ритму 0..1 — від нього росте лише висота тону (до +8%)
     * @param pitchBase    базова висота тону
     */
    private static void playHeartbeatSound(RegistryObject<SoundEvent> sound, float volume,
                                           float rhythmLevel, float pitchBase) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Глибокий низький «бух-бух»: тон майже не росте з близькістю (раніше +8% робило звук тонким).
        float pitch = Math.max(0.5f, pitchBase + 0.04f * Math.min(1f, rhythmLevel));
        SoundCenter.playAt(sound.getId().toString(), SoundSource.PLAYERS, Math.min(1f, volume), pitch,
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}

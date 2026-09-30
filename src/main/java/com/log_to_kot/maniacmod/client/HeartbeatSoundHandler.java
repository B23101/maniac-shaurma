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
            return;
        }
        // Пауза (одиночна гра): годинник стоїть, звук не накопичується.
        if (mc.isPaused()) return;

        // Серце б'ється не лише від маньяка поруч, а й від малого хп:
        // нижче порога віньєтки воно з'являється тихо й повільно, а на
        // межі смерті — гучно й швидко. Береться більше з двох джерел,
        // тож близький маньяк усе ще прискорює ритм сильніше.
        float target = Math.max(ClientMatchState.heartbeat(), lowHpHeartbeat());
        int events = HeartbeatClock.update(dt, target);

        boolean soundAllowed = ClientMatchState.allows(PhaseRule.SURVIVOR_VITALS)
            || ClientMatchState.phase() == GamePhase.LOBBY;
        float level = HeartbeatClock.level();
        if (!soundAllowed || level < AUDIBLE_LEVEL) return;

        if ((events & HeartbeatClock.EVENT_LUB) != 0) {
            playHeartbeatSound(ModSounds.HEARTBEAT_OUT, level, 1.0f);
        }
        if ((events & HeartbeatClock.EVENT_DUB) != 0) {
            // Другий удар трохи тихіший і вищий — природне "тук-ТУК".
            playHeartbeatSound(ModSounds.HEARTBEAT_IN, level * 0.88f, 1.04f);
        }
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
     * @param level рівень близькості 0..1 (згладжений) — гучність росте разом із ним
     * @param pitchBase базова висота: до неї додається до +8% впритул до маньяка
     */
    private static void playHeartbeatSound(RegistryObject<SoundEvent> sound, float level, float pitchBase) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float l = Math.min(1f, level);
        float volume = 0.30f + 0.70f * l;
        float pitch = pitchBase + 0.08f * l;
        SoundCenter.playAt(sound.getId().toString(), SoundSource.PLAYERS, volume, pitch,
            mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }
}

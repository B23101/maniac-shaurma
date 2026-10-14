package com.log_to_kot.maniacmod.client.vr;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.HeartbeatClock;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * VR: серцебиття в долонях. Дивимось на єдиний годинник {@link HeartbeatClock}
 * (той самий, що озвучує серце): коли «час від останнього удару» стрибає назад —
 * почався новий удар, даємо короткий імпульс обом контролерам. Сила росте з
 * рівнем стресу ({@link HeartbeatClock#level()}).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VrHeartbeatHaptics {

    private static float lastMs = Float.MAX_VALUE;

    private VrHeartbeatHaptics() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!VrCompat.isVrActive()) { lastMs = Float.MAX_VALUE; return; }

        float level = HeartbeatClock.level();
        float ms = HeartbeatClock.msSinceLub();
        boolean newBeat = ms + 1.0f < lastMs;
        lastMs = ms;
        if (!newBeat || level < 0.05f) return;

        float amplitude = 0.15f + 0.6f * Math.min(1f, level);
        VrCompat.haptic(VrCompat.Hand.MAIN, 0.06f, amplitude);
        VrCompat.haptic(VrCompat.Hand.OFF, 0.06f, amplitude);
    }
}

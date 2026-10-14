package com.log_to_kot.maniacmod.client.barricade;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Клієнтський стан шкали витягування цвяхів. Гаситься сам, якщо пакет не приходив ~250 мс. */
@OnlyIn(Dist.CLIENT)
public final class BarricadePullClient {

    private static final long TIMEOUT_MS = 250;

    private static int progress;
    private static int required;
    private static float rate = 1f;
    private static long lastPacketMs;

    private BarricadePullClient() {}

    public static void onProgress(int newProgress, int newRequired, float newRate) {
        progress = newProgress;
        required = newRequired;
        rate = newRate;
        lastPacketMs = System.currentTimeMillis();
    }

    public static boolean isActive() {
        return required > 0 && System.currentTimeMillis() - lastPacketMs < TIMEOUT_MS;
    }

    public static float fraction() {
        return required <= 0 ? 0f : Math.max(0f, Math.min(1f, (float) progress / required));
    }

    public static float rate() { return rate; }

    public static void reset() {
        required = 0;
        progress = 0;
    }
}

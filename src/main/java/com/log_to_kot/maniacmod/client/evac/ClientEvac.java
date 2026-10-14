package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Клієнтське дзеркало даних евакуації. Нічого не вирішує: тримає прямокутники галявин
 * ({@link ClearingZoneRenderer}), останній стан евакуації для HUD ({@link EvacHudOverlay})
 * і останню втечу для банера ({@link EscapeToastOverlay}). Скидається в
 * {@code ClientMatchState.reset()}.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientEvac {

    /** Остання втеча — для анімованого банера «Ім'я втік! 3/6». */
    public record Escape(String name, int escaped, int total, long atMs) {}

    private static volatile List<ClearingSyncPacket.Rect> clearings = List.of();
    private static volatile EvacStatusPacket status = EvacStatusPacket.IDLE;
    private static volatile long statusAtMs = 0L;
    private static volatile Escape lastEscape = null;

    private ClientEvac() {}

    // ── Галявини ─────────────────────────────────────────────────────────
    public static void setClearings(List<ClearingSyncPacket.Rect> rects) { clearings = List.copyOf(rects); }
    public static List<ClearingSyncPacket.Rect> clearings()               { return clearings; }

    // ── Стан евакуації ───────────────────────────────────────────────────
    public static void setStatus(EvacStatusPacket packet) {
        status = packet;
        statusAtMs = System.currentTimeMillis();
    }

    public static int step()          { return status.step(); }
    public static int escaped()       { return status.escaped(); }
    public static int total()         { return status.total(); }
    public static boolean active()    { return status.step() != EvacStatusPacket.Step.IDLE; }

    /**
     * Скільки секунд до відльоту — ПЛАВНО: сервер шле значення раз на секунду, а між пакетами
     * ми віднімаємо реальний час, щоб шкала рухалась безперервно, а не стрибками.
     * -1 — таймера немає.
     */
    public static float secondsLeft() {
        EvacStatusPacket s = status;
        if (s.secondsLeft() < 0) return -1f;
        float elapsed = (System.currentTimeMillis() - statusAtMs) / 1000f;
        return Math.max(0f, s.secondsLeft() - Math.min(elapsed, 1.0f));
    }

    public static int secondsTotal() { return status.secondsTotal(); }

    // ── Втеча ────────────────────────────────────────────────────────────
    public static void onEscape(String name, int escaped, int total) {
        lastEscape = new Escape(name, escaped, total, System.currentTimeMillis());
    }

    public static Escape lastEscape() { return lastEscape; }

    public static void reset() {
        clearings = List.of();
        status = EvacStatusPacket.IDLE;
        statusAtMs = 0L;
        lastEscape = null;
    }
}

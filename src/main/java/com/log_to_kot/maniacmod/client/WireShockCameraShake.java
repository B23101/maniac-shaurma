package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.vr.VrCompat;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Тряска камери під час удару струмом (ТЗ 5.2).
 *
 * ── Чому {@code ComputeCameraAngles}, а не правка гравця ─────────────
 * Це подія Forge, що викликається ПІСЛЯ того, як ваніль порахувала
 * yaw/pitch/roll камери, але ПЕРЕД тим, як їх застосовано до рендеру.
 * Зсув тут — суто візуальний: реальний напрямок погляду гравця
 * (і, відповідно, серверний стан) не змінюється. Тому тряска не може
 * «розсинхронізувати» камеру з грою чи дати перевагу.
 *
 * ── Один канал із локом камери ───────────────────────────────────────
 * Блокування повороту (міксини {@code MixinMouseHandlerManiacStunned} і
 * {@code MixinEntityTurnManiacStunned}) і тряска — різні речі: перше забороняє ГРАВЦЮ крутити камеру, друге
 * додає ВІДЧУТТЯ струму до вже порахованого кута. Тому тряска не чіпає
 * лок, а лише домішує шум до кутів.
 *
 * ── Джерело параметрів ──────────────────────────────────────────────
 * Амплітуда й частота приходять із {@code WireShockPacket} (сервер
 * володіє конфігом). Тут лише зберігаємо їх і малюємо синусоїдну суміш
 * трьох частот — так тряска виглядає хаотично, а не як рівномірне
 * гойдання.
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WireShockCameraShake {

    /** Амплітуда тряски (градуси). 0 — не трясе. */
    private static double degrees = 0.0;

    /** Частота тряски (Гц). */
    private static volatile double hz = 9.0;

    private WireShockCameraShake() {}

    /** Викликає {@code ClientPacketHandler.onWireShock}. {@code active=false} знімає тряску. */
    public static void configure(boolean active, double degrees, double hz) {
        if (active) {
            VrCompat.haptic(VrCompat.Hand.MAIN, 0.6f, 1.0f);
            VrCompat.haptic(VrCompat.Hand.OFF, 0.6f, 1.0f);
        }
        WireShockCameraShake.degrees = active ? Math.max(0.0, degrees) : 0.0;
        WireShockCameraShake.hz = active ? Math.max(0.1, hz) : 9.0;
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (VrCompat.isVrActive()) return; // VR: тряска камери = нудота
        if (degrees <= 0.0) return;
        if (!ClientMatchState.isWireShocked()) return;

        double t = System.nanoTime() / 1_000_000_000.0 * hz * Math.PI * 2.0;
        float yawJitter = (float) (Math.sin(t) * degrees);
        float pitchJitter = (float) (Math.sin(t * 1.7 + 1.0) * degrees);
        float rollJitter = (float) (Math.sin(t * 2.3 + 2.0) * degrees * 0.6);

        event.setYaw(event.getYaw() + yawJitter);
        event.setPitch(event.getPitch() + pitchJitter);
        event.setRoll(event.getRoll() + rollJitter);
    }
}

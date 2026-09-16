package com.log_to_kot.maniacmod.net;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Спільний контракт усіх пакетів "сервер → клієнт".
 *
 * ── Навіщо цей клас ───────────────────────────────────────────────────
 * До цього кожен s2c-пакет повторював один і той самий блок у
 * {@code handle(ctx)}:
 *
 *     DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
 *         ClientPacketHandler.onXxx(...));
 *
 * Це не просто зайві рядки — це дублювання правила з {@code net/README.md}
 * ("пакет не торкається клієнтських класів напряму, лише через
 * DistExecutor"), яке легко порушити ОДИН раз в одному новому пакеті,
 * і виділений сервер впаде з {@code NoClassDefFoundError} рівно там.
 *
 * Тепер правило дотримується технічно: {@link #handle} тут FINAL і
 * сам робить DistExecutor-обгортку, а конкретний пакет реалізує лише
 * {@link #clientHandle()} — виклик потрібного методу
 * {@code ClientPacketHandler}, без згадки DistExecutor узагалі.
 *
 * ── Хто це реалізує ──────────────────────────────────────────────────
 * Усі record-и в {@code net/s2c/*}. C2S-пакети цей контракт НЕ
 * реалізують — вони обробляються вже на сервері, без обмеження
 * дистрибуції, тому лишаються на {@link ModPacket} напряму.
 */
public interface S2CPacket extends ModPacket {

    /**
     * Що зробити на клієнті з даними цього пакета. Викликається вже
     * всередині {@code Dist.CLIENT}-контексту — тут можна вільно
     * звертатись до {@code ClientPacketHandler} чи інших
     * {@code @OnlyIn(CLIENT)} класів.
     */
    void clientHandle();

    @Override
    default void handle(NetworkEvent.Context ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::clientHandle);
    }
}

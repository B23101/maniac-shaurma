package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише жертві дроту): почався/скінчився удар струмом.
 *
 * ── Навіщо окремий прапор, якщо є {@code trapped} ────────────────────
 * {@code trapped} у {@code SurvivorVitalsPacket} спільний для КАПКАНА й
 * дроту, і він лише глушить рух. Дріт за ТЗ додатково:
 *   • блокує ПОВОРОТ камери (капкан — ні, ним можна роздивлятись);
 *   • трясе камеру.
 * Тому клієнту треба знати саме «мене б'є струмом», а не просто «я в
 * пастці». Окреме поле в {@code SurvivorVitalsPacket} змусило б возити
 * його в кожному тіку HUD усім станам; окремий пакет надсилається
 * рівно двічі за подію (початок і кінець).
 *
 * ── Чому амплітуда/частота тут, а не в клієнтському конфігу ────────
 * Конфіг живе на СЕРВЕРІ (той самий принцип, що з дальністю пасток у
 * {@code TrapLoadoutPacket}): на виділеному сервері клієнт його не має.
 * Тож сервер сам рахує й передає параметри тряски, а клієнт лише малює.
 *
 * @param active  true — струм почався; false — завершився
 * @param degrees амплітуда тряски камери, градуси (ТЗ 5.2)
 * @param hz      частота тряски, Гц (ТЗ 5.2)
 */
public record WireShockPacket(boolean active, double degrees, double hz) implements S2CPacket {

    /** Кінець удару — параметри не потрібні. */
    public static WireShockPacket stop() {
        return new WireShockPacket(false, 0.0, 1.0);
    }

    public WireShockPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeDouble(degrees);
        buf.writeDouble(hz);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onWireShock(active, degrees, hz);
    }
}

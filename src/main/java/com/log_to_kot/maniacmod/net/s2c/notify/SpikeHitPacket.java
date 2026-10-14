package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (лише жертві шипів): ти щойно наступив на шипи.
 *
 * Клієнт за цим пакетом вмикає екранний ефект {@code SpikeHitEffect}: спалах,
 * залізні шипи, що вилітають знизу екрана, бризки й патьоки крові, червону
 * пульсуючу віньєтку, різкий кивок камери вгору й тремтіння від болю. Сам
 * пакет нічого не вирішує в матчі — суто відчуття удару (шкода, стаміна й
 * підсвітка рахуються на сервері).
 *
 * @param downed true, якщо удар збив жертву з ніг (хп скінчились) — ефект
 *               тоді сильніший й довший
 */
public record SpikeHitPacket(boolean downed) implements S2CPacket {

    public SpikeHitPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(downed);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onSpikeHit(downed);
    }
}

package com.log_to_kot.maniacmod.net.s2c.barricade;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: прогрес витягування цвяхів з барикади. Шлеться ЩОТІКА, поки гравець тримає ПКМ
 * ломом; щойно він відпустив — пакети припиняються, і клієнт сам гасить шкалу за ~250 мс (той самий
 * принцип, що {@code RescueProgressPacket}: окремого «сховати» немає).
 *
 * @param progress поточний прогрес, тіки
 * @param required скільки треба всього
 * @param rate     поточна швидкість: 1.0 — звичайна, більше — прискорено серцебиттям (страхом)
 */
public record BarricadePullPacket(int progress, int required, float rate) implements S2CPacket {

    public BarricadePullPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readFloat());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(progress);
        buf.writeVarInt(required);
        buf.writeFloat(rate);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.barricade.BarricadePullClient.onProgress(progress, required, rate);
    }
}

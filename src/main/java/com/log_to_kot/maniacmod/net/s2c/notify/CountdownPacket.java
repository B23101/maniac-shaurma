package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: цифра відліку (shaurma-lib AnimatedCountdownSystem).
 *
 * ── Категорія: notify ────────────────────────────────────────────────
 * Так само як {@link ActionBarPacket} — одноразова подія на кожну
 * цифру, клієнт нічого сам не рахує між пакетами.
 *
 * Використовується технічними фазами: відлік до кінця кінематографа,
 * до старту полювання, до закриття воріт.
 *
 * @param digit      число, що показується; 0 = сховати
 * @param accentArgb колір акценту (наприклад червоний у фіналі)
 */
public record CountdownPacket(int digit, int accentArgb) implements S2CPacket {

    public CountdownPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(digit);
        buf.writeInt(accentArgb);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onCountdown(digit, accentArgb);
    }
}

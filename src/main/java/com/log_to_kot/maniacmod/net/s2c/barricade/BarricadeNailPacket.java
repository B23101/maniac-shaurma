package com.log_to_kot.maniacmod.net.s2c.barricade;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: стан ОДНОГО цвяха в міні-грі барикади. Шлеться, поки цвях тягнуть або він
 * «осідає» назад, і один раз, коли його витягнуто ({@code progress >= required}).
 *
 * @param nail     індекс цвяха ({@code дошка * 2 + кінець})
 * @param progress скільки вже витягнуто, тіки
 * @param required скільки треба всього
 * @param held     цвях зараз тягнуть (сам гравець або інший виживий на цій барикаді)
 * @param rate     швидкість: 1.0 — звичайна, більше — прискорено серцебиттям (страхом)
 */
public record BarricadeNailPacket(int nail, int progress, int required, boolean held, float rate)
        implements S2CPacket {

    public BarricadeNailPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readFloat());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(nail);
        buf.writeVarInt(progress);
        buf.writeVarInt(required);
        buf.writeBoolean(held);
        buf.writeFloat(rate);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.barricade.BarricadeGameClient.onNail(this);
    }
}

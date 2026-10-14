package com.log_to_kot.maniacmod.net.s2c.evac;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт (усім): «Ім'я втік! 3/6». Разова ПОДІЯ для анімованого банера
 * ({@code EscapeToastOverlay}); поточний лічильник тримає {@link EvacStatusPacket}.
 *
 * @param name    нік того, хто втік
 * @param escaped скільки втекло включно з ним
 * @param total   скільки виживих було в матчі
 */
public record EscapeAnnouncePacket(String name, int escaped, int total) implements S2CPacket {

    public EscapeAnnouncePacket(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name, 32);
        buf.writeVarInt(escaped);
        buf.writeVarInt(total);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onEscapeAnnounce(name, escaped, total);
    }
}

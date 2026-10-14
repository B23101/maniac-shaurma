package com.log_to_kot.maniacmod.net.s2c.history;

import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: повний матч. {@code record == null} — не знайдено або немає доступу.
 */
public record HistoryDetailPacket(String matchId, MatchRecord record) implements S2CPacket {

    public HistoryDetailPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readBoolean() ? MatchRecord.read(buf) : null);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(matchId, 32);
        buf.writeBoolean(record != null);
        if (record != null) record.write(buf);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onHistoryDetail(this);
    }
}

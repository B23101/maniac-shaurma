package com.log_to_kot.maniacmod.net.s2c.leaderboard;

import com.log_to_kot.maniacmod.leaderboard.LeaderboardRow;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardService;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: сторінка однієї таблиці + становище одержувача в ній.
 *
 * @param total     скільки гравців у таблиці
 * @param meRank    місце одержувача; 0 — його в таблиці нема
 * @param meValue   його значення (0, якщо нема)
 * @param nearName  нік сусіда: того, хто на місце вище, а для №1 — хто дихає в потилицю
 * @param nearValue значення сусіда
 */
public record LeaderboardPagePacket(int category, int total, int offset, List<LeaderboardRow> rows,
                                    int meRank, long meValue, String nearName, long nearValue) implements S2CPacket {

    public LeaderboardPagePacket {
        rows = List.copyOf(rows);
        nearName = nearName == null ? "" : nearName;
    }

    public LeaderboardPagePacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readRows(buf),
            buf.readVarInt(), buf.readVarLong(), buf.readUtf(32), buf.readVarLong());
    }

    private static List<LeaderboardRow> readRows(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), LeaderboardService.MAX_PAGE);
        List<LeaderboardRow> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(LeaderboardRow.read(buf));
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(category);
        buf.writeVarInt(total);
        buf.writeVarInt(offset);
        int n = Math.min(rows.size(), LeaderboardService.MAX_PAGE);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) rows.get(i).write(buf);
        buf.writeVarInt(meRank);
        buf.writeVarLong(meValue);
        buf.writeUtf(nearName, 32);
        buf.writeVarLong(nearValue);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onLeaderboardPage(this);
    }
}

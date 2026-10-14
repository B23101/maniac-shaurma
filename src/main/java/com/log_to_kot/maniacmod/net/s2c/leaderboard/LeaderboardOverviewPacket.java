package com.log_to_kot.maniacmod.net.s2c.leaderboard;

import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: огляд усіх таблиць для бічного списку екрана — хто чемпіон, скільки в таблиці гравців,
 * де одержувач. Скінів тут нема (це лише текст), тож пакет легкий.
 *
 * @param meCrowns у скількох таблицях одержувач зараз №1
 */
public record LeaderboardOverviewPacket(int meCrowns, List<Entry> entries) implements S2CPacket {

    /** Рядок огляду; {@code meRank == 0} — одержувача в таблиці нема. */
    public record Entry(int category, String champName, long champValue, int total, int meRank, long meValue) {}

    public LeaderboardOverviewPacket {
        entries = List.copyOf(entries);
    }

    public LeaderboardOverviewPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt(), readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 64);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new Entry(buf.readVarInt(), buf.readUtf(32), buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(meCrowns);
        int n = Math.min(entries.size(), 64);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            buf.writeVarInt(e.category());
            buf.writeUtf(e.champName() == null ? "" : e.champName(), 32);
            buf.writeVarLong(e.champValue());
            buf.writeVarInt(e.total());
            buf.writeVarInt(e.meRank());
            buf.writeVarLong(e.meValue());
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onLeaderboardOverview(this);
    }
}

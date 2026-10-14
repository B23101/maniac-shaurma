package com.log_to_kot.maniacmod.net.s2c.history;

import com.log_to_kot.maniacmod.history.MatchBrief;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер → клієнт: сторінка списку історії (відповідь на {@code HistoryListRequestPacket}).
 *
 * @param target {@code null} — це адмінський список усіх матчів; інакше — історія цього гравця
 * @param total  скільки матчів усього під фільтром (для сторінок)
 */
public record HistoryListPacket(UUID target, int total, int offset, List<MatchBrief> items) implements S2CPacket {

    private static final int MAX_ITEMS = 50;

    public HistoryListPacket {
        items = List.copyOf(items);
    }

    public HistoryListPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readUUID() : null, buf.readVarInt(), buf.readVarInt(), readItems(buf));
    }

    private static List<MatchBrief> readItems(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ITEMS);
        List<MatchBrief> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(MatchBrief.read(buf));
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(target != null);
        if (target != null) buf.writeUUID(target);
        buf.writeVarInt(total);
        buf.writeVarInt(offset);
        int n = Math.min(items.size(), MAX_ITEMS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) items.get(i).write(buf);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onHistoryList(this);
    }
}

package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → один гравець: за що й скільки XP йому нараховано за матч. Шлеться на вході в ENDING
 * одразу після {@code MatchSummaryPacket}.
 *
 * <p>Чому окремий пакет, а не поле в {@code MatchSummaryPacket}: підсумок однаковий для всіх і
 * летить широкомовно, а нагорода — особиста (свої рядки, свій загальний XP).</p>
 *
 * @param lines    рядки «Нарахування»: ключ локалізації {@code maniacmod.reward.<key>} і кількість XP
 * @param xpBefore загальний XP гравця ДО цього матчу
 * @param xpAfter  загальний XP ПІСЛЯ нарахування (рівень і смужка рахуються з {@code XpCurve})
 */
public record MatchRewardPacket(List<Line> lines, long xpBefore, long xpAfter) implements S2CPacket {

    public record Line(String key, int xp) {}

    private static final int MAX_LINES = 16;

    public MatchRewardPacket {
        lines = List.copyOf(lines);
    }

    public MatchRewardPacket(FriendlyByteBuf buf) {
        this(readLines(buf), buf.readVarLong(), buf.readVarLong());
    }

    private static List<Line> readLines(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_LINES);
        List<Line> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(new Line(buf.readUtf(32), buf.readVarInt()));
        return list;
    }

    public int total() {
        int sum = 0;
        for (Line l : lines) sum += l.xp();
        return sum;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        int size = Math.min(lines.size(), MAX_LINES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUtf(lines.get(i).key(), 32);
            buf.writeVarInt(lines.get(i).xp());
        }
        buf.writeVarLong(xpBefore);
        buf.writeVarLong(xpAfter);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onReward(this);
    }
}

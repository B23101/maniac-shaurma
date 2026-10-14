package com.log_to_kot.maniacmod.net.c2s.leaderboard;

import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardRow;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardService;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket;
import com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardPagePacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клієнт → сервер: «пришли таблицю». {@code category < 0} — огляд усіх таблиць (чемпіони + твої місця,
 * для бічного списку екрана); інакше — сторінка цієї категорії {@code [offset, offset+limit)}.
 *
 * <p>Топи публічні (нік, рівень, цифри) й не залежать від фази матчу, тому доступ не обмежений; захист —
 * антиспам ({@value #COOLDOWN_MS} мс на гравця) і стеля {@link LeaderboardService#MAX_PAGE} рядків.</p>
 */
public record LeaderboardRequestPacket(int category, int offset, int limit) implements ModPacket {

    private static final long COOLDOWN_MS = 120L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public LeaderboardRequestPacket(FriendlyByteBuf buf) {
        this(buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt());
    }

    /** Огляд усіх таблиць. */
    public static LeaderboardRequestPacket overview() {
        return new LeaderboardRequestPacket(-1, 0, 0);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(category + 1);   // -1 → 0: VarInt не любить від'ємних
        buf.writeVarInt(offset);
        buf.writeVarInt(limit);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ModNetwork.sender(ctx);
        long now = System.currentTimeMillis();
        if (LAST.size() > 512) LAST.clear();
        Long last = LAST.put(player.getUUID(), now);
        if (last != null && now - last < COOLDOWN_MS) return;

        if (category < 0) {
            ModNetwork.toPlayer(player, buildOverview(player.getUUID()));
            return;
        }
        LeaderboardCategory c = LeaderboardCategory.byOrdinal(category);
        if (c == null) return;
        UUID me = player.getUUID();
        int meRank = LeaderboardService.rankOf(c, me);
        LeaderboardRow near = LeaderboardService.neighbour(c, me);
        ModNetwork.toPlayer(player, new LeaderboardPagePacket(c.ordinal(), LeaderboardService.total(c),
            Math.max(0, offset), LeaderboardService.page(c, offset, limit),
            meRank, LeaderboardService.valueOf(c, me),
            near == null ? "" : near.name(), near == null ? 0L : near.value()));
    }

    private static LeaderboardOverviewPacket buildOverview(UUID me) {
        List<LeaderboardOverviewPacket.Entry> list = new ArrayList<>();
        for (LeaderboardCategory c : LeaderboardCategory.values()) {
            LeaderboardRow champ = LeaderboardService.champion(c);
            list.add(new LeaderboardOverviewPacket.Entry(c.ordinal(),
                champ == null ? "" : champ.name(), champ == null ? 0L : champ.value(),
                LeaderboardService.total(c), LeaderboardService.rankOf(c, me), LeaderboardService.valueOf(c, me)));
        }
        return new LeaderboardOverviewPacket(LeaderboardService.crownsOf(me), list);
    }
}

package com.log_to_kot.maniacmod.net.c2s.history;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.history.HistoryAccess;
import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.net.s2c.history.HistoryDetailPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клієнт → сервер: «пришли повний матч». {@code context} — чий профіль/історію відкрито, звідки
 * клікнули матч ({@code null} — з адмінського списку). Права — {@link HistoryAccess#canOpen}.
 * Відповідь — завжди {@link HistoryDetailPacket}: або матч, або «не знайдено/заборонено» (щоб екран не
 * чекав вічно; причину клієнту не розкриваємо).
 */
public record HistoryDetailRequestPacket(String matchId, UUID context) implements ModPacket {

    private static final long COOLDOWN_MS = 200L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public HistoryDetailRequestPacket {
        matchId = matchId == null ? "" : matchId;
    }

    public HistoryDetailRequestPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(32), buf.readBoolean() ? buf.readUUID() : null);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(matchId, 32);
        buf.writeBoolean(context != null);
        if (context != null) buf.writeUUID(context);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ModNetwork.sender(ctx);
        long now = System.currentTimeMillis();
        if (LAST.size() > 512) LAST.clear();
        Long last = LAST.put(player.getUUID(), now);
        if (last != null && now - last < COOLDOWN_MS) return;

        var match = ManiacMod.match();
        if (match == null) return;
        MatchRecord record = match.history().get(player.server, matchId);
        boolean allowed = false;
        if (record != null) {
            boolean lobby = match.phases().current() == GamePhase.LOBBY;
            boolean online = context != null && player.server.getPlayerList().getPlayer(context) != null;
            allowed = HistoryAccess.canOpen(player, record, context, lobby, online);
        }
        ModNetwork.toPlayer(player, new HistoryDetailPacket(matchId, allowed ? record : null));
    }
}

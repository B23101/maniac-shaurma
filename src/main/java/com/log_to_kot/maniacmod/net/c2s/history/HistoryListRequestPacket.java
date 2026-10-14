package com.log_to_kot.maniacmod.net.c2s.history;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.history.HistoryAccess;
import com.log_to_kot.maniacmod.history.MatchHistoryService;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.net.s2c.history.HistoryListPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клієнт → сервер: «пришли сторінку історії». {@code target == null} — усі матчі (лише адмін); інакше —
 * матчі, де брав участь цей гравець (власна історія, історія профілю, який дивишся).
 *
 * <p>Права перевіряються ТУТ ({@link HistoryAccess}), а не лише тим, що клієнт показав кнопку: пакет
 * можна надіслати й без екрана. Відповідь — {@link HistoryListPacket}; на заборонений запит сервер
 * мовчить. Антиспам — раз на {@value #COOLDOWN_MS} мс на гравця.</p>
 */
public record HistoryListRequestPacket(UUID target, String query, int outcome, int offset, int limit)
    implements ModPacket {

    private static final long COOLDOWN_MS = 150L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    public HistoryListRequestPacket {
        query = query == null ? "" : query;
    }

    public HistoryListRequestPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readUUID() : null, buf.readUtf(32),
            buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(target != null);
        if (target != null) buf.writeUUID(target);
        buf.writeUtf(query, 32);
        buf.writeVarInt(outcome);
        buf.writeVarInt(offset);
        buf.writeVarInt(limit);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ModNetwork.sender(ctx);
        long now = System.currentTimeMillis();
        // Мапа лише для антиспаму: не даємо їй рости безкінечно за довгу сесію сервера.
        if (LAST.size() > 512) LAST.clear();
        Long last = LAST.put(player.getUUID(), now);
        if (last != null && now - last < COOLDOWN_MS) return;

        var match = ManiacMod.match();
        if (match == null) return;
        boolean lobby = match.phases().current() == GamePhase.LOBBY;
        boolean online = target != null && player.server.getPlayerList().getPlayer(target) != null;
        if (!HistoryAccess.canList(player, target, lobby, online)) return;

        var filter = MatchHistoryService.OutcomeFilter.values()[
            Math.floorMod(outcome, MatchHistoryService.OutcomeFilter.values().length)];
        var page = match.history().list(player.server, target, query, filter, offset, limit);
        ModNetwork.toPlayer(player, new HistoryListPacket(target, page.total(), page.offset(), page.items()));
    }
}

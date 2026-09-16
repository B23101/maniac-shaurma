package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: поточна фаза матчу.
 *
 * ── Категорія: matchstate ────────────────────────────────────────────
 * Дані, спільні для ВСІХ гравців матчу одночасно — не залежать від
 * того, хто саме дивиться (на відміну від {@code vitals}, де кожен
 * гравець отримує СВОЇ числа). Сюди ж належить майбутній пакет
 * досягнень матчу (POWER_RESTORED / EXIT_OPENED тощо, зараз читається
 * лише на сервері через {@code MatchOrchestrator.completedObjectives()}
 * — якщо колись знадобиться показати це на клієнті, новий пакет іде
 * саме в цю категорію, не в {@code notify}: це стан, а не подія).
 *
 * Найважливіший пакет клієнта. Завдяки йому клієнтський код (HUD,
 * клавіші, оверлеї) питає фазу локально, не смикаючи сервер і не
 * тримаючи власних прапорців «гра йде».
 *
 * У v3 цього пакета не було взагалі: клієнт здогадувався про стан гри
 * за побічними ознаками (чи прийшов ManiacTypePacket, чи показаний
 * overlay), тому після реконекту HUD показував дурниці.
 */
public record PhaseSyncPacket(GamePhase phase) implements S2CPacket {

    public PhaseSyncPacket(FriendlyByteBuf buf) {
        this(buf.readEnum(GamePhase.class));
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(phase);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onPhase(phase);
    }
}

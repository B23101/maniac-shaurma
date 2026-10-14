package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: ХТО ПЕРЕМІГ. Шлеться один раз, РАНІШЕ за {@code PhaseSyncPacket(ENDING)},
 * щоб до моменту відкриття підсумкового екрана результат уже лежав у {@code ClientMatchState}.
 *
 * <p>Категорія — matchstate (спільне для всіх гравців). Правило перемоги живе в ОДНОМУ місці —
 * {@code MatchOrchestrator.survivorsWon()}; клієнт нічого не рахує сам (не виводить із ростера).</p>
 *
 * @param survivorsWon true — виживі (втік хоча б один); false — маньяк (не втік ніхто)
 * @param escaped      скільки втекло
 * @param total        скільки виживих було в матчі (втекли + вибули + лишились)
 */
public record MatchOutcomePacket(boolean survivorsWon, int escaped, int total) implements S2CPacket {

    public MatchOutcomePacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(survivorsWon);
        buf.writeVarInt(escaped);
        buf.writeVarInt(total);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onOutcome(survivorsWon, escaped, total);
    }
}

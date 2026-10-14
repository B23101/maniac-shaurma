package com.log_to_kot.maniacmod.net.s2c.matchstate;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Сервер → клієнт: ПОВНА статистика матчу для екрана підсумків. Шлеться один раз усім разом із
 * {@code MatchOutcomePacket} на вході у фазу ENDING.
 *
 * <p>Чому окремий пакет, а не розширення {@code RosterSyncPacket}: ростер летить кілька разів на
 * секунду й описує «хто зараз у якому стані»; тут — одноразовий підсумок «що сталось за матч»
 * (див. {@code client/screen/statistics/README.md}: нова історія матчу = новий пакет у matchstate).</p>
 *
 * <p>Усі отримують однаковий пакет: «своя» статистика — рядок із власним UUID, клієнт знаходить
 * його сам.</p>
 *
 * @param survivorsWon       правило перемоги — {@code MatchOrchestrator.survivorsWon()}
 * @param playedSeconds      скільки тривала активна гра (від першого HUNT до кінця)
 * @param generatorsDone     полагоджено генераторів
 * @param generatorsRequired скільки вимагав конфіг
 * @param objectiveMask      бітова маска {@code MatchObjectives.Objective.ordinal()}
 * @param entries            по рядку на кожного учасника (маніяк + усі виживі)
 */
public record MatchSummaryPacket(boolean survivorsWon, int playedSeconds,
                                 int generatorsDone, int generatorsRequired,
                                 int objectiveMask, List<Entry> entries) implements S2CPacket {

    /** Чим закінчив учасник. Нові значення — лише в кінець (пишеться по ordinal). */
    public enum Result { ESCAPED, ELIMINATED, CAUGHT, MANIAC }

    /**
     * Один учасник.
     *
     * @param survivedSeconds скільки протримався (до втечі/загибелі або до кінця матчу)
     * @param maniacId        id архетипу для маніяка, інакше порожньо
     */
    public record Entry(UUID id, String name, Result result, String maniacId, int survivedSeconds,
                        int hitsDealt, int hitsTaken, int downs, int hooks, int rescues, int repairSeconds,
                        List<TrapLine> traps) {
        public Entry {
            traps = traps == null ? List.of() : List.copyOf(traps);
        }
    }

    /** Пастки одного типу за матч: поставлено і скільки жертв зачепило. */
    public record TrapLine(String trapId, int placed, int caught) {}

    private static final int MAX_TRAP_LINES = 16;

    private static final int MAX_ENTRIES = 64;

    public MatchSummaryPacket {
        entries = List.copyOf(entries);
    }

    public MatchSummaryPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            readEntries(buf));
    }

    private static List<Entry> readEntries(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<Entry> list = new ArrayList<>(size);
        Result[] values = Result.values();
        for (int i = 0; i < size; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(64);
            Result result = values[Math.floorMod(buf.readVarInt(), values.length)];
            String maniacId = buf.readUtf(64);
            int survived = buf.readVarInt();
            int hitsDealt = buf.readVarInt(), hitsTaken = buf.readVarInt(), downs = buf.readVarInt();
            int hooks = buf.readVarInt(), rescues = buf.readVarInt(), repair = buf.readVarInt();
            int tn = Math.min(buf.readVarInt(), MAX_TRAP_LINES);
            List<TrapLine> traps = new ArrayList<>(tn);
            for (int t = 0; t < tn; t++) traps.add(new TrapLine(buf.readUtf(64), buf.readVarInt(), buf.readVarInt()));
            list.add(new Entry(id, name, result, maniacId, survived, hitsDealt, hitsTaken, downs, hooks,
                rescues, repair, traps));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(survivorsWon);
        buf.writeVarInt(playedSeconds);
        buf.writeVarInt(generatorsDone);
        buf.writeVarInt(generatorsRequired);
        buf.writeVarInt(objectiveMask);
        int size = Math.min(entries.size(), MAX_ENTRIES);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            Entry e = entries.get(i);
            buf.writeUUID(e.id());
            buf.writeUtf(e.name(), 64);
            buf.writeVarInt(e.result().ordinal());
            buf.writeUtf(e.maniacId() == null ? "" : e.maniacId(), 64);
            buf.writeVarInt(e.survivedSeconds());
            buf.writeVarInt(e.hitsDealt());
            buf.writeVarInt(e.hitsTaken());
            buf.writeVarInt(e.downs());
            buf.writeVarInt(e.hooks());
            buf.writeVarInt(e.rescues());
            buf.writeVarInt(e.repairSeconds());
            int tn = Math.min(e.traps().size(), MAX_TRAP_LINES);
            buf.writeVarInt(tn);
            for (int t = 0; t < tn; t++) {
                TrapLine l = e.traps().get(t);
                buf.writeUtf(l.trapId(), 64);
                buf.writeVarInt(l.placed());
                buf.writeVarInt(l.caught());
            }
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onSummary(this);
    }
}

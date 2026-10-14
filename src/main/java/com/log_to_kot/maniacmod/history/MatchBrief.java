package com.log_to_kot.maniacmod.history;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * КОРОТКИЙ рядок історії: коли грали, скільки, хто був і чим усе скінчилось. Саме його показує список
 * матчів (адмінський і особистий); повні подробиці ({@link MatchRecord}) вантажаться окремо, лише
 * коли гравець відкрив конкретний матч.
 *
 * <p>Живе в індексі {@code index.jsonl} (один рядок JSON на матч) і в пам'яті сервісу — тому тут
 * лише те, що потрібно списку й фільтрам, без хронології.</p>
 */
public final class MatchBrief {

    public static final int MAX_PLAYERS = MatchRecord.MAX_PLAYERS;

    public String id = "";
    public long startedAtMs;
    public int playedSeconds;
    public int totalSeconds;
    public boolean survivorsWon;
    public MatchRecord.End end = MatchRecord.End.ESCAPE;
    public String stage = "";
    public String maniacId = "";
    public int generatorsDone;
    public int generatorsRequired;
    public int survivorTotal;
    public int escaped;
    public List<Part> players = new ArrayList<>();

    /** Учасник у списку: достатньо, щоб намалювати голову, нік, роль і результат. */
    public static final class Part {
        public UUID id;
        public String name = "";
        public MatchRecord.Role role = MatchRecord.Role.SURVIVOR;
        public MatchRecord.Outcome outcome = MatchRecord.Outcome.CAUGHT;
        public int xp;

        /** Виграла його сторона? (маніяк — коли виживі програли, виживий — коли хтось втік). */
        public boolean won(boolean survivorsWon) {
            return role == MatchRecord.Role.MANIAC ? !survivorsWon : survivorsWon;
        }
    }

    public boolean involves(UUID playerId) {
        return part(playerId) != null;
    }

    public Part part(UUID playerId) {
        if (playerId == null) return null;
        for (Part p : players) if (playerId.equals(p.id)) return p;
        return null;
    }

    /** Чи містить матч гравця, нік якого містить {@code needleLower} (регістр не важливий), або цього маніяка. */
    public boolean matchesText(String needleLower) {
        if (needleLower == null || needleLower.isEmpty()) return true;
        if (maniacId.toLowerCase(java.util.Locale.ROOT).contains(needleLower)) return true;
        for (Part p : players) {
            if (p.name.toLowerCase(java.util.Locale.ROOT).contains(needleLower)) return true;
        }
        return false;
    }

    public MatchBrief normalize() {
        if (id == null) id = "";
        if (end == null) end = MatchRecord.End.ESCAPE;
        if (stage == null) stage = "";
        if (maniacId == null) maniacId = "";
        if (players == null) players = new ArrayList<>();
        players.removeIf(p -> p == null || p.id == null);
        for (Part p : players) {
            if (p.name == null) p.name = "";
            if (p.role == null) p.role = MatchRecord.Role.SURVIVOR;
            if (p.outcome == null) p.outcome = MatchRecord.Outcome.CAUGHT;
        }
        return this;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id, 32);
        buf.writeLong(startedAtMs);
        buf.writeVarInt(playedSeconds);
        buf.writeVarInt(totalSeconds);
        buf.writeBoolean(survivorsWon);
        buf.writeVarInt(end.ordinal());
        buf.writeUtf(stage, 32);
        buf.writeUtf(maniacId, 64);
        buf.writeVarInt(generatorsDone);
        buf.writeVarInt(generatorsRequired);
        buf.writeVarInt(survivorTotal);
        buf.writeVarInt(escaped);
        int n = Math.min(players.size(), MAX_PLAYERS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Part p = players.get(i);
            buf.writeUUID(p.id);
            buf.writeUtf(p.name, 64);
            buf.writeVarInt(p.role.ordinal());
            buf.writeVarInt(p.outcome.ordinal());
            buf.writeVarInt(p.xp);
        }
    }

    public static MatchBrief read(FriendlyByteBuf buf) {
        MatchBrief b = new MatchBrief();
        b.id = buf.readUtf(32);
        b.startedAtMs = buf.readLong();
        b.playedSeconds = buf.readVarInt();
        b.totalSeconds = buf.readVarInt();
        b.survivorsWon = buf.readBoolean();
        b.end = MatchRecord.enumOf(MatchRecord.End.values(), buf.readVarInt());
        b.stage = buf.readUtf(32);
        b.maniacId = buf.readUtf(64);
        b.generatorsDone = buf.readVarInt();
        b.generatorsRequired = buf.readVarInt();
        b.survivorTotal = buf.readVarInt();
        b.escaped = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_PLAYERS);
        for (int i = 0; i < n; i++) {
            Part p = new Part();
            p.id = buf.readUUID();
            p.name = buf.readUtf(64);
            p.role = MatchRecord.enumOf(MatchRecord.Role.values(), buf.readVarInt());
            p.outcome = MatchRecord.enumOf(MatchRecord.Outcome.values(), buf.readVarInt());
            p.xp = buf.readVarInt();
            b.players.add(p);
        }
        return b;
    }
}

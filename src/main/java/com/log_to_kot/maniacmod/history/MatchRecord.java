package com.log_to_kot.maniacmod.history;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ПОВНИЙ запис одного завершеного матчу: хто грав, чим кожен закінчив, до якої стадії дійшла гра,
 * скільки XP отримав кожен і хронологія подій. Це єдина форма даних історії: її пише сервер на диск
 * (Gson, {@code <світ>/data/maniacmod/history/matches/<id>.json}), нею ж він відповідає клієнту
 * ({@link #write}/{@link #read}), її ж малює екран деталей матчу.
 *
 * <p>Поля публічні й не-final — тим самим способом, що й {@code PlayerProfile}: Gson читає/пише файл без
 * адаптерів, а відсутні в старому файлі ключі лишаються зі значеннями за замовчуванням
 * ({@link #normalize()} прибирає {@code null}-и після читання).</p>
 *
 * <p><b>Час.</b> Усі позначки часу в подіях і фазах — у ТІКАХ від створення матчу
 * ({@code MatchContext}), а не від початку полювання: так події до HUNT (вступ, розкидання, ролі)
 * не губляться. Клієнт віднімає {@link #playStartTick}, щоб показати «час гри».</p>
 *
 * <p>Нові значення enum-ів — ЛИШЕ в кінець (мережа пише {@code ordinal()}).</p>
 */
public final class MatchRecord {

    public static final int FORMAT = 1;
    public static final int MAX_PLAYERS = 64;
    public static final int MAX_EVENTS = 5000;
    public static final int MAX_PHASES = 64;
    private static final int MAX_LINES = 16;

    /** Роль у матчі. */
    public enum Role { MANIAC, SURVIVOR }

    /** Чим закінчив учасник. {@code CAUGHT} — дожив до кінця, але не втік (його «не врятували»). */
    public enum Outcome { ESCAPED, ELIMINATED, CAUGHT, MANIAC, LEFT }

    /** Як скінчився матч. */
    public enum End {
        /** Хтось із виживих втік (вертоліт). */
        ESCAPE,
        /** Усі виживі вибули (загинули або вийшли) — маніяк переміг. */
        ALL_OUT,
        /** Вертоліт відлетів, а ніхто не втік. */
        TIME_OUT,
        /** Адмін завершив матч командою. */
        FORCED,
        /** Матч скинуто до завершення (напр. маніяк вийшов). */
        ABORTED
    }

    public int format = FORMAT;
    public String id = "";
    public long startedAtMs;
    public long endedAtMs;
    /** Скільки тривав увесь матч від старту (з технічними фазами), секунди. */
    public int totalSeconds;
    /** Скільки тривала АКТИВНА гра (від першого HUNT), секунди. */
    public int playedSeconds;
    /** Тік (від створення матчу), на якому почалось полювання; {@code -1} — до HUNT не дійшло. */
    public int playStartTick = -1;
    public boolean survivorsWon;
    public End end = End.ESCAPE;
    /** Найглибша ігрова фаза, якої досяг матч: {@code HUNT} / {@code POWERED} / {@code HELI_WAIT} / {@code FINALE}. */
    public String stage = "HUNT";
    public String maniacId = "";
    public int generatorsDone;
    public int generatorsRequired;
    /** Бітова маска {@code MatchObjectives.Objective.ordinal()} — що було досягнуто. */
    public int objectiveMask;
    public int survivorTotal;
    public int escaped;
    public int eliminated;

    public List<Player> players = new ArrayList<>();
    public List<PhaseSpan> phases = new ArrayList<>();
    public List<Event> events = new ArrayList<>();

    // ── Вкладені типи ────────────────────────────────────────────────────

    /** Один учасник матчу. */
    public static final class Player {
        public UUID id;
        public String name = "";
        public Role role = Role.SURVIVOR;
        public Outcome outcome = Outcome.CAUGHT;
        /** Для маніяка — id архетипу, інакше порожньо. */
        public String maniacId = "";
        public int survivedSeconds;
        public int hitsDealt;
        public int hitsTaken;
        public int downs;
        public int hooks;
        public int rescues;
        public int repairSeconds;
        /** Тік (від створення матчу), коли гравець вибув/втік; {@code -1} — дожив до кінця. */
        public int endTick = -1;
        /** Ім'я {@code GamePhase}, у якій він вибув/втік; порожньо — дожив. */
        public String endPhase = "";
        /** Причина вибуття: {@code maniac} | {@code trap:<id>} | {@code bleedout} | {@code left}; порожньо — немає. */
        public String endCause = "";
        /** Індекс у {@link MatchRecord#players} того, хто бив останнім; {@code -1} — ніхто. */
        public int killerIdx = -1;
        public List<TrapLine> traps = new ArrayList<>();
        /** За що нараховано XP: ключі {@code maniacmod.reward.<key>}. */
        public List<XpLine> xp = new ArrayList<>();
        public int xpTotal;
        /** Загальний XP до/після матчу — з них клієнт рахує рівень і ранг. {@code -1} — XP не нараховувався. */
        public long xpBefore = -1L;
        public long xpAfter = -1L;
    }

    /** Пастки одного типу: поставлено / жертв. */
    public static final class TrapLine {
        public String trapId = "";
        public int placed;
        public int caught;

        public TrapLine() {}

        public TrapLine(String trapId, int placed, int caught) {
            this.trapId = trapId;
            this.placed = placed;
            this.caught = caught;
        }
    }

    /** Один рядок нарахування XP. */
    public static final class XpLine {
        public String key = "";
        public int xp;

        public XpLine() {}

        public XpLine(String key, int xp) {
            this.key = key;
            this.xp = xp;
        }
    }

    /** Відрізок часу, який матч провів в одній фазі. */
    public static final class PhaseSpan {
        public String phase = "";
        public int startTick;
        public int endTick;

        public PhaseSpan() {}

        public PhaseSpan(String phase, int startTick, int endTick) {
            this.phase = phase;
            this.startTick = startTick;
            this.endTick = endTick;
        }
    }

    /** Подія хронології. Значення полів — див. {@link HistoryEventType}. */
    public static final class Event {
        public int tick;
        public HistoryEventType type = HistoryEventType.PHASE;
        public int actor = -1;
        public int target = -1;
        public String arg = "";
        public int value;

        public Event() {}

        public Event(int tick, HistoryEventType type, int actor, int target, String arg, int value) {
            this.tick = tick;
            this.type = type;
            this.actor = actor;
            this.target = target;
            this.arg = arg == null ? "" : arg;
            this.value = value;
        }
    }

    // ── Запити ───────────────────────────────────────────────────────────

    public boolean involves(UUID playerId) {
        if (playerId == null) return false;
        for (Player p : players) if (playerId.equals(p.id)) return true;
        return false;
    }

    public Player player(int index) {
        return index >= 0 && index < players.size() ? players.get(index) : null;
    }

    public Player player(UUID id) {
        if (id == null) return null;
        for (Player p : players) if (id.equals(p.id)) return p;
        return null;
    }

    /** Ім'я учасника за індексом з події; «—», якщо індекс порожній. */
    public String nameOf(int index) {
        Player p = player(index);
        return p == null ? "—" : p.name;
    }

    /** Короткий рядок для списків. */
    public MatchBrief toBrief() {
        MatchBrief b = new MatchBrief();
        b.id = id;
        b.startedAtMs = startedAtMs;
        b.playedSeconds = playedSeconds;
        b.totalSeconds = totalSeconds;
        b.survivorsWon = survivorsWon;
        b.end = end;
        b.stage = stage;
        b.maniacId = maniacId;
        b.generatorsDone = generatorsDone;
        b.generatorsRequired = generatorsRequired;
        b.survivorTotal = survivorTotal;
        b.escaped = escaped;
        for (Player p : players) {
            MatchBrief.Part part = new MatchBrief.Part();
            part.id = p.id;
            part.name = p.name;
            part.role = p.role;
            part.outcome = p.outcome;
            part.xp = p.xpTotal;
            b.players.add(part);
        }
        return b;
    }

    /** Після читання з диска: старий файл міг не мати полів — замінюємо {@code null} значеннями за замовчуванням. */
    public MatchRecord normalize() {
        if (id == null) id = "";
        if (end == null) end = End.ESCAPE;
        if (stage == null) stage = "HUNT";
        if (maniacId == null) maniacId = "";
        if (players == null) players = new ArrayList<>();
        if (phases == null) phases = new ArrayList<>();
        if (events == null) events = new ArrayList<>();
        players.removeIf(p -> p == null || p.id == null);
        for (Player p : players) {
            if (p.name == null) p.name = "";
            if (p.role == null) p.role = Role.SURVIVOR;
            if (p.outcome == null) p.outcome = Outcome.CAUGHT;
            if (p.maniacId == null) p.maniacId = "";
            if (p.endPhase == null) p.endPhase = "";
            if (p.endCause == null) p.endCause = "";
            if (p.traps == null) p.traps = new ArrayList<>();
            if (p.xp == null) p.xp = new ArrayList<>();
            p.traps.removeIf(t -> t == null || t.trapId == null);
            p.xp.removeIf(x -> x == null || x.key == null);
        }
        phases.removeIf(s -> s == null || s.phase == null);
        events.removeIf(e -> e == null || e.type == null);
        for (Event e : events) if (e.arg == null) e.arg = "";
        return this;
    }

    // ── Мережа ───────────────────────────────────────────────────────────

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(format);
        buf.writeUtf(id, 32);
        buf.writeLong(startedAtMs);
        buf.writeLong(endedAtMs);
        buf.writeVarInt(totalSeconds);
        buf.writeVarInt(playedSeconds);
        buf.writeVarInt(playStartTick + 1);
        buf.writeBoolean(survivorsWon);
        buf.writeVarInt(end.ordinal());
        buf.writeUtf(stage, 32);
        buf.writeUtf(maniacId, 64);
        buf.writeVarInt(generatorsDone);
        buf.writeVarInt(generatorsRequired);
        buf.writeVarInt(objectiveMask);
        buf.writeVarInt(survivorTotal);
        buf.writeVarInt(escaped);
        buf.writeVarInt(eliminated);

        int pn = Math.min(players.size(), MAX_PLAYERS);
        buf.writeVarInt(pn);
        for (int i = 0; i < pn; i++) writePlayer(buf, players.get(i));

        int sn = Math.min(phases.size(), MAX_PHASES);
        buf.writeVarInt(sn);
        for (int i = 0; i < sn; i++) {
            PhaseSpan s = phases.get(i);
            buf.writeUtf(s.phase, 32);
            buf.writeVarInt(s.startTick);
            buf.writeVarInt(s.endTick);
        }

        int en = Math.min(events.size(), MAX_EVENTS);
        buf.writeVarInt(en);
        for (int i = 0; i < en; i++) {
            Event e = events.get(i);
            buf.writeVarInt(e.tick);
            buf.writeVarInt(e.type.ordinal());
            buf.writeVarInt(e.actor + 1);
            buf.writeVarInt(e.target + 1);
            buf.writeUtf(e.arg, 64);
            buf.writeVarInt(e.value);
        }
    }

    private static void writePlayer(FriendlyByteBuf buf, Player p) {
        buf.writeUUID(p.id);
        buf.writeUtf(p.name, 64);
        buf.writeVarInt(p.role.ordinal());
        buf.writeVarInt(p.outcome.ordinal());
        buf.writeUtf(p.maniacId, 64);
        buf.writeVarInt(p.survivedSeconds);
        buf.writeVarInt(p.hitsDealt);
        buf.writeVarInt(p.hitsTaken);
        buf.writeVarInt(p.downs);
        buf.writeVarInt(p.hooks);
        buf.writeVarInt(p.rescues);
        buf.writeVarInt(p.repairSeconds);
        buf.writeVarInt(p.endTick + 1);
        buf.writeUtf(p.endPhase, 32);
        buf.writeUtf(p.endCause, 64);
        buf.writeVarInt(p.killerIdx + 1);
        int tn = Math.min(p.traps.size(), MAX_LINES);
        buf.writeVarInt(tn);
        for (int i = 0; i < tn; i++) {
            TrapLine t = p.traps.get(i);
            buf.writeUtf(t.trapId, 64);
            buf.writeVarInt(t.placed);
            buf.writeVarInt(t.caught);
        }
        int xn = Math.min(p.xp.size(), MAX_LINES);
        buf.writeVarInt(xn);
        for (int i = 0; i < xn; i++) {
            XpLine x = p.xp.get(i);
            buf.writeUtf(x.key, 32);
            buf.writeVarInt(x.xp);
        }
        buf.writeVarInt(p.xpTotal);
        buf.writeVarLong(p.xpBefore + 1L);
        buf.writeVarLong(p.xpAfter + 1L);
    }

    public static MatchRecord read(FriendlyByteBuf buf) {
        MatchRecord r = new MatchRecord();
        r.format = buf.readVarInt();
        r.id = buf.readUtf(32);
        r.startedAtMs = buf.readLong();
        r.endedAtMs = buf.readLong();
        r.totalSeconds = buf.readVarInt();
        r.playedSeconds = buf.readVarInt();
        r.playStartTick = buf.readVarInt() - 1;
        r.survivorsWon = buf.readBoolean();
        r.end = enumOf(End.values(), buf.readVarInt());
        r.stage = buf.readUtf(32);
        r.maniacId = buf.readUtf(64);
        r.generatorsDone = buf.readVarInt();
        r.generatorsRequired = buf.readVarInt();
        r.objectiveMask = buf.readVarInt();
        r.survivorTotal = buf.readVarInt();
        r.escaped = buf.readVarInt();
        r.eliminated = buf.readVarInt();

        int pn = Math.min(buf.readVarInt(), MAX_PLAYERS);
        for (int i = 0; i < pn; i++) r.players.add(readPlayer(buf));

        int sn = Math.min(buf.readVarInt(), MAX_PHASES);
        for (int i = 0; i < sn; i++) {
            r.phases.add(new PhaseSpan(buf.readUtf(32), buf.readVarInt(), buf.readVarInt()));
        }

        int en = Math.min(buf.readVarInt(), MAX_EVENTS);
        for (int i = 0; i < en; i++) {
            int tick = buf.readVarInt();
            HistoryEventType type = HistoryEventType.byOrdinal(buf.readVarInt());
            int actor = buf.readVarInt() - 1;
            int target = buf.readVarInt() - 1;
            String arg = buf.readUtf(64);
            int value = buf.readVarInt();
            r.events.add(new Event(tick, type, actor, target, arg, value));
        }
        return r;
    }

    private static Player readPlayer(FriendlyByteBuf buf) {
        Player p = new Player();
        p.id = buf.readUUID();
        p.name = buf.readUtf(64);
        p.role = enumOf(Role.values(), buf.readVarInt());
        p.outcome = enumOf(Outcome.values(), buf.readVarInt());
        p.maniacId = buf.readUtf(64);
        p.survivedSeconds = buf.readVarInt();
        p.hitsDealt = buf.readVarInt();
        p.hitsTaken = buf.readVarInt();
        p.downs = buf.readVarInt();
        p.hooks = buf.readVarInt();
        p.rescues = buf.readVarInt();
        p.repairSeconds = buf.readVarInt();
        p.endTick = buf.readVarInt() - 1;
        p.endPhase = buf.readUtf(32);
        p.endCause = buf.readUtf(64);
        p.killerIdx = buf.readVarInt() - 1;
        int tn = Math.min(buf.readVarInt(), MAX_LINES);
        for (int i = 0; i < tn; i++) p.traps.add(new TrapLine(buf.readUtf(64), buf.readVarInt(), buf.readVarInt()));
        int xn = Math.min(buf.readVarInt(), MAX_LINES);
        for (int i = 0; i < xn; i++) p.xp.add(new XpLine(buf.readUtf(32), buf.readVarInt()));
        p.xpTotal = buf.readVarInt();
        p.xpBefore = buf.readVarLong() - 1L;
        p.xpAfter = buf.readVarLong() - 1L;
        return p;
    }

    static <E extends Enum<E>> E enumOf(E[] values, int ordinal) {
        return values[Math.floorMod(ordinal, values.length)];
    }
}

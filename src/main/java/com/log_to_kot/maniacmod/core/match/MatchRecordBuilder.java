package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Збирає {@link MatchRecord} зі стану матчу. Лише збирання: ні IO, ні мережі, ні правил показу.
 *
 * <p>Цифри гравців беруться з {@link MatchSummaryPacket} — того самого знімка, що бачить екран
 * підсумків і з якого рахується профіль, тож історія, підсумок і профіль не можуть розійтись. З
 * хронології ({@link MatchTimeline}) додається те, чого підсумок не знає: у якій фазі й чому вибув
 * кожен, хто його добив, відрізки фаз і самі події. Гравці, що вийшли без тіла, у підсумку відсутні —
 * їх береться зі складу хронології як {@code LEFT}.</p>
 */
final class MatchRecordBuilder {

    private static final DateTimeFormatter ID_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private MatchRecordBuilder() {}

    /**
     * @param rewards XP кожного, хто його справді отримав (онлайн-учасники); {@code null} — XP не було
     *                (перерваний матч)
     */
    static MatchRecord build(MatchContext ctx, MatchSummaryPacket summary,
                             Map<UUID, MatchRewardPacket> rewards, MatchRecord.End end) {
        MatchTimeline tl = ctx.timeline();
        long now = ctx.matchTick();
        long endedAt = System.currentTimeMillis();

        MatchRecord r = new MatchRecord();
        r.id = ID_STAMP.format(java.time.Instant.ofEpochMilli(tl.startedAtMs()))
            + "-" + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
        r.startedAtMs = tl.startedAtMs();
        r.endedAtMs = endedAt;
        r.totalSeconds = (int) (now / 20L);
        r.playedSeconds = summary.playedSeconds();
        r.playStartTick = (int) ctx.stats().playStartTick();
        r.survivorsWon = summary.survivorsWon();
        r.end = end;
        r.generatorsDone = summary.generatorsDone();
        r.generatorsRequired = summary.generatorsRequired();
        r.objectiveMask = summary.objectiveMask();
        r.phases.addAll(tl.spans(now));
        r.stage = deepestStage(r);
        r.events.addAll(tl.events());

        MatchSummaryPacket.Entry maniacEntry = null;
        for (UUID id : tl.roster()) {
            MatchSummaryPacket.Entry e = entryOf(summary, id);
            MatchRecord.Player p = player(id, e, tl, ctx, now, rewards == null ? null : rewards.get(id));
            r.players.add(p);
            if (p.role == MatchRecord.Role.MANIAC) {
                maniacEntry = e;
                r.maniacId = p.maniacId;
            } else {
                r.survivorTotal++;
                if (p.outcome == MatchRecord.Outcome.ESCAPED) r.escaped++;
                else if (p.outcome == MatchRecord.Outcome.ELIMINATED) r.eliminated++;
            }
        }
        // Маніяк міг змінитись між складом і підсумком (вибір у меню ролей) — підсумок знає актуальне.
        if (maniacEntry != null && maniacEntry.maniacId() != null && !maniacEntry.maniacId().isEmpty()) {
            r.maniacId = maniacEntry.maniacId();
        } else if (ctx.maniacArchetype() != null) {
            r.maniacId = ctx.maniacArchetype().id();
        }
        for (MatchRecord.Player p : r.players) {
            if (p.role == MatchRecord.Role.MANIAC && !r.maniacId.isEmpty()) p.maniacId = r.maniacId;
        }
        return r.normalize();
    }

    private static MatchRecord.Player player(UUID id, MatchSummaryPacket.Entry e, MatchTimeline tl,
                                             MatchContext ctx, long now, MatchRewardPacket reward) {
        MatchRecord.Player p = new MatchRecord.Player();
        p.id = id;
        String name = e == null ? null : e.name();
        if (name == null || name.isEmpty() || "?".equals(name)) name = tl.nameOf(id);
        p.name = name == null ? "?" : name;

        if (e != null) {
            p.role = e.result() == MatchSummaryPacket.Result.MANIAC
                ? MatchRecord.Role.MANIAC : MatchRecord.Role.SURVIVOR;
            p.outcome = switch (e.result()) {
                case ESCAPED -> MatchRecord.Outcome.ESCAPED;
                case ELIMINATED -> MatchRecord.Outcome.ELIMINATED;
                case CAUGHT -> MatchRecord.Outcome.CAUGHT;
                case MANIAC -> MatchRecord.Outcome.MANIAC;
            };
            p.maniacId = e.maniacId() == null ? "" : e.maniacId();
            p.survivedSeconds = e.survivedSeconds();
            p.hitsDealt = e.hitsDealt();
            p.hitsTaken = e.hitsTaken();
            p.downs = e.downs();
            p.hooks = e.hooks();
            p.rescues = e.rescues();
            p.repairSeconds = e.repairSeconds();
            for (MatchSummaryPacket.TrapLine t : e.traps()) {
                p.traps.add(new MatchRecord.TrapLine(t.trapId(), t.placed(), t.caught()));
            }
        } else {
            // Вийшов без тіла: зі складу, статистика — з лічильників матчу.
            p.role = ctx.isManiac(id) ? MatchRecord.Role.MANIAC : MatchRecord.Role.SURVIVOR;
            p.outcome = MatchRecord.Outcome.LEFT;
            p.survivedSeconds = (int) (ctx.stats().survivedTicks(id, now) / 20L);
            p.hitsTaken = (int) ctx.stats().get(id, MatchStat.HITS_TAKEN);
            p.downs = (int) ctx.stats().get(id, MatchStat.DOWNS);
            p.rescues = (int) ctx.stats().get(id, MatchStat.RESCUES);
            p.repairSeconds = (int) (ctx.stats().get(id, MatchStat.REPAIR_TICKS) / 20L);
        }

        int endTick = tl.endTickOf(id);
        if (endTick >= 0) {
            p.endTick = endTick;
            p.endPhase = tl.endPhaseOf(id);
            p.endCause = tl.endCauseOf(id);
            if (p.outcome == MatchRecord.Outcome.ELIMINATED) {
                p.killerIdx = tl.indexOf(tl.lastHitterOf(id));
            }
        }

        if (reward != null) {
            for (MatchRewardPacket.Line l : reward.lines()) p.xp.add(new MatchRecord.XpLine(l.key(), l.xp()));
            p.xpTotal = reward.total();
            p.xpBefore = reward.xpBefore();
            p.xpAfter = reward.xpAfter();
        }
        return p;
    }

    private static MatchSummaryPacket.Entry entryOf(MatchSummaryPacket summary, UUID id) {
        for (MatchSummaryPacket.Entry e : summary.entries()) if (e.id().equals(id)) return e;
        return null;
    }

    /** Найглибша ігрова фаза з відрізків: HUNT &lt; POWERED &lt; HELI_WAIT &lt; FINALE (порядок enum-а). */
    private static String deepestStage(MatchRecord r) {
        GamePhase deepest = null;
        for (MatchRecord.PhaseSpan s : r.phases) {
            GamePhase phase;
            try {
                phase = GamePhase.valueOf(s.phase);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (!phase.isGameplay()) continue;
            if (deepest == null || phase.ordinal() > deepest.ordinal()) deepest = phase;
        }
        return deepest == null ? GamePhase.HUNT.name() : deepest.name();
    }
}

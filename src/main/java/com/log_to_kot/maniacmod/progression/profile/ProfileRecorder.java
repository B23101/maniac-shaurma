package com.log_to_kot.maniacmod.progression.profile;

import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result;

/**
 * Вносить результат одного матчу в довготривалий профіль. Рахує з {@link MatchSummaryPacket} —
 * того самого знімка, що бачить клієнт, тож екран підсумків і профіль не розходяться.
 * Викликається рівно раз на гравця — на вході у фазу ENDING ({@code MatchRewardService}).
 */
public final class ProfileRecorder {

    private ProfileRecorder() {}

    /**
     * Спільний прогрес гравця ДО нарахування. Нагорода йде в доріжку ролі (її видно в профілі),
     * але рівень спільний для виживого й маніяка, тож «до/після» рахуємо із загальної суми.
     */
    public static long trackXp(PlayerProfile p, Entry me) {
        return p.totalXp();
    }

    public static void record(PlayerProfile p, Entry me, MatchSummaryPacket summary, int gainedXp) {
        boolean survivorsWon = summary.survivorsWon();
        if (me.result() == Result.MANIAC) {
            PlayerProfile.Maniac m = p.maniac(idOf(me));
            boolean won = !survivorsWon;
            m.xp += gainedXp;
            m.matches++;
            if (won) { m.wins++; m.winStreak++; m.bestWinStreak = Math.max(m.bestWinStreak, m.winStreak); }
            else m.winStreak = 0;
            m.hitsDealt += me.hitsDealt();
            m.downs += me.downs();
            m.hooks += me.hooks();
            m.playedSeconds += summary.playedSeconds();
            m.bestDowns = Math.max(m.bestDowns, me.downs());
            for (MatchSummaryPacket.TrapLine line : me.traps()) {
                PlayerProfile.Trap t = m.traps.computeIfAbsent(line.trapId(), k -> new PlayerProfile.Trap());
                t.placed += line.placed();
                t.caught += line.caught();
            }
        } else {
            PlayerProfile.Survivor s = p.survivor;
            p.survivorXp += gainedXp;
            s.matches++;
            if (survivorsWon) { s.wins++; s.winStreak++; s.bestWinStreak = Math.max(s.bestWinStreak, s.winStreak); }
            else s.winStreak = 0;
            if (me.result() == Result.ESCAPED) s.escapes++; else s.eliminated++;
            s.hitsTaken += me.hitsTaken();
            s.rescues += me.rescues();
            s.hooked += me.hooks();
            s.repairSeconds += me.repairSeconds();
            s.survivedSeconds += me.survivedSeconds();
            s.bestSurvivedSeconds = Math.max(s.bestSurvivedSeconds, me.survivedSeconds());
        }
    }

    private static String idOf(Entry me) {
        String id = me.maniacId();
        return id == null || id.isEmpty() ? "unknown" : id;
    }
}

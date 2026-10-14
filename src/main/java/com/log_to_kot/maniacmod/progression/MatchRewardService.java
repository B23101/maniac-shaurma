package com.log_to_kot.maniacmod.progression;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Result;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Нараховує XP за матч і шле гравцеві розбивку для екрана підсумків.
 *
 * <p>Рахує ЛИШЕ з {@link MatchSummaryPacket} — того самого знімка, що бачить клієнт, тож сервер
 * і екран не можуть розійтись. Кожна нагорода одноразова за матч («ліміти нагород» з екрана):
 * повторні дії XP не множать. Викликається рівно один раз — на вході у фазу ENDING.</p>
 */
public final class MatchRewardService {

    private MatchRewardService() {}

    /** Розбивка XP для одного учасника (без запису на диск). */
    public static List<MatchRewardPacket.Line> breakdown(Entry me, boolean survivorsWon) {
        List<MatchRewardPacket.Line> lines = new ArrayList<>();
        boolean maniac = me.result() == Result.MANIAC;
        boolean won = maniac != survivorsWon;   // маніяк виграє, коли виживі програли; виживі — навпаки

        add(lines, "complete", ManiacConfigs.get(ConfigSchema.XP_COMPLETE));
        if (won) add(lines, "win", ManiacConfigs.get(ConfigSchema.XP_WIN));

        if (maniac) {
            if (me.hooks() > 0) add(lines, "hooks", ManiacConfigs.get(ConfigSchema.XP_MANIAC_HOOKS));
            if (me.downs() >= 3) add(lines, "downs", ManiacConfigs.get(ConfigSchema.XP_MANIAC_DOWNS));
        } else {
            if (me.result() == Result.ESCAPED) add(lines, "escape", ManiacConfigs.get(ConfigSchema.XP_ESCAPE));
            if (me.repairSeconds() >= ManiacConfigs.get(ConfigSchema.XP_GENERATORS_MIN_SECONDS)) {
                add(lines, "generators", ManiacConfigs.get(ConfigSchema.XP_GENERATORS));
            }
            if (me.rescues() > 0) add(lines, "rescue", ManiacConfigs.get(ConfigSchema.XP_RESCUE));
        }
        return lines;
    }

    private static void add(List<MatchRewardPacket.Line> lines, String key, int xp) {
        if (xp > 0) lines.add(new MatchRewardPacket.Line(key, xp));
    }

    /**
     * Нарахувати й надіслати кожному онлайн-учаснику його нагороду.
     *
     * @return що саме отримав кожен (UUID → той самий пакет, що пішов гравцеві) — історія матчів
     *         записує рівно ці цифри, а не рахує їх вдруге
     */
    public static java.util.Map<java.util.UUID, MatchRewardPacket> awardAll(MatchSummaryPacket summary,
                                                                           List<ServerPlayer> players) {
        java.util.Map<java.util.UUID, MatchRewardPacket> awarded = new java.util.LinkedHashMap<>();
        for (ServerPlayer player : players) {
            Entry mine = null;
            for (Entry e : summary.entries()) if (e.id().equals(player.getUUID())) mine = e;
            if (mine == null) continue;   // не учасник матчу (глядач)

            List<MatchRewardPacket.Line> lines = breakdown(mine, summary.survivorsWon());
            int gained = 0;
            for (MatchRewardPacket.Line l : lines) gained += l.xp();
            // XP пишемо в доріжку ролі, але рівень/«до-після» — спільні (PlayerProfile.totalXp()).
            var profile = com.log_to_kot.maniacmod.progression.profile.PlayerProfileStorage.load(player.getUUID());
            long before = com.log_to_kot.maniacmod.progression.profile.ProfileRecorder.trackXp(profile, mine);
            long after = before + gained;
            com.log_to_kot.maniacmod.progression.profile.ProfileRecorder.record(profile, mine, summary, gained);
            // Нік/скін/час для лідербордів — разом із цим самим записом, другого запису на диск нема.
            com.log_to_kot.maniacmod.leaderboard.LeaderboardService.stamp(player, profile);
            com.log_to_kot.maniacmod.progression.profile.PlayerProfileStorage.save(
                player.getUUID(), player.getGameProfile().getName(), profile);
            MatchRewardPacket packet = new MatchRewardPacket(lines, before, after);
            awarded.put(player.getUUID(), packet);
            ModNetwork.toPlayer(player, packet);
        }
        return awarded;
    }
}

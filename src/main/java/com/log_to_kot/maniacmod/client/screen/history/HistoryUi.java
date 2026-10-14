package com.log_to_kot.maniacmod.client.screen.history;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.history.HistoryEventType;
import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Спільні дрібниці екранів історії: форматування, назви, кольори й текст подій. Усе — безстанові
 * функції; жодного власного вигляду (кольори беруться з {@code ManiacUiTheme}/{@code ManiacTabTheme}).
 *
 * <p>Стадії матчу — {@link #STAGES}: HUNT → POWERED → HELI_WAIT → FINALE. Нова ігрова фаза в
 * {@code GamePhase} мусить з'явитись і тут (і в lang: {@code maniacmod.history.stage.<id>}).</p>
 */
@OnlyIn(Dist.CLIENT)
final class HistoryUi {

    /** Ігрові стадії за порядком проходження. */
    static final String[] STAGES = {"HUNT", "POWERED", "HELI_WAIT", "FINALE"};

    static final int SILVER = ManiacTabTheme.SILVER;
    static final int BLOOD = ManiacTabTheme.BLOOD;
    static final int OK = ManiacTabTheme.OK;
    static final int WARN = ManiacTabTheme.WARN;
    static final int CYAN = ManiacTabTheme.CYAN;
    static final int DIM = ManiacUiTheme.TEXT_DIM;

    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private HistoryUi() {}

    // ── Текст ────────────────────────────────────────────────────────────

    static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /** Текст зі scale і вирівнюванням: 0 — ліворуч, 1 — по центру, 2 — праворуч. */
    static void text(GuiGraphics g, Font font, String s, float x, float y, float scale, int argb, int align) {
        if (s == null || s.isEmpty() || ((argb >>> 24) & 0xFF) < 8) return;
        int w = font.width(s);
        float ox = align == 1 ? -w / 2f : align == 2 ? -w : 0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, Math.round(ox), 0, argb, false);
        g.pose().popPose();
    }

    /** Обрізає рядок трикрапкою, щоб він влазив у {@code maxPx} при заданому scale. */
    static String fit(Font font, String s, float scale, float maxPx) {
        if (s == null) return "";
        if (font.width(s) * scale <= maxPx) return s;
        String ell = "…";
        int end = s.length();
        while (end > 1 && font.width(s.substring(0, end) + ell) * scale > maxPx) end--;
        return s.substring(0, Math.max(0, end)) + ell;
    }

    static int a(int argb, float mul) {
        return GeneratorFuelUiTheme.alpha(argb, mul);
    }

    static int mix(int x, int y, float t) {
        return GeneratorFuelUiTheme.mix(x, y, t);
    }

    static boolean inside(double mx, double my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    // ── Формати ──────────────────────────────────────────────────────────

    /** {@code m:ss} або {@code h:mm:ss}. */
    static String clock(int seconds) {
        seconds = Math.max(0, seconds);
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    static String date(long ms) { return DATE.format(Instant.ofEpochMilli(ms)); }

    static String time(long ms) { return TIME.format(Instant.ofEpochMilli(ms)); }

    /** «5 хв тому», «3 год тому», «2 дні тому» — для шапки картки. */
    static String ago(long ms) {
        long sec = Math.max(0L, (System.currentTimeMillis() - ms) / 1000L);
        if (sec < 60) return tr("maniacmod.history.ago.now");
        if (sec < 3600) return tr("maniacmod.history.ago.min", sec / 60);
        if (sec < 86400) return tr("maniacmod.history.ago.hour", sec / 3600);
        return tr("maniacmod.history.ago.day", sec / 86400);
    }

    // ── Назви ────────────────────────────────────────────────────────────

    /** Архетип маніяка; {@code null}, якщо його вже нема в реєстрі (старий запис, прибраний персонаж). */
    static ManiacArchetype archetype(String id) {
        if (id == null || id.isEmpty()) return null;
        return ManiacRegistry.all().get(id);
    }

    static String maniacName(String id) {
        ManiacArchetype a = archetype(id);
        if (a != null) return a.displayName();
        return id == null || id.isEmpty() ? tr("maniacmod.history.unknown_maniac") : id;
    }

    static String trapName(String id) {
        String key = "maniacmod.trap.name." + id;
        return net.minecraft.client.resources.language.I18n.exists(key) ? tr(key) : id;
    }

    static String stageName(String stage) {
        return tr("maniacmod.history.stage." + stage.toLowerCase(Locale.ROOT));
    }

    static int stageIndex(String stage) {
        for (int i = 0; i < STAGES.length; i++) if (STAGES[i].equals(stage)) return i;
        return 0;
    }

    static String phaseName(String phase) {
        String key = "maniacmod.history.phase." + phase.toLowerCase(Locale.ROOT);
        return net.minecraft.client.resources.language.I18n.exists(key) ? tr(key) : phase;
    }

    static String endName(MatchRecord.End end) {
        return tr("maniacmod.history.end." + end.name().toLowerCase(Locale.ROOT));
    }

    static String outcomeName(MatchRecord.Outcome o) {
        return tr("maniacmod.history.outcome." + o.name().toLowerCase(Locale.ROOT));
    }

    static int outcomeColor(MatchRecord.Outcome o) {
        return switch (o) {
            case ESCAPED -> OK;
            case ELIMINATED -> BLOOD;
            case CAUGHT -> WARN;
            case MANIAC -> ManiacUiTheme.BAR_FILL_DANGER;
            case LEFT -> DIM;
        };
    }

    /** Колір результату матчу: срібло — виграли виживі, багрянець — маніяк, сірий — перервано. */
    static int resultColor(boolean survivorsWon, MatchRecord.End end) {
        if (end == MatchRecord.End.ABORTED) return DIM;
        return survivorsWon ? SILVER : BLOOD;
    }

    static String resultTitle(boolean survivorsWon, MatchRecord.End end) {
        if (end == MatchRecord.End.ABORTED) return tr("maniacmod.history.result.aborted");
        return tr(survivorsWon ? "maniacmod.history.result.survivors" : "maniacmod.history.result.maniac");
    }

    static ResourceLocation skinOf(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(id);
        if (info != null) return info.getSkinLocation();
        return DefaultPlayerSkin.getDefaultSkin(id);
    }

    // ── Події ────────────────────────────────────────────────────────────

    static int eventColor(HistoryEventType t) {
        return switch (t) {
            case MATCH_START, PHASE -> DIM;
            case HIT, HOOKED, ELIMINATED, TRAP_TRIGGERED -> BLOOD;
            case DOWNED -> WARN;
            case RESCUED, ESCAPED -> OK;
            case GENERATOR_DONE -> CYAN;
            case OBJECTIVE, FORCE_END -> SILVER;
            case TRAP_PLACED -> mix(WARN, BLOOD, 0.4f);
            case PLAYER_LEFT -> DIM;
        };
    }

    /** Категорія події для фільтрів хронології: 0 — усе, 1 — бій, 2 — вибуття/втечі, 3 — ціль. */
    static int eventCategory(HistoryEventType t) {
        return switch (t) {
            case HIT, DOWNED, HOOKED, RESCUED, TRAP_PLACED, TRAP_TRIGGERED -> 1;
            case ELIMINATED, ESCAPED, PLAYER_LEFT -> 2;
            case GENERATOR_DONE, OBJECTIVE, PHASE, FORCE_END, MATCH_START -> 3;
        };
    }

    /** Одне речення про подію. Імена беруться зі складу запису за індексами. */
    static String eventText(MatchRecord r, MatchRecord.Event e) {
        String actor = r.nameOf(e.actor);
        String target = r.nameOf(e.target);
        return switch (e.type) {
            case MATCH_START -> tr("maniacmod.history.ev.match_start", maniacName(e.arg), e.value);
            case PHASE -> tr("maniacmod.history.ev.phase", phaseName(e.arg));
            case HIT -> tr("maniacmod.history.ev.hit", actor, target, e.value);
            case DOWNED -> e.actor >= 0
                ? tr("maniacmod.history.ev.downed_by", target, actor)
                : tr("maniacmod.history.ev.downed", target);
            case HOOKED -> tr("maniacmod.history.ev.hooked", actor, target);
            case RESCUED -> e.target >= 0
                ? tr("maniacmod.history.ev.rescued", actor, target)
                : tr("maniacmod.history.ev.rescued_anon", actor);
            case ESCAPED -> tr("maniacmod.history.ev.escaped", actor);
            case ELIMINATED -> tr("maniacmod.history.ev.eliminated", target, causeText(e.arg, actor));
            case GENERATOR_DONE -> e.actor >= 0
                ? tr("maniacmod.history.ev.generator_by", actor, e.value, r.generatorsRequired)
                : tr("maniacmod.history.ev.generator", e.value, r.generatorsRequired);
            case OBJECTIVE -> tr("maniacmod.history.ev.objective",
                tr("maniacmod.history.objective." + e.arg.toLowerCase(Locale.ROOT)));
            case TRAP_PLACED -> tr("maniacmod.history.ev.trap_placed", actor, trapName(e.arg));
            case TRAP_TRIGGERED -> e.target >= 0
                ? tr("maniacmod.history.ev.trap_hit", trapName(e.arg), target)
                : tr("maniacmod.history.ev.trap_hit_anon", trapName(e.arg));
            case PLAYER_LEFT -> tr("maniacmod.history.ev.left" + ("body".equals(e.arg) ? "_body" : ""), actor);
            case FORCE_END -> tr("maniacmod.history.ev.force_end." + ("survivors".equals(e.arg) ? "survivors" : "maniac"));
        };
    }

    /** Причина вибуття для ELIMINATED: {@code maniac} / {@code trap:<id>} / {@code bleedout} / {@code left}. */
    static String causeText(String cause, String killerName) {
        if (cause == null) cause = "";
        if (cause.startsWith("trap:")) return tr("maniacmod.history.cause.trap", trapName(cause.substring(5)));
        return switch (cause) {
            case "maniac" -> killerName == null || killerName.equals("—")
                ? tr("maniacmod.history.cause.maniac_anon")
                : tr("maniacmod.history.cause.maniac", killerName);
            case "left" -> tr("maniacmod.history.cause.left");
            case "bleedout" -> tr("maniacmod.history.cause.bleedout");
            default -> "";
        };
    }
}

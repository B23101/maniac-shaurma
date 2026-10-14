package com.log_to_kot.maniacmod.client.screen.statistics;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry;
import com.log_to_kot.maniacmod.progression.XpCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Підсумки матчу — компактний екран «досвід за матч» (темні панелі поверх світу).
 *
 * <pre>
 *  ┌ ліва панель ┐ ┌ ПІДСУМКИ МАТЧУ ──────┐ ┌ ДОСВІД ЗА МАТЧ ┐
 *  │ МАТЧ ЗАВЕРШЕНО│ │ НАРАХУВАННЯ         │ │      +6        │
 *  │   [аватар]   │ │ ▌Завершення матчу +2│ │ XP ЗАРАХОВАНО  │
 *  │   ПЕРЕМОГА   │ │ ▌Перемога        +2 │ │ ПРОГРЕС РІВНЯ  │
 *  │   Виживший   │ │ ▌Втеча           +1 │ │ РІВЕНЬ 2       │
 *  │ ЧАС МАТЧУ    │ │ ▌Генератори      +1 │ │ ▬▬▬▬▬▬▬▬▬▬     │
 *  │ 32хв 19с     │ └─────────────────────┘ │ 541 / 550 XP   │
 *  └──────────────┘ [Статистика]   [Пропустити анімацію]
 * </pre>
 *
 * <p><b>Фон — freecam.</b> Екран не малює суцільний фон: за панелями видно світ із точки
 * {@code STATS_CAMERA} (камеру ставить сервер — {@code victory/StatsCameraModule}); панелі лише
 * притемнюють його. Якщо точки нема — видно те, що бачить гравець.</p>
 *
 * <p>Дані: {@link MatchSummaryPacket} (результат, час, роль) + {@link MatchRewardPacket} (рядки XP
 * і загальний XP до/після). Рівень і смужка рахуються з {@link XpCurve}, тож при переході рівня
 * смужка чесно добігає до кінця й іде з нуля. «Статистика» відкриває детальну таблицю
 * ({@link MatchStatsScreen}); «Пропустити анімацію» домотує все до кінця, після чого стає «Закрити».</p>
 */
public final class MatchResultScreen extends Screen {

    private static final int GOLD = 0xFFE8C547;
    private static final int GOLD_DIM = 0xFF9C8230;
    private static final int WHITE = 0xFFE9EAEC;
    private static final int GRAY = 0xFF8A8D93;
    private static final int PANEL = 0xE60A0B0E;
    private static final int PANEL_SOFT = 0x990A0B0E;
    private static final int LINE = 0xFF2A2C31;
    private static final int LOSS = 0xFFD05A5A;

    private static final long ROW_START_MS = 450L;
    private static final long ROW_STEP_MS = 230L;
    private static final long XP_COUNT_MS = 800L;
    private static final long BAR_MS = 1300L;
    private static final long ALL_DONE_PAD_MS = 400L;

    private long openedAtMs;
    /** Скільки мс анімації «підмотано» кнопкою «Пропустити» (велике число — усе завершено). */
    private long skippedBonusMs;

    // Прямокутники кнопок (оновлюються щокадру) для обробки кліків.
    private int statsX, statsY, statsW, statsH;
    private int skipX, skipY, skipW, skipH;

    /**
     * Знімок даних на момент відкриття: екран живе довше за матч (його скидають, а ClientMatchState
     * на RESET/LOBBY обнуляється), тож читати стан щокадру не можна.
     */
    private final MatchSummaryPacket summary = ClientMatchState.summary();
    private final MatchRewardPacket reward = ClientMatchState.reward();
    private final boolean outcomeSurvivorsWon =
        ClientMatchState.outcomeKnown() && ClientMatchState.outcomeSurvivorsWon();

    public MatchResultScreen() {
        super(Component.translatable("maniacmod.result.title"));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /**
     * Закриття (кнопка «Закрити», Esc): повідомляємо сервер — він знімає freecam і повертає нас у
     * лобі. До цього моменту екран і камера лишаються, навіть коли матч уже скинуто.
     */
    @Override
    public void onClose() {
        com.log_to_kot.maniacmod.net.ModNetwork.toServer(
            new com.log_to_kot.maniacmod.net.c2s.intent.StatsViewClosePacket());
        super.onClose();
    }

    // ── Дані ─────────────────────────────────────────────────────────────

    private static String duration(int seconds) {
        return String.format(Locale.ROOT, "%dхв %02dс", seconds / 60, seconds % 60);
    }

    private static Entry mine(MatchSummaryPacket s) {
        if (s == null) return null;
        Minecraft mc = Minecraft.getInstance();
        UUID me = mc.player == null ? null : mc.player.getUUID();
        for (Entry e : s.entries()) if (e.id().equals(me)) return e;
        return null;
    }

    private long elapsed(long now) {
        return now - openedAtMs + skippedBonusMs;
    }

    private long animLength(int rows) {
        return ROW_START_MS + rows * ROW_STEP_MS + XP_COUNT_MS + BAR_MS + ALL_DONE_PAD_MS;
    }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (openedAtMs == 0L) {
            openedAtMs = now;
            UiAnim.playAppear();
        }
        long t = elapsed(now);

        Entry me = mine(summary);
        boolean survivorsWon = summary != null ? summary.survivorsWon() : outcomeSurvivorsWon;
        boolean maniac = me != null && me.result() == MatchSummaryPacket.Result.MANIAC;
        boolean won = me == null ? survivorsWon : (maniac != survivorsWon);
        List<MatchRewardPacket.Line> lines = reward == null ? List.of() : reward.lines();

        // Масштаб тексту від висоти екрана: 1.0 на 540 (GUI x2 при 1080p), в межах 0.75..1.6.
        float k = Math.max(0.75f, Math.min(1.6f, height / 540f));
        float open = ease(t, 0, 500);

        // Загальне притемнення світу + м'яка віньєтка знизу.
        g.fill(0, 0, width, height, BloodFx.a(0xFF000000, 0.38f * open));
        g.fillGradient(0, height * 3 / 4, width, height, 0x00000000, BloodFx.a(0xFF000000, 0.55f * open));

        int leftW = Math.round(width * 0.268f);
        int cx = leftW + Math.round(width * 0.03f);
        int cw = Math.round(width * 0.39f);
        int rx = Math.round(width * 0.71f);
        int rw = width - rx - Math.round(width * 0.01f);

        drawLeft(g, leftW, k, t, open, me, summary, won, maniac, survivorsWon);
        drawCenter(g, cx, cw, k, t, lines, reward);
        drawRight(g, rx, rw, k, t, lines.size(), reward);
        drawFooter(g, cx, cw, k, t, lines.size(), open, mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    // ── Ліва панель ──────────────────────────────────────────────────────

    private void drawLeft(GuiGraphics g, int w, float k, long t, float open, Entry me,
                          MatchSummaryPacket summary, boolean won, boolean maniac, boolean survivorsWon) {
        int slide = Math.round((1f - open) * -30f);
        g.pose().pushPose();
        g.pose().translate(slide, 0, 0);
        g.fill(0, 0, w, height, PANEL);
        g.fill(w - 1, 0, w, height, LINE);

        int pad = Math.round(w * 0.065f);
        text(g, tr("maniacmod.result.finished"), pad, height * 0.066f, 0.85f * k, GRAY, 0);

        // Аватар: обличчя гравця в рамці.
        int face = Math.round(height * 0.125f);
        int fx = w / 2 - face / 2;
        int fy = Math.round(height * 0.18f);
        g.fill(fx - 3, fy - 3, fx + face + 3, fy + face + 3, LINE);
        g.fill(fx - 2, fy - 2, fx + face + 2, fy + face + 2, 0xFF14151A);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) PlayerFaceRenderer.draw(g, mc.player.getSkinTextureLocation(), fx, fy, face);

        // Результат.
        float rt = ease(t, 150, 600);
        int color = won ? GOLD : LOSS;
        String result = tr(won ? "maniacmod.result.win" : "maniacmod.result.loss");
        text(g, result, w / 2f, height * 0.395f, 2.3f * k, BloodFx.a(color, rt), 1);
        String role = me == null ? tr(survivorsWon ? "maniacmod.result.big_survivors" : "maniacmod.result.big_maniac")
            : tr(maniac ? "maniacmod.result.role.maniac" : "maniacmod.result.role.survivor");
        text(g, role, w / 2f, height * 0.47f, 1.0f * k, BloodFx.a(WHITE, rt), 1);

        int lineY = Math.round(height * 0.53f);
        int lw = Math.round((w - 2 * pad) * ease(t, 250, 600));
        g.fill(pad, lineY, pad + lw, lineY + 2, color);

        // Час матчу.
        text(g, tr("maniacmod.result.match_time"), pad, height * 0.595f, 0.85f * k, GRAY, 0);
        String time = summary == null ? "—" : duration(Math.round(summary.playedSeconds() * ease(t, 300, 900)));
        text(g, time, pad, height * 0.645f, 1.6f * k, WHITE, 0);

        text(g, tr("maniacmod.result.saved_history"), pad, height * 0.79f, 0.85f * k, GRAY, 0);
        g.pose().popPose();
    }

    // ── Центр: нарахування ───────────────────────────────────────────────

    private void drawCenter(GuiGraphics g, int x, int w, float k, long t,
                            List<MatchRewardPacket.Line> lines, MatchRewardPacket reward) {
        text(g, tr("maniacmod.result.heading"), x, height * 0.052f, 1.6f * k, WHITE, 0);
        text(g, tr("maniacmod.result.heading_sub"), x, height * 0.1f, 1.0f * k, GRAY, 0);
        text(g, tr("maniacmod.result.accruals"), x, height * 0.168f, 0.85f * k, GRAY, 0);

        int rowH = Math.round(height * 0.056f);
        int gap = Math.round(height * 0.012f);
        int y = Math.round(height * 0.21f);
        if (lines.isEmpty()) {
            text(g, tr("maniacmod.result.no_accruals"), x, y + rowH / 2f - 4f * 0.9f * k, 0.9f * k, GRAY, 0);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            MatchRewardPacket.Line l = lines.get(i);
            float p = ease(t, ROW_START_MS + i * ROW_STEP_MS, 380);
            int sx = Math.round((1f - p) * -26f);
            int ry = y + i * (rowH + gap);
            if (p > 0.02f) {
                g.fill(x + sx, ry, x + sx + w, ry + rowH, BloodFx.a(0xFF0D0E12, 0.82f * p));
                g.fill(x + sx, ry + 1, x + sx + 1, ry + rowH - 1, BloodFx.a(LINE, p));
                g.fill(x + sx + w - 1, ry + 1, x + sx + w, ry + rowH - 1, BloodFx.a(LINE, p));
                g.fill(x + sx, ry, x + sx + 3, ry + rowH, BloodFx.a(GOLD, p));
                float ty = ry + rowH / 2f - 4f * 1.1f * k;
                text(g, tr("maniacmod.reward." + l.key()), x + sx + 12, ty, 1.1f * k, BloodFx.a(WHITE, p), 0);
                text(g, "+" + l.xp(), x + sx + w - 12, ty, 1.1f * k, BloodFx.a(GRAY, p), 2);
            }
        }
    }

    // ── Права панель: XP і рівень ────────────────────────────────────────

    private void drawRight(GuiGraphics g, int x, int w, float k, long t, int rows, MatchRewardPacket reward) {
        float p = ease(t, 250, 500);
        int top = Math.round(height * 0.21f);
        int bottom = Math.round(height * 0.82f);
        int slide = Math.round((1f - p) * 24f);
        g.pose().pushPose();
        g.pose().translate(slide, 0, 0);
        g.fill(x, top, x + w, bottom, BloodFx.a(0xFF0A0B0E, 0.86f * p));
        g.fill(x, top, x + w, top + 1, BloodFx.a(LINE, p));
        g.fill(x, bottom - 1, x + w, bottom, BloodFx.a(LINE, p));
        g.fill(x, top, x + 1, bottom, BloodFx.a(LINE, p));
        g.fill(x + w - 1, top, x + w, bottom, BloodFx.a(LINE, p));

        int pad = Math.round(w * 0.07f);
        long xpStart = ROW_START_MS + rows * ROW_STEP_MS;
        int gained = reward == null ? 0 : reward.total();
        long before = reward == null ? 0L : reward.xpBefore();
        long after = reward == null ? 0L : reward.xpAfter();

        text(g, tr("maniacmod.result.xp_for_match"), x + pad, top + height * 0.027f, 0.85f * k, GRAY, 0);
        float countT = UiAnim.clamp01((t - xpStart) / (float) XP_COUNT_MS);
        int shown = Math.round(gained * UiAnim.easeOutCubic(countT));
        text(g, "+" + shown, x + pad, top + height * 0.075f, 3.0f * k, GOLD, 0);
        text(g, tr("maniacmod.result.xp_credited"), x + pad, top + height * 0.151f, 0.85f * k, GRAY, 0);
        g.fill(x + pad, top + Math.round(height * 0.19f), x + w - pad, top + Math.round(height * 0.19f) + 1, LINE);

        // Анімований загальний XP → рівень і смужка (переходи рівнів проходять самі).
        float barT = UiAnim.clamp01((t - xpStart - XP_COUNT_MS) / (float) BAR_MS);
        long cur = before + Math.round((after - before) * UiAnim.easeInOutCubic(barT));
        int level = XpCurve.levelOf(cur);
        int into = XpCurve.intoLevel(cur);
        int need = XpCurve.needed(level);

        text(g, tr("maniacmod.result.level_progress"), x + pad, top + height * 0.228f, 0.85f * k, GRAY, 0);
        text(g, tr("maniacmod.result.level") + " " + level, x + pad, top + height * 0.27f, 1.5f * k, WHITE, 0);

        int barX = x + pad;
        int barW = w - 2 * pad;
        int barY = top + Math.round(height * 0.33f);
        int barH = Math.max(4, Math.round(height * 0.016f));
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF1B1D22);
        int fill = Math.round(barW * (into / (float) need));
        g.fill(barX, barY, barX + fill, barY + barH, GOLD);
        g.fill(barX, barY, barX + fill, barY + 1, 0x55FFFFFF);
        text(g, into + " / " + need + " XP", x + pad, barY + barH + height * 0.022f, 0.85f * k, GRAY, 0);

        // Спалах «новий рівень», коли рівень виріс.
        if (barT > 0f && XpCurve.levelOf(after) > XpCurve.levelOf(before) && level > XpCurve.levelOf(before)) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 160.0);
            text(g, tr("maniacmod.result.level_up"), x + w - pad, top + height * 0.27f, 0.85f * k,
                BloodFx.a(GOLD, pulse), 2);
        }
        text(g, tr("maniacmod.result.xp_saved"), x + pad, bottom - height * 0.075f, 0.85f * k, GOLD_DIM, 0);
        g.pose().popPose();
    }


    // ── Підвал: примітка й кнопки ────────────────────────────────────────

    private void drawFooter(GuiGraphics g, int cx, int cw, float k, long t, int rows, float open,
                            int mouseX, int mouseY) {
        text(g, tr("maniacmod.result.limits_note"), cx, height * 0.855f, 0.85f * k, GRAY, 0);

        int bh = Math.round(height * 0.052f);
        int by = height - bh - Math.round(height * 0.012f);

        statsX = cx;
        statsW = Math.max(Math.round(width * 0.15f), 70);
        statsY = by;
        statsH = bh;
        boolean done = t >= animLength(rows);
        String skipLabel = tr(done ? "maniacmod.result.close" : "maniacmod.result.skip");
        skipW = Math.max(Math.round(width * 0.18f), 90);
        skipX = width - skipW - Math.round(width * 0.006f);
        skipY = by;
        skipH = bh;

        button(g, statsX, statsY, statsW, statsH, tr("maniacmod.result.statistics"), k, open, mouseX, mouseY);
        button(g, skipX, skipY, skipW, skipH, skipLabel, k, open, mouseX, mouseY);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, float k, float a,
                        int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, BloodFx.a(hover ? 0xFF16171C : 0xFF0C0D10, 0.92f * a));
        int border = hover ? GOLD : LINE;
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
        text(g, label, x + w / 2f, y + h / 2f - 4f * 1.0f * k, 1.0f * k, hover ? GOLD : WHITE, 1);
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (inside(mx, my, statsX, statsY, statsW, statsH)) {
                UiAnim.playClick();
                Minecraft.getInstance().setScreen(new MatchStatsScreen(this, summary, summary != null ? summary.survivorsWon() : outcomeSurvivorsWon));
                return true;
            }
            if (inside(mx, my, skipX, skipY, skipW, skipH)) {
                UiAnim.playClick();
                int rows = reward == null ? 0 : reward.lines().size();
                if (elapsed(System.currentTimeMillis()) >= animLength(rows)) {
                    onClose();
                } else {
                    skippedBonusMs += 60_000L;   // усе завершується одразу
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Текст зі масштабом; align: 0 — зліва від x, 1 — по центру, 2 — справа. */
    private void text(GuiGraphics g, String s, float x, float y, float scale, int color, int align) {
        if (((color >>> 24) & 0xFF) < 8) return;
        int w = font.width(s);
        float ox = align == 1 ? -w / 2f : align == 2 ? -w : 0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, Math.round(ox), 0, color, false);
        g.pose().popPose();
    }
}

package com.log_to_kot.maniacmod.client.screen.statistics;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.RankFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Підсумки матчу — ОДИН екран із трьома вкладками (замінює {@code MatchResultScreen} і
 * {@code MatchStatsScreen}). Стиль — ESC-меню та tab-екран: кутові дужки, заголовок капсом із
 * роздільником, прямокутні бари; усі кольори — зі спільних тем ({@link ManiacUiTheme},
 * {@link ManiacMenuTheme}), числових констант кольору в екрані нема.
 *
 * <pre>
 *  ┌ ПІДСУМКИ МАТЧУ ───────────────────────┐   ┌ ДОСВІД ЗА МАТЧ ┐ ← край екрана
 *  │ [Огляд] [Мій внесок] [Учасники]       │   │ +6 XP   ▰ ранг │
 *  │ …вміст вкладки…                       │   └────────────────┘
 *  └───────────────────────────────────────┘   ┌ Перемога   +2 ┐ ← XP-плашки
 *                          [Пропустити]        └───────────────┘
 * </pre>
 *
 * <h3>Вкладки</h3>
 * <ol>
 *   <li><b>Огляд</b> — результат, роль, час, генератори N з M, скільки втекло.</li>
 *   <li><b>Мій внесок</b> — рядки XP, рівень + ранг + смужка, свої лічильники.</li>
 *   <li><b>Учасники</b> — таблиця з головами ({@link PlayerFaceRenderer}), як у tab-екрані.</li>
 * </ol>
 * Перемикання — мишею або ←/→.
 *
 * <h3>XP на краю екрана</h3>
 * Справа від самого відкриття: картка «ДОСВІД ЗА МАТЧ» (сума, що росте з кожним нарахуванням,
 * ранг, рівень і смужка) і під нею XP-плашки, що по черзі в'їжджають із краю й виїжджають.
 * Вони видимі на будь-якій вкладці. «Новий рівень/ранг» лишається внизу колонки.
 * Ранг — {@link Ranks}, похідний від рівня; профілю поки нема, коли буде — читатиме звідти ж.
 *
 * <h3>Акценти</h3>
 * Один акцент результату: світле срібло (перемога виживших) або приглушений червоний
 * ({@code BAR_FILL_DANGER}, перемога маніяка) — лише тонка лінія під заголовком.
 *
 * <h3>Анімація</h3>
 * Плавна поява панелей, послідовний вихід рядків XP, рахунок цифр і смужка 1–1,5 с.
 * Без глітчу, тряски, миготіння й циклічних ефектів. «Пропустити» домотує все до кінця й стає
 * «Закрити». Фон прозорий: за панелями видно freecam {@code STATS_CAMERA}.
 */
public final class MatchSummaryScreen extends Screen {

    private enum Tab {
        OVERVIEW("overview"), CONTRIBUTION("contribution"), PLAYERS("players");
        final String key;
        Tab(String key) { this.key = key; }
    }

    private static final Tab[] TABS = Tab.values();

    private static final long ROW_START_MS = 350L;
    private static final long ROW_STEP_MS = 200L;
    private static final long ROW_IN_MS = 320L;
    private static final long BAR_GAP_MS = 250L;
    private static final long BAR_MS = 1300L;
    private static final long COUNT_MS = 1200L;
    private static final long END_PAD_MS = 300L;

    private static final long TOAST_IN_MS = 320L;
    private static final long TOAST_HOLD_MS = 1300L;
    private static final long TOAST_OUT_MS = 320L;
    /** Слотів стільки, щоб плашки i та i+SLOTS ніколи не жили одночасно (SLOTS·STEP > життя плашки). */
    private static final int TOAST_SLOTS = 10;

    private long openedAtMs;
    /** Скільки мс анімації «підмотано» кнопкою «Пропустити». */
    private long skipBonusMs;

    private Tab tab = Tab.OVERVIEW;
    /** Коли вкладку вперше показали — від цього рахується її власна анімація. */
    private final long[] shownAt = new long[TABS.length];
    private final float[] tabHover = new float[TABS.length];
    private float skipHover;
    private final long[] lastNs = {0L};

    /** Час (на глобальному годиннику), коли смужка вперше перейшла на новий рівень; -1 — ще ні. */
    private long levelUpAtMs = -1L;

    // Прямокутники для кліків: x, y, w, h (оновлюються щокадру).
    private final int[][] tabRect = new int[TABS.length][4];
    private final int[] skipRect = new int[4];

    // Поточні множники масштабу й прозорості (виставляються на початку кожної ділянки).
    private float k = 1f;
    private float fa = 1f;

    /**
     * Знімок даних на момент відкриття: екран живе довше за матч (ClientMatchState на
     * RESET/LOBBY обнуляється), тож читати стан щокадру не можна.
     */
    private final MatchSummaryPacket summary = ClientMatchState.summary();
    private final MatchRewardPacket reward = ClientMatchState.reward();
    private final boolean outcomeSurvivorsWon =
        ClientMatchState.outcomeKnown() && ClientMatchState.outcomeSurvivorsWon();
    private final int levelBefore;
    private final Ranks.Rank rankBefore;
    /** Роль гравця в цьому матчі — від неї залежать назви рангів (прогрес спільний). */
    private final Ranks.Role role;

    private Ranks.Role roleOfMe() {
        Entry me = mine(summary);
        return me != null && me.result() == MatchSummaryPacket.Result.MANIAC ? Ranks.Role.MANIAC : Ranks.Role.SURVIVOR;
    }

    public MatchSummaryScreen() {
        super(Component.translatable("maniacmod.result.title"));
        long before = reward == null ? 0L : reward.xpBefore();
        this.levelBefore = XpCurve.levelOf(before);
        this.role = roleOfMe();
        this.rankBefore = Ranks.ofLevel(role, levelBefore);
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

    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static Entry mine(MatchSummaryPacket s) {
        if (s == null) return null;
        Minecraft mc = Minecraft.getInstance();
        UUID me = mc.player == null ? null : mc.player.getUUID();
        for (Entry e : s.entries()) if (e.id().equals(me)) return e;
        return null;
    }

    private static ResourceLocation skinOf(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        var info = conn == null ? null : conn.getPlayerInfo(id);
        if (info != null) return info.getSkinLocation();
        return DefaultPlayerSkin.getDefaultSkin(id);
    }

    private int rows() {
        return reward == null ? 0 : reward.lines().size();
    }

    private long xpStartMs() {
        return ROW_START_MS + rows() * ROW_STEP_MS;
    }

    private long animLength() {
        return xpStartMs() + BAR_GAP_MS + BAR_MS + END_PAD_MS;
    }

    private long tabLength(Tab t) {
        return switch (t) {
            case OVERVIEW -> 1700L;
            case CONTRIBUTION -> animLength();
            case PLAYERS -> 400L + (summary == null ? 0 : summary.entries().size()) * 70L + END_PAD_MS;
        };
    }

    private long globalClock(long now) { return now - openedAtMs + skipBonusMs; }

    private long tabClock(long now) {
        long shown = shownAt[tab.ordinal()];
        return now - (shown == 0L ? now : shown) + skipBonusMs;
    }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    /** Сума XP, що «набралась» на годиннику {@code t}: кожен рядок додається плавно, коли до нього дійшла черга. */
    private int xpShown(long t) {
        if (reward == null) return 0;
        float sum = 0f;
        List<MatchRewardPacket.Line> lines = reward.lines();
        for (int i = 0; i < lines.size(); i++) {
            sum += lines.get(i).xp() * ease(t, ROW_START_MS + i * ROW_STEP_MS, 450L);
        }
        return Math.round(sum);
    }

    /** Загальний XP, що добігає від «до» до «після» на годиннику {@code t}. */
    private long xpTotalAt(long t) {
        if (reward == null) return 0L;
        float p = UiAnim.easeInOutCubic(UiAnim.clamp01((t - xpStartMs() - BAR_GAP_MS) / (float) BAR_MS));
        return reward.xpBefore() + Math.round((reward.xpAfter() - reward.xpBefore()) * p);
    }

    // ── Колір ────────────────────────────────────────────────────────────

    /** Колір із урахуванням поточної прозорості ділянки. */
    private int c(int argb, float mul) {
        return GeneratorFuelUiTheme.alpha(argb, mul * fa);
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (openedAtMs == 0L) {
            openedAtMs = now;
            shownAt[tab.ordinal()] = now;
            UiAnim.playAppear();
        }
        float dt = UiAnim.frameDt(lastNs);
        long gt = globalClock(now);
        k = Math.max(0.75f, Math.min(1.6f, height / 540f));
        float open = ease(gt, 0, 450);

        // Притемнення світу (freecam лишається видно) + м'яка віньєтка знизу.
        g.fill(0, 0, width, height, GeneratorFuelUiTheme.alpha(ManiacMenuTheme.SHADE, 0.38f * open));
        g.fillGradient(0, height * 3 / 4, width, height, 0x00000000,
            GeneratorFuelUiTheme.alpha(ManiacMenuTheme.SHADE, 0.55f * open));

        int m = Math.round(10 * k);
        int dockW = reward == null ? 0 : Math.min(Math.round(176 * k), Math.round(width * 0.28f));
        int areaR = width - (dockW == 0 ? 0 : dockW + m) - m;
        int pw = Math.min(Math.round(600 * k), areaR - m);
        int px = m + (areaR - m - pw) / 2;
        int btnH = Math.round(20 * k);
        int py = m;
        int ph = height - 2 * m - btnH - Math.round(6 * k);

        drawPanel(g, px, py, pw, ph, open, gt, now, mouseX, mouseY, dt);
        if (dockW > 0) drawDock(g, width - dockW - m, m, dockW, m, gt, open);
        drawSkip(g, px + pw, py + ph + Math.round(6 * k), btnH, open, now, mouseX, mouseY, dt);

        super.render(g, mouseX, mouseY, partialTick);
    }

    // ── Головна панель ───────────────────────────────────────────────────

    private void drawPanel(GuiGraphics g, int px, int py, int pw, int ph, float open, long gt, long now,
                           int mouseX, int mouseY, float dt) {
        fa = open;
        g.pose().pushPose();
        g.pose().translate(0f, (1f - open) * 14f * k, 0f);

        g.fill(px, py, px + pw, py + ph, c(ManiacUiTheme.PANEL_BG, 1f));
        ManiacUiTheme.border1px(g, px, py, pw, ph, c(ManiacUiTheme.BORDER, 1f));
        UiFx.brackets(g, px, py, pw, ph, c(ManiacUiTheme.CORNER_ACCENT, 0.9f), Math.round(10 * k), 0f, 0);

        int pad = Math.round(14 * k);
        int ix = px + pad;
        int iw = pw - 2 * pad;

        // Заголовок: надрядок, назва капсом, роздільник.
        text(g, tr("maniacmod.result.finished"), ix, py + pad, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, tr("maniacmod.result.heading"), ix, py + pad + 11 * k, 1.5f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        int divY = py + pad + Math.round(32 * k);
        ManiacUiTheme.divider(g, ix, divY, iw);

        // Вкладки.
        int tabH = Math.round(20 * k);
        int tabGap = Math.round(6 * k);
        int tabW = Math.min(Math.round(150 * k), (iw - 2 * tabGap) / 3);
        int tabY = divY + Math.round(8 * k);
        for (int i = 0; i < TABS.length; i++) {
            int tx = ix + i * (tabW + tabGap);
            tabRect[i][0] = tx;
            tabRect[i][1] = tabY;
            tabRect[i][2] = tabW;
            tabRect[i][3] = tabH;
            boolean hover = inside(mouseX, mouseY, tx, tabY, tabW, tabH);
            tabHover[i] = UiAnim.approach(tabHover[i], hover ? 1f : 0f, 16f, dt);
            drawTab(g, tx, tabY, tabW, tabH, tr("maniacmod.summary.tab." + TABS[i].key), i == tab.ordinal(), tabHover[i]);
        }

        // Вміст вкладки: один рух — легкий підйом із проявою.
        int cy = tabY + tabH + Math.round(12 * k);
        int ch = py + ph - pad - cy;
        long tt = tabClock(now);
        float in = ease(tt, 0, 300);
        fa = open * in;
        g.pose().pushPose();
        g.pose().translate(0f, (1f - in) * 8f * k, 0f);
        Entry me = mine(summary);
        boolean survivorsWon = summary != null ? summary.survivorsWon() : outcomeSurvivorsWon;
        boolean maniac = me != null && me.result() == MatchSummaryPacket.Result.MANIAC;
        boolean won = me == null ? survivorsWon : (maniac != survivorsWon);
        switch (tab) {
            case OVERVIEW -> drawOverview(g, ix, cy, iw, ch, tt, me, survivorsWon, won, maniac);
            case CONTRIBUTION -> drawContribution(g, ix, cy, iw, ch, tt, me);
            case PLAYERS -> drawPlayers(g, ix, cy, iw, ch, tt, me);
        }
        g.pose().popPose();
        g.pose().popPose();
        fa = 1f;
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, float hv) {
        int slant = 4;
        int sw = w - slant;
        ManiacMenuTheme.slantFill(g, x, y, sw, h, slant,
            c(active ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, active ? 1f : 0.85f));
        if (active) g.fill(x, y + h - 2, x + sw, y + h, c(ManiacUiTheme.BAR_FILL_NEUTRAL, 1f));
        int border = GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT,
            Math.max(hv, active ? 0.5f : 0f));
        ManiacMenuTheme.slantBorder(g, x, y, sw, h, slant, c(border, 1f));
        int tc = active ? ManiacUiTheme.TEXT_ACCENT
            : GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_BODY, hv);
        text(g, label.toUpperCase(Locale.ROOT), x + slant / 2f + sw / 2f, y + h / 2f - 4f * 0.9f * k,
            0.9f * k, c(tc, 1f), 1);
    }

    private void drawSkip(GuiGraphics g, int right, int y, int h, float open, long now, int mx, int my, float dt) {
        long gt = globalClock(now);
        boolean done = gt >= animLength() && tabClock(now) >= tabLength(tab);
        String label = tr(done ? "maniacmod.result.close" : "maniacmod.result.skip").toUpperCase(Locale.ROOT);
        int w = Math.max(Math.round(150 * k), font.width(label) + Math.round(30 * k));
        int x = right - w;
        skipRect[0] = x; skipRect[1] = y; skipRect[2] = w; skipRect[3] = h;
        boolean hover = inside(mx, my, x, y, w, h);
        skipHover = UiAnim.approach(skipHover, hover ? 1f : 0f, 16f, dt);

        fa = open;
        int slant = 5;
        int sw = w - slant;
        ManiacMenuTheme.slantFill(g, x, y, sw, h, slant, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        int blade = Math.round(sw * UiAnim.easeOutCubic(skipHover));
        ManiacMenuTheme.slantFill(g, x, y, blade, h, slant, c(ManiacUiTheme.MENU_ACCENT_BROWN, 1f));
        ManiacMenuTheme.slantBorder(g, x, y, sw, h, slant, c(
            GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, skipHover), 1f));
        ManiacMenuTheme.slantFill(g, x, y, 3, h, slant, c(
            GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.TEXT_ACCENT, skipHover), 1f));
        text(g, label, x + slant / 2f + sw / 2f, y + h / 2f - 4f * k, k,
            c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, skipHover), 1f), 1);

        text(g, tr("maniacmod.summary.hint"), x - Math.round(14 * k), y + h / 2f - 4f * 0.85f * k, 0.85f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 2);
        fa = 1f;
    }

    // ── Вкладка 1: Огляд ─────────────────────────────────────────────────

    private void drawOverview(GuiGraphics g, int x, int y, int w, int h, long tt, Entry me,
                              boolean survivorsWon, boolean won, boolean maniac) {
        // Блок результату: голова + заголовок + роль.
        int face = Math.round(46 * k);
        drawFace(g, x, y, face, me != null ? me.id() : localId());
        int tx = x + face + Math.round(14 * k);

        String headline = tr(survivorsWon ? "maniacmod.result.big_survivors" : "maniacmod.result.big_maniac")
            .toUpperCase(Locale.ROOT);
        float hs = 1.7f * k;
        text(g, headline, tx, y + 2 * k, hs, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        // Єдиний акцент екрана: тонка лінія під заголовком.
        int accent = survivorsWon ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.BAR_FILL_DANGER;
        int lineW = Math.round(Math.min(font.width(headline) * hs, w - face - 14 * k) * ease(tt, 150, 600));
        g.fill(tx, y + Math.round(2 * k + 8 * hs + 5 * k), tx + lineW, y + Math.round(2 * k + 8 * hs + 5 * k) + 2, c(accent, 1f));

        String role = me == null ? tr(survivorsWon ? "maniacmod.result.big_survivors" : "maniacmod.result.big_maniac")
            : tr(maniac ? "maniacmod.result.role.maniac" : "maniacmod.result.role.survivor");
        int roleY = y + Math.round(2 * k + 8 * hs + 16 * k);
        text(g, tr("maniacmod.summary.your_role").toUpperCase(Locale.ROOT), tx, roleY, 0.8f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, role + "  ·  " + tr(won ? "maniacmod.result.win" : "maniacmod.result.loss"),
            tx, roleY + 10 * k, 1.05f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);

        // Картки: час, генератори, втекло.
        int escaped = 0, total = 0;
        if (summary != null) {
            for (Entry e : summary.entries()) {
                if (e.result() == MatchSummaryPacket.Result.MANIAC) continue;
                total++;
                if (e.result() == MatchSummaryPacket.Result.ESCAPED) escaped++;
            }
        } else if (ClientMatchState.outcomeKnown()) {
            escaped = ClientMatchState.outcomeEscaped();
            total = ClientMatchState.outcomeTotal();
        }
        int gap = Math.round(10 * k);
        int cardW = (w - 2 * gap) / 3;
        int cardH = Math.round(70 * k);
        int cardY = y + face + Math.round(20 * k);
        float count = ease(tt, 300, COUNT_MS);

        String timeVal = summary == null ? "—" : duration(Math.round(summary.playedSeconds() * count));
        card(g, x, cardY, cardW, cardH, ease(tt, 200, 450), tr("maniacmod.result.card.time"), timeVal, -1f);

        int gen = summary == null ? 0 : summary.generatorsDone();
        int genReq = summary == null ? 0 : summary.generatorsRequired();
        card(g, x + cardW + gap, cardY, cardW, cardH, ease(tt, 300, 450), tr("maniacmod.result.card.generators"),
            summary == null ? "—" : Math.round(gen * count) + " / " + genReq,
            genReq <= 0 ? 0f : count * Math.min(1f, gen / (float) genReq));

        card(g, x + 2 * (cardW + gap), cardY, cardW, cardH, ease(tt, 400, 450), tr("maniacmod.result.card.escaped"),
            total == 0 ? "—" : Math.round(escaped * count) + " / " + total,
            total <= 0 ? 0f : count * Math.min(1f, escaped / (float) total));

        text(g, tr("maniacmod.result.saved_history"), x, y + h - 9 * 0.85f * k, 0.85f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 0);
    }

    /** Картка-показник: підпис, велике число й (за потреби) смужка. {@code bar < 0} — без смужки. */
    private void card(GuiGraphics g, int x, int y, int w, int h, float in, String label, String value, float bar) {
        float save = fa;
        fa = save * in;
        int slide = Math.round((1f - in) * 10f * k);
        g.pose().pushPose();
        g.pose().translate(0f, slide, 0f);
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));
        int pad = Math.round(9 * k);
        text(g, label.toUpperCase(Locale.ROOT), x + pad, y + pad, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, value, x + pad, y + pad + 12 * k, 1.8f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        if (bar >= 0f) bar(g, x + pad, y + h - pad - Math.round(6 * k), w - 2 * pad, Math.round(6 * k), bar,
            ManiacUiTheme.BAR_FILL_NEUTRAL);
        g.pose().popPose();
        fa = save;
    }

    // ── Вкладка 2: Мій внесок ────────────────────────────────────────────

    private void drawContribution(GuiGraphics g, int x, int y, int w, int h, long tt, Entry me) {
        int gap = Math.round(16 * k);
        int lw = Math.round((w - gap) * 0.50f);
        int rx = x + lw + gap;
        int rw = w - lw - gap;

        // ── Ліворуч: нарахування XP ──
        text(g, tr("maniacmod.result.accruals"), x, y, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        ManiacUiTheme.divider(g, x, y + Math.round(12 * k), lw);
        int listY = y + Math.round(20 * k);
        int totalH = Math.round(34 * k);
        List<MatchRewardPacket.Line> lines = reward == null ? List.<MatchRewardPacket.Line>of() : reward.lines();
        if (lines.isEmpty()) {
            text(g, tr("maniacmod.result.no_accruals"), x, listY + 2 * k, 0.95f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        } else {
            int avail = y + h - totalH - listY;
            int rowGap = Math.max(2, Math.round(3 * k));
            int rowH = Math.max(Math.round(13 * k), Math.min(Math.round(22 * k), avail / lines.size() - rowGap));
            for (int i = 0; i < lines.size(); i++) {
                MatchRewardPacket.Line l = lines.get(i);
                float p = ease(tt, ROW_START_MS + i * ROW_STEP_MS, ROW_IN_MS);
                if (p < 0.02f) continue;
                int ry = listY + i * (rowH + rowGap);
                int sx = Math.round((1f - p) * -12f * k);
                float save = fa;
                fa = save * p;
                g.fill(x + sx, ry, x + sx + lw, ry + rowH, c(ManiacUiTheme.SLOT_FILL, 0.9f));
                ManiacUiTheme.border1px(g, x + sx, ry, lw, rowH, c(ManiacUiTheme.BORDER, 1f));
                g.fill(x + sx, ry, x + sx + 2, ry + rowH, c(ManiacUiTheme.BAR_FILL_NEUTRAL, 1f));
                float ty = ry + rowH / 2f - 4f * k;
                text(g, tr("maniacmod.reward." + l.key()), x + sx + 10 * k, ty, k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
                text(g, "+" + l.xp(), x + sx + lw - 8 * k, ty, k, c(ManiacUiTheme.TEXT_TITLE, 1f), 2);
                fa = save;
            }
            // Підсумок.
            int ty = y + h - totalH + Math.round(4 * k);
            ManiacUiTheme.divider(g, x, ty - Math.round(4 * k), lw);
            text(g, tr("maniacmod.summary.xp_total").toUpperCase(Locale.ROOT), x, ty + 6 * k, 0.8f * k,
                c(ManiacUiTheme.TEXT_DIM, 1f), 0);
            text(g, "+" + xpShown(tt) + " XP", x + lw, ty + 2 * k, 1.6f * k, c(ManiacUiTheme.TEXT_ACCENT, 1f), 2);
        }

        // ── Праворуч: рівень і ранг ──
        long total = xpTotalAt(tt);
        int level = XpCurve.levelOf(total);
        int into = XpCurve.intoLevel(total);
        int need = XpCurve.needed(level);
        Ranks.Rank rank = Ranks.ofLevel(role, level);

        int pad = Math.round(10 * k);
        int er = Math.round(15 * k);
        int emblemH = RankFx.height(er);
        int cardH = pad + emblemH + Math.round(10 * k) + Math.round(6 * k) + Math.round(6 * k) + Math.round(8 * k)
            + Math.round(12 * k) + pad;
        float cardIn = ease(tt, 150, 450);
        float save = fa;
        fa = save * cardIn;
        g.fill(rx, y, rx + rw, y + cardH, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacUiTheme.border1px(g, rx, y, rw, cardH, c(ManiacUiTheme.BORDER, 1f));
        RankFx.draw(g, rx + pad + er + 2, y + pad + er, Math.max(4, Math.round(er * UiAnim.easeOutBack(cardIn, 0.6f))),
            rank, fa);
        int nx = rx + pad + er * 2 + Math.round(14 * k);
        String rankName = (tr(rank.langKey()) + (rank.top() ? "" : " " + rank.divisionRoman())).toUpperCase(Locale.ROOT);
        text(g, tr("maniacmod.summary.rank").toUpperCase(Locale.ROOT), nx, y + pad, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, rankName, nx, y + pad + 11 * k, 1.25f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        text(g, tr("maniacmod.result.level") + " " + level, nx, y + pad + 25 * k, 0.95f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
        if (level > levelBefore) {
            float lu = ease(tt, xpStartMs() + BAR_GAP_MS + BAR_MS / 2, 450);
            text(g, tr("maniacmod.result.level_up"), rx + rw - pad, y + pad, 0.8f * k,
                c(ManiacUiTheme.TEXT_ACCENT, lu), 2);
        }
        int barY = y + pad + emblemH + Math.round(10 * k);
        bar(g, rx + pad, barY, rw - 2 * pad, Math.round(6 * k), into / (float) need, ManiacUiTheme.BAR_FILL_NEUTRAL);
        text(g, into + " / " + need + " XP", rx + pad, barY + 10 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        String nk = Ranks.nextTierKey(role, level);
        if (nk != null) {
            text(g, Component.translatable("maniacmod.summary.next_rank", Component.translatable(nk),
                    Ranks.nextTierLevel(level) - level).getString(),
                rx + rw - pad, barY + 10 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 2);
        }
        fa = save;

        // ── Праворуч: власні лічильники ──
        if (me != null) {
            int sy = y + cardH + Math.round(14 * k);
            text(g, tr("maniacmod.result.mine").toUpperCase(Locale.ROOT), rx, sy, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
            ManiacUiTheme.divider(g, rx, sy + Math.round(12 * k), rw);
            String[] keys = {"hits_dealt", "hits_taken", "downs", "hooks", "rescues", "repair"};
            int[] vals = {me.hitsDealt(), me.hitsTaken(), me.downs(), me.hooks(), me.rescues(), me.repairSeconds()};
            float cnt = ease(tt, 400, COUNT_MS);
            int rowH = Math.max(Math.round(12 * k), Math.min(Math.round(16 * k), (y + h - sy - Math.round(20 * k)) / keys.length));
            for (int i = 0; i < keys.length; i++) {
                int ly = sy + Math.round(18 * k) + i * rowH;
                float p = ease(tt, 300 + i * 60L, 300);
                float sv = fa;
                fa = sv * p;
                String val = i == 5 ? clock(Math.round(vals[i] * cnt)) : String.valueOf(Math.round(vals[i] * cnt));
                text(g, tr("maniacmod.result.stat." + keys[i]), rx, ly, 0.95f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
                text(g, val, rx + rw, ly, 0.95f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 2);
                fa = sv;
            }
        }
    }

    // ── Вкладка 3: Учасники ──────────────────────────────────────────────

    private void drawPlayers(GuiGraphics g, int x, int y, int w, int h, long tt, Entry me) {
        if (summary == null) {
            text(g, "—", x, y, k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
            return;
        }
        int head = Math.round(16 * k);
        int colTime = x + w - Math.round(10 * k);
        int colResult = colTime - Math.round(86 * k);
        int nameX = x + Math.round(10 * k) + head + Math.round(10 * k);

        text(g, tr("maniacmod.summary.col.player").toUpperCase(Locale.ROOT), nameX, y, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, tr("maniacmod.summary.col.result").toUpperCase(Locale.ROOT), colResult, y, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 2);
        text(g, tr("maniacmod.summary.col.time").toUpperCase(Locale.ROOT), colTime, y, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 2);
        ManiacUiTheme.divider(g, x, y + Math.round(12 * k), w);

        List<Entry> sorted = new ArrayList<>(summary.entries());
        sorted.sort((a, b) -> Integer.compare(order(a), order(b)));
        int top = y + Math.round(16 * k);
        int avail = y + h - top;
        int rowH = Math.max(Math.round(14 * k), Math.min(Math.round(24 * k), avail / Math.max(1, sorted.size())));
        UUID meId = me == null ? null : me.id();

        for (int i = 0; i < sorted.size(); i++) {
            int ry = top + i * rowH;
            if (ry + rowH > y + h + 1) break;
            Entry e = sorted.get(i);
            float p = ease(tt, 150 + i * 70L, 300);
            if (p < 0.02f) continue;
            float save = fa;
            fa = save * p;
            int sx = Math.round((1f - p) * -12f * k);
            boolean mineRow = e.id().equals(meId);

            g.fill(x + sx, ry, x + sx + w, ry + rowH - 1,
                c(i % 2 == 0 ? ManiacUiTheme.SLOT_FILL : ManiacUiTheme.MENU_PANEL_DARK, 0.92f));
            g.fill(x + sx, ry + rowH - 1, x + sx + w, ry + rowH, c(ManiacUiTheme.BORDER, 0.6f));
            if (mineRow) {
                g.fill(x + sx, ry, x + sx + w, ry + rowH - 1, c(ManiacUiTheme.BAR_FILL_NEUTRAL, 0.09f));
                g.fill(x + sx, ry, x + sx + 2, ry + rowH - 1, c(ManiacUiTheme.TEXT_ACCENT, 1f));
            }

            int hs = Math.min(head, rowH - 4);
            int hy = ry + (rowH - 1 - hs) / 2;
            drawFace(g, x + sx + Math.round(8 * k), hy, hs, e.id());
            float ty = ry + (rowH - 1) / 2f - 4f * k;
            text(g, e.name(), nameX + sx, ty, k, c(mineRow ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_BODY, 1f), 0);

            int rc = switch (e.result()) {
                case ESCAPED -> ManiacUiTheme.TEXT_ACCENT;
                case MANIAC -> ManiacUiTheme.TEXT_BODY;
                case ELIMINATED, CAUGHT -> ManiacUiTheme.TEXT_DIM;
            };
            text(g, tr("maniacmod.result.tag." + e.result().name().toLowerCase(Locale.ROOT)),
                colResult + sx, ty, k, c(rc, 1f), 2);
            String tm = e.result() == MatchSummaryPacket.Result.MANIAC ? "—" : clock(e.survivedSeconds());
            text(g, tm, colTime + sx, ty, k, c(ManiacUiTheme.TEXT_BODY, 1f), 2);
            fa = save;
        }
    }

    private static int order(Entry e) {
        return switch (e.result()) {
            case MANIAC -> 0;
            case ESCAPED -> 1;
            case CAUGHT -> 2;
            case ELIMINATED -> 3;
        };
    }

    // ── Край екрана: XP, ранг, плашки ────────────────────────────────────

    private void drawDock(GuiGraphics g, int x, int y, int w, int m, long gt, float open) {
        float in = ease(gt, 150, 500);
        float slide = (1f - in) * (w + m + 4);
        int pad = Math.round(10 * k);
        int er = Math.round(12 * k);
        int emblemH = RankFx.height(er);
        int barH = Math.round(6 * k);
        int o1 = pad;
        int o2 = pad + Math.round(11 * k);
        int o3 = o2 + Math.round(22 * k);
        int o4 = o3 + Math.round(8 * k);
        int o5 = o4 + emblemH + Math.round(6 * k);
        int o6 = o5 + barH + Math.round(4 * k);
        int h = o6 + Math.round(8 * k) + pad;

        long total = xpTotalAt(gt);
        int level = XpCurve.levelOf(total);
        int into = XpCurve.intoLevel(total);
        int need = XpCurve.needed(level);
        Ranks.Rank rank = Ranks.ofLevel(role, level);

        fa = in;
        g.pose().pushPose();
        g.pose().translate(slide, 0f, 0f);
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.PANEL_BG, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));
        UiFx.brackets(g, x, y, w, h, c(ManiacUiTheme.CORNER_ACCENT, 0.9f), Math.round(8 * k), 0f, 0);

        text(g, tr("maniacmod.result.xp_for_match"), x + pad, y + o1, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        String amount = "+" + xpShown(gt);
        float as = 2.2f * k;
        text(g, amount, x + pad, y + o2, as, c(ManiacUiTheme.TEXT_ACCENT, 1f), 0);
        text(g, "XP", x + pad + font.width(amount) * as + 4 * k, y + o2 + 8 * as - 8 * 0.9f * k, 0.9f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        ManiacUiTheme.divider(g, x + pad, y + o3, w - 2 * pad);

        RankFx.draw(g, x + pad + er + 2, y + o4 + er, er, rank, fa);
        int nx = x + pad + er * 2 + Math.round(12 * k);
        String rankName = (tr(rank.langKey()) + (rank.top() ? "" : " " + rank.divisionRoman())).toUpperCase(Locale.ROOT);
        text(g, rankName, nx, y + o4 + 3 * k, 1.0f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        text(g, tr("maniacmod.result.level") + " " + level, nx, y + o4 + 15 * k, 0.85f * k,
            c(ManiacUiTheme.TEXT_BODY, 1f), 0);
        bar(g, x + pad, y + o5, w - 2 * pad, barH, into / (float) need, ManiacUiTheme.BAR_FILL_NEUTRAL);
        text(g, into + " / " + need + " XP", x + pad, y + o6, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        g.pose().popPose();

        // XP-плашки: по одній на нарахування, в'їжджають із краю й виїжджають.
        if (reward != null) {
            int th = Math.round(20 * k);
            int tg = Math.round(3 * k);
            int toastTop = y + h + Math.round(6 * k);
            List<MatchRewardPacket.Line> lines = reward.lines();
            long life = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS;
            for (int i = 0; i < lines.size(); i++) {
                long age = gt - (ROW_START_MS + i * ROW_STEP_MS);
                if (age < 0L || age >= life) continue;
                float tin = UiAnim.easeOutCubic(age / (float) TOAST_IN_MS);
                float tout = age > TOAST_IN_MS + TOAST_HOLD_MS
                    ? UiAnim.clamp01((age - TOAST_IN_MS - TOAST_HOLD_MS) / (float) TOAST_OUT_MS) : 0f;
                tout *= tout;
                float off = (1f - tin) * (w + m + 4) + tout * (w + m + 4);
                int ty = toastTop + (i % TOAST_SLOTS) * (th + tg);
                fa = tin * (1f - tout);
                g.pose().pushPose();
                g.pose().translate(off, 0f, 0f);
                MatchRewardPacket.Line l = lines.get(i);
                g.fill(x, ty, x + w, ty + th, c(ManiacUiTheme.PANEL_BG, 1f));
                ManiacUiTheme.border1px(g, x, ty, w, th, c(ManiacUiTheme.BORDER, 1f));
                g.fill(x, ty, x + 2, ty + th, c(ManiacUiTheme.TEXT_ACCENT, 1f));
                float tty = ty + th / 2f - 4f * 0.95f * k;
                String label = font.plainSubstrByWidth(tr("maniacmod.reward." + l.key()),
                    Math.round((w - 56 * k) / (0.95f * k)));
                text(g, label, x + 9 * k, tty, 0.95f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
                text(g, "+" + l.xp() + " XP", x + w - 8 * k, tty, 0.95f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 2);
                g.pose().popPose();
            }
        }

        // «Новий рівень / ранг» — лишається внизу колонки, доки екран відкритий.
        if (level > levelBefore) {
            if (levelUpAtMs < 0L) {
                levelUpAtMs = gt;
                UiAnim.playReady();
            }
            float nin = ease(gt, levelUpAtMs, 450);
            int nh = Math.round(30 * k);
            int ng = Math.round(4 * k);
            int ny = height - m - nh;
            drawNotice(g, x, ny, w, nh, nin, m, tr("maniacmod.result.level_up"), String.valueOf(level), null);
            if (!rank.equals(rankBefore)) {
                String rn = (tr(rank.langKey()) + (rank.top() ? "" : " " + rank.divisionRoman()));
                drawNotice(g, x, ny - nh - ng, w, nh, nin, m, tr("maniacmod.summary.rank_up"), rn, rank);
            }
        }
        fa = 1f;
    }

    private void drawNotice(GuiGraphics g, int x, int y, int w, int h, float in, int m,
                            String label, String value, Ranks.Rank emblem) {
        fa = in;
        g.pose().pushPose();
        g.pose().translate((1f - in) * (w + m + 4), 0f, 0f);
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.PANEL_BG, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));
        g.fill(x, y, x + 2, y + h, c(ManiacUiTheme.TEXT_ACCENT, 1f));
        int tx = x + Math.round(10 * k);
        if (emblem != null) {
            int r = Math.max(4, Math.round(8 * k));
            RankFx.draw(g, tx + r, y + h / 2 - 2, r, emblem, fa);
            tx += r * 2 + Math.round(8 * k);
        }
        text(g, label.toUpperCase(Locale.ROOT), tx, y + 5 * k, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, value.toUpperCase(Locale.ROOT), tx, y + 5 * k + 9 * k, 1.2f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        g.pose().popPose();
    }

    // ── Примітиви ────────────────────────────────────────────────────────

    /** Прямокутний бар теми: темний фон, світла рамка, заливка з тонким верхнім відблиском (без анімованих бликів). */
    private void bar(GuiGraphics g, int x, int y, int w, int h, float fraction, int fill) {
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.BAR_BG, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BAR_FRAME, 0.55f));
        int f = Math.round((w - 2) * UiAnim.clamp01(fraction));
        if (f > 0) {
            g.fill(x + 1, y + 1, x + 1 + f, y + h - 1, c(fill, 1f));
            g.fill(x + 1, y + 1, x + 1 + f, y + 2, c(0x38FFFFFF, 1f));
        }
    }

    private void drawFace(GuiGraphics g, int x, int y, int size, UUID id) {
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, c(ManiacUiTheme.BORDER, 1f));
        g.fill(x, y, x + size, y + size, c(ManiacUiTheme.BAR_BG, 1f));
        if (id == null) return;
        try {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, UiAnim.clamp01(fa));
            PlayerFaceRenderer.draw(g, skinOf(id), x, y, size);
        } catch (RuntimeException ignored) {
            // голова — декор: збій скіна не має валити екран
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    private static UUID localId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getUUID();
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = 0; i < TABS.length; i++) {
                int[] r = tabRect[i];
                if (inside(mx, my, r[0], r[1], r[2], r[3])) {
                    selectTab(i);
                    return true;
                }
            }
            if (inside(mx, my, skipRect[0], skipRect[1], skipRect[2], skipRect[3])) {
                UiAnim.playClick();
                long now = System.currentTimeMillis();
                if (globalClock(now) >= animLength() && tabClock(now) >= tabLength(tab)) {
                    onClose();
                } else {
                    skipBonusMs += 60_000L;   // усе завершується одразу
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT) {
            selectTab(Math.max(0, tab.ordinal() - 1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            selectTab(Math.min(TABS.length - 1, tab.ordinal() + 1));
            return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private void selectTab(int index) {
        if (index == tab.ordinal()) return;
        tab = TABS[index];
        if (shownAt[index] == 0L) shownAt[index] = System.currentTimeMillis();
        UiAnim.playClick();
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
        if (((color >>> 24) & 0xFF) < 8 || s.isEmpty()) return;
        int w = font.width(s);
        float ox = align == 1 ? -w / 2f : align == 2 ? -w : 0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, Math.round(ox), 0, color, false);
        g.pose().popPose();
    }
}

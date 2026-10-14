package com.log_to_kot.maniacmod.client.screen.leaderboard;

import com.log_to_kot.maniacmod.client.ClientProfileState;
import com.log_to_kot.maniacmod.client.leaderboard.ClientLeaderboardState;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.RankFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardCategory.Group;
import com.log_to_kot.maniacmod.leaderboard.LeaderboardRow;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Лідерборди в стилі лаунчера: ліворуч — таблиці вибраної вкладки (з чемпіоном і твоїм місцем у кожній), по центру —
 * ПОДІУМ трійки лідерів з великими 3D-бюстами й нижче рядки з маленькими бюстами, праворуч — картка «ТИ»
 * (твоє місце, відрив/відставання, корони, найкращі позиції).
 *
 * <h3>Бюсти</h3>
 * Замість пласкої голови — 3D-модель по пояс ({@link LeaderboardAvatar}): нерухома, без погойдування й повороту
 * за курсором. Малюється й для офлайн-гравців — скін приходить із сервера разом із рядком.
 *
 * <h3>Мотивація</h3>
 * Порожні місця подіуму не ховаються, а кличуть («ВІЛЬНО — займи місце №2»); картка «ТИ» завжди каже, скільки бракує
 * до наступного місця (або який у тебе відрив, якщо ти перший); кожен чемпіон отримує корону, а їх кількість видно.
 *
 * <h3>Дані</h3>
 * Екран нічого не рахує — лише просить сервер ({@link ClientLeaderboardState}) і малює. Запити йдуть не частіше
 * ніж раз на {@value #SEND_GAP_MS} мс (сервер ріже частіші). Керування: колесо / PgUp / PgDn — гортати список, ←/→ та ↑/↓ — таблиця,
 * Tab / 1-2-3 — вкладка, Esc — назад.
 */
@OnlyIn(Dist.CLIENT)
public final class LeaderboardScreen extends Screen {

    private static final int PODIUM = 3;
    /** Скільки місць показуємо максимум (топ-100); сервер віддає шматками по {@link #CHUNK}. */
    private static final int TOP_LIMIT = 100;
    private static final int CHUNK = 30;
    private static final long SEND_GAP_MS = 180L;
    private static final long REFRESH_MS = 15_000L;
    private static final long OVERVIEW_REFRESH_MS = 20_000L;

    private static final int B_BACK = 0, B_TAB0 = 1;

    private final Screen parent;
    private Group group = Group.OVERALL;
    private LeaderboardCategory cat = LeaderboardCategory.LEVEL;
    /** Прокрутка списку 4+ у пікселях: ціль і плавне поточне значення. */
    private float scrollTarget;
    private float scrollCur;
    private int maxScroll;
    private long moreAskedAt;
    private int moreAskedFrom = -1;

    private long openedAt;
    private long listAt;
    private long lastSendAt;
    private long pageRequestedAt;
    private long overviewRequestedAt;
    private String requestedKey = "";
    private String shownKey = "";
    private final long[] clock = {0L};
    private float k = 1f;

    private final List<LeaderboardCategory> cats = new ArrayList<>();
    private final float[] btnHover = new float[8];
    private final int[][] btnRect = new int[8][4];
    private final float[] catHover = new float[8];
    private final int[][] catRect = new int[8][4];
    private final float[] rowHover = new float[TOP_LIMIT];

    // Що під курсором у цьому кадрі — для підказки.
    private LeaderboardRow hoverRow;
    private int hoverMx, hoverMy;

    public LeaderboardScreen(Screen parent) {
        super(Component.translatable("maniacmod.lb.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        cats.clear();
        cats.addAll(LeaderboardCategory.of(group));
        ClientProfileState.requestIfStale(30_000L);   // рівень і ранг для картки «ТИ»
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    // ── Дані ─────────────────────────────────────────────────────────────

    /** Не більше одного запиту за {@value #SEND_GAP_MS} мс: сервер ріже частіші. Таблиця важливіша за огляд. */
    private void ensureData(long now, int visibleBottomRank) {
        if (now - lastSendAt < SEND_GAP_MS) return;
        var board = ClientLeaderboardState.board(cat);
        // 1) Початок таблиці: при вході в категорію й періодично (місця змінюються).
        String key = String.valueOf(cat.ordinal());
        if (!key.equals(requestedKey) || now - pageRequestedAt > REFRESH_MS) {
            requestedKey = key;
            pageRequestedAt = now;
            lastSendAt = now;
            ClientLeaderboardState.requestPage(cat, 0, CHUNK);
            return;
        }
        // 2) Дозавантаження вниз, коли докрутили майже до кінця завантаженого.
        if (board != null) {
            int loaded = board.loadedMax();
            int cap = Math.min(board.total, TOP_LIMIT);
            if (loaded < cap && visibleBottomRank >= loaded - 6
                    && (moreAskedFrom != loaded || now - moreAskedAt > 3000L)) {
                moreAskedFrom = loaded;
                moreAskedAt = now;
                lastSendAt = now;
                ClientLeaderboardState.requestPage(cat, loaded, Math.min(CHUNK, cap - loaded));
                return;
            }
        }
        // 3) Огляд (чемпіони й мої місця для бічного списку).
        if (ClientLeaderboardState.overview() == null ? now - overviewRequestedAt > 1500L
                : now - overviewRequestedAt > OVERVIEW_REFRESH_MS) {
            overviewRequestedAt = now;
            lastSendAt = now;
            ClientLeaderboardState.requestOverview();
        }
    }

    private UUID myId() {
        var p = Minecraft.getInstance().player;
        return p == null ? null : p.getUUID();
    }

    private void selectGroup(Group g) {
        if (g == group) return;
        group = g;
        cats.clear();
        cats.addAll(LeaderboardCategory.of(g));
        selectCategory(cats.get(0));
    }

    private void selectCategory(LeaderboardCategory c) {
        if (c == cat) return;
        cat = c;
        scrollTarget = 0f;
        scrollCur = 0f;
        moreAskedFrom = -1;
        shownKey = "";
    }

    private void scrollBy(float px) {
        scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget + px));
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.currentTimeMillis();
        if (openedAt == 0L) openedAt = now;
        float dt = UiAnim.frameDt(clock);
        k = Math.max(0.75f, Math.min(1.6f, height / 540f));
        float open = ease(now - openedAt, 0, 450);
        int accent = LeaderboardUi.accent(group);
        hoverRow = null;

        int m = Math.round(14 * k);
        int topH = Math.round(40 * k);
        int bodyTop = m + topH;
        int bodyH = height - bodyTop - m;
        int gap = Math.round(12 * k);
        int sideW = Math.max(130, Math.min(Math.round(width * 0.22f), Math.round(240 * k)));
        int meW = width < 720 ? 0 : Math.min(Math.round(width * 0.24f), Math.round(270 * k));
        int centerX = m + sideW + gap;
        int centerW = width - m - centerX - (meW > 0 ? meW + gap : 0);
        int podiumH = Math.round(bodyH * (bodyH < 420 * k ? 0.40f : 0.37f));
        int rowH = Math.round(40 * k);
        int rowGap = Math.round(4 * k);
        // Скільки місць зараз у полі зору — за ним сервер дозавантажує наступні шматки.
        int listH = bodyH - podiumH - gap;
        int visibleBottomRank = PODIUM + 1 + Math.round(scrollCur / (rowH + rowGap)) + listH / (rowH + rowGap) + 1;
        ensureData(now, visibleBottomRank);

        // Фон: темна колба + світло кольору вкладки + пил.
        g.fill(0, 0, width, height, LeaderboardUi.a(0xFF020304, 0.92f * open));
        g.fillGradient(0, 0, width, Math.round(height * 0.6f), LeaderboardUi.a(accent, 0.14f * open), 0x00000000);
        g.fillGradient(0, Math.round(height * 0.7f), width, height, 0x00000000, LeaderboardUi.a(0xFF000000, 0.55f * open));
        drawDust(g, now, accent, open);

        drawTopBar(g, m, m, width - 2 * m, topH, mx, my, dt, accent, open);
        drawSidebar(g, m, bodyTop, sideW, bodyH, mx, my, dt, accent, now, open);
        drawCenter(g, centerX, bodyTop, centerW, bodyH, podiumH, rowH, rowGap, gap, mx, my, dt, accent, now, open);
        if (meW > 0) drawMeCard(g, width - m - meW, bodyTop, meW, bodyH, accent, now, open);

        if (hoverRow != null) drawRowTooltip(g, hoverRow);
        super.render(g, mx, my, pt);
    }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    private void drawDust(GuiGraphics g, long now, int accent, float open) {
        for (int i = 0; i < 30; i++) {
            float sx = hash(i * 3L + 1), sp = 0.012f + 0.03f * hash(i * 3L + 2), ph = hash(i * 3L + 3);
            float fy = 1f - ((now / 1000f * sp + ph) % 1f);
            int px = Math.round(sx * width), py = Math.round(fy * height);
            float tw = 0.5f + 0.5f * (float) Math.sin(now / 800.0 + i * 1.7);
            g.fill(px, py, px + 1, py + 1, LeaderboardUi.a(accent, (0.08f + 0.18f * tw) * open));
        }
    }

    private static float hash(long n) {
        long z = n * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }

    // ── Верхня смуга ─────────────────────────────────────────────────────

    private void drawTopBar(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float dt, int accent, float open) {
        LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.overline"), x, y + 2 * k, 0.8f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        String title = LeaderboardUi.tr("maniacmod.lb.title").toUpperCase(Locale.ROOT);
        LeaderboardUi.text(g, font, title, x, y + 13 * k, 1.6f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_TITLE, open), 0);
        int lw = Math.round(font.width(title) * 1.6f * k);
        g.fill(x, y + h - 2, x + lw, y + h, LeaderboardUi.a(accent, open));
        var ov = ClientLeaderboardState.overview();
        if (ov != null && ov.meCrowns() > 0) {
            int cx = x + lw + Math.round(22 * k);
            LeaderboardUi.crown(g, cx, y + Math.round(21 * k), Math.max(1, Math.round(1.4f * k)),
                LeaderboardUi.a(LeaderboardUi.GOLD, open));
            LeaderboardUi.text(g, font, "×" + ov.meCrowns(), cx + 9 * k, y + 22 * k, 0.95f * k,
                LeaderboardUi.a(LeaderboardUi.GOLD, open), 0);
        }

        String back = LeaderboardUi.tr("maniacmod.lb.back").toUpperCase(Locale.ROOT);
        int bw = Math.max(Math.round(110 * k), font.width(back) + Math.round(36 * k));
        int bh = Math.round(22 * k);
        int by = y + (h - bh) / 2 - Math.round(2 * k);
        setRect(B_BACK, x + w - bw, by, bw, bh);
        drawBtn(g, B_BACK, back, mx, my, dt, false, accent, open);

        // Вкладки груп.
        int tw = Math.round(120 * k), tgap = Math.round(6 * k);
        int tx = x + w - bw - Math.round(16 * k) - 3 * tw - 2 * tgap;
        Group[] groups = Group.values();
        for (int i = 0; i < groups.length; i++) {
            setRect(B_TAB0 + i, tx + i * (tw + tgap), by, tw, bh);
            String label = LeaderboardUi.tr("maniacmod.lb.group." + groups[i].name().toLowerCase(Locale.ROOT))
                .toUpperCase(Locale.ROOT);
            drawBtn(g, B_TAB0 + i, label, mx, my, dt, groups[i] == group, LeaderboardUi.accent(groups[i]), open);
        }
    }

    private void setRect(int i, int x, int y, int w, int h) {
        btnRect[i][0] = x; btnRect[i][1] = y; btnRect[i][2] = w; btnRect[i][3] = h;
    }

    private void drawBtn(GuiGraphics g, int i, String label, int mx, int my, float dt, boolean active, int accent, float open) {
        int[] r = btnRect[i];
        boolean hov = LeaderboardUi.inside(mx, my, r);
        btnHover[i] = UiAnim.approach(btnHover[i], hov ? 1f : 0f, 16f, dt);
        float h = btnHover[i];
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], LeaderboardUi.a(ManiacUiTheme.MENU_PANEL_DARK, open));
        int blade = Math.round(r[2] * (active ? 1f : UiAnim.easeOutCubic(h)));
        if (blade > 0) {
            g.fill(r[0], r[1], r[0] + blade, r[1] + r[3], LeaderboardUi.a(accent, (active ? 0.30f : 0.20f) * open));
            g.fill(r[0], r[1] + r[3] - 2, r[0] + blade, r[1] + r[3], LeaderboardUi.a(accent, open));
        }
        ManiacUiTheme.border1px(g, r[0], r[1], r[2], r[3], LeaderboardUi.a(LeaderboardUi.mix(ManiacUiTheme.BORDER,
            accent, active ? 1f : h), open));
        LeaderboardUi.text(g, font, label, r[0] + r[2] / 2f, r[1] + r[3] / 2f - 4f * k, k,
            LeaderboardUi.a(LeaderboardUi.mix(ManiacUiTheme.TEXT_BODY, 0xFFFFFFFF, Math.max(h, active ? 1f : 0f)), open), 1);
    }

    // ── Бічний список таблиць ────────────────────────────────────────────

    private void drawSidebar(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float dt, int accent,
                             long now, float open) {
        for (int[] r : catRect) r[2] = 0;
        LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.tables").toUpperCase(Locale.ROOT), x, y + 1, 0.8f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        int top = y + Math.round(14 * k);
        int n = Math.min(cats.size(), catRect.length);
        int cardGap = Math.round(6 * k);
        int cardH = Math.min(Math.round(52 * k), (y + h - top - cardGap * (n - 1)) / Math.max(1, n));
        var ov = ClientLeaderboardState.overview();

        for (int i = 0; i < n; i++) {
            LeaderboardCategory c = cats.get(i);
            float ea = ease(now - openedAt, 80L + i * 60L, 380);
            int cy = top + i * (cardH + cardGap);
            int slide = Math.round((1f - ea) * -22 * k);
            int cx = x + slide;
            catRect[i][0] = x; catRect[i][1] = cy; catRect[i][2] = w; catRect[i][3] = cardH;
            boolean hov = LeaderboardUi.inside(mx, my, catRect[i]);
            catHover[i] = UiAnim.approach(catHover[i], hov ? 1f : 0f, 14f, dt);
            boolean sel = c == cat;
            float hv = catHover[i];
            float al = ea * open;

            g.fill(cx, cy, cx + w, cy + cardH, LeaderboardUi.a(ManiacUiTheme.SLOT_FILL, al));
            int blade = Math.round(w * (sel ? 1f : UiAnim.easeOutCubic(hv) * 0.55f));
            if (blade > 0) {
                g.fillGradient(cx, cy, cx + blade, cy + cardH, LeaderboardUi.a(accent, (sel ? 0.30f : 0.16f) * al),
                    LeaderboardUi.a(accent, 0f));
            }
            g.fill(cx, cy, cx + (sel ? 3 : 2), cy + cardH, LeaderboardUi.a(sel ? accent : ManiacUiTheme.BORDER, al));
            ManiacUiTheme.border1px(g, cx, cy, w, cardH, LeaderboardUi.a(LeaderboardUi.mix(ManiacUiTheme.BORDER,
                accent, sel ? 0.8f : hv * 0.6f), al));

            // Назва + чемпіон.
            float tx = cx + 10 * k;
            float maxW = w - 20 * k - 42 * k;
            LeaderboardUi.text(g, font, LeaderboardUi.fit(font, LeaderboardUi.tr(c.nameKey()), 0.95f * k, maxW),
                tx, cy + 7 * k, 0.95f * k, LeaderboardUi.a(sel ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_BODY, al), 0);
            var e = ov == null ? null : ov.byCategory().get(c);
            if (e != null && e.total() > 0) {
                LeaderboardUi.crown(g, Math.round(tx + 4 * k), Math.round(cy + cardH - 14 * k), 1, LeaderboardUi.a(LeaderboardUi.GOLD, al));
                LeaderboardUi.text(g, font, LeaderboardUi.fit(font, e.champName() + " · "
                        + LeaderboardUi.main(c, e.champValue()), 0.75f * k, maxW - 12 * k),
                    tx + 12 * k, cy + cardH - 14 * k, 0.75f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 0);
            } else if (ov != null) {
                LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.free_throne"), tx, cy + cardH - 14 * k, 0.75f * k,
                    LeaderboardUi.a(LeaderboardUi.GOLD, al * 0.8f), 0);
            }

            // Моє місце в цій таблиці.
            if (e != null && e.meRank() > 0) {
                int mc = LeaderboardUi.medal(e.meRank());
                LeaderboardUi.text(g, font, "#" + e.meRank(), cx + w - 8 * k, cy + 7 * k, 1.05f * k,
                    LeaderboardUi.a(mc != 0 ? mc : accent, al), 2);
            } else if (ov != null) {
                LeaderboardUi.text(g, font, "—", cx + w - 8 * k, cy + 7 * k, 1.05f * k,
                    LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 2);
            }
        }
    }

    // ── Центр: подіум і рядки ────────────────────────────────────────────

    private void drawCenter(GuiGraphics g, int x, int y, int w, int h, int podiumH, int rowH, int rowGap,
                            int gap, int mx, int my, float dt, int accent, long now, float open) {
        var board = ClientLeaderboardState.board(cat);
        if (board != null && !shownKey.equals(String.valueOf(cat.ordinal()))) {
            shownKey = String.valueOf(cat.ordinal());
            listAt = now;
        }
        int total = board == null ? 0 : board.total;
        long top1 = board == null || board.row(1) == null ? 1L : Math.max(1L, board.row(1).value());

        // Подіум: місця 1–3 (порожні слоти кличуть зайняти місце).
        List<LeaderboardRow> top = new ArrayList<>();
        for (int r = 1; r <= PODIUM; r++) {
            LeaderboardRow row = board == null ? null : board.row(r);
            if (row == null) break;
            top.add(row);
        }
        drawPodium(g, x, y, w, podiumH, top, board != null, accent, now, mx, my, open);

        int listTop = y + podiumH + gap;
        int listH = y + h - listTop;
        int step = rowH + rowGap;
        int remaining = board == null ? 0 : Math.max(0, Math.min(total, TOP_LIMIT) - PODIUM);
        int contentH = remaining * step;
        maxScroll = Math.max(0, contentH - listH);
        scrollTarget = Math.max(0f, Math.min(maxScroll, scrollTarget));
        scrollCur += (scrollTarget - scrollCur) * Math.min(1f, dt * 14f);
        if (Math.abs(scrollTarget - scrollCur) < 0.3f) scrollCur = scrollTarget;

        // Заголовок списку: «МІСЦЯ 4+».
        if (remaining > 0 || board == null) {
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.list.title", PODIUM + 1,
                    Math.min(Math.max(total, PODIUM + 1), TOP_LIMIT)).toUpperCase(Locale.ROOT), x, listTop - 1 * k, 0.75f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
            listTop += Math.round(11 * k);
            listH -= Math.round(11 * k);
            maxScroll = Math.max(0, contentH - listH);
        }

        if (board == null) {
            drawShimmer(g, x, listTop, w, rowH, rowGap, Math.max(2, Math.min(5, listH / step)), now, open);
        } else if (total <= PODIUM) {
            LeaderboardUi.text(g, font, total == 0 ? LeaderboardUi.tr("maniacmod.lb.empty") : LeaderboardUi.tr("maniacmod.lb.list.rest"),
                x + w / 2f, listTop + 10 * k, 1.0f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_BODY, open), 1);
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.empty.hint"), x + w / 2f, listTop + 26 * k, 0.8f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 1);
        }

        UUID me = myId();
        for (int i = 0; i < rowHover.length; i++) rowHover[i] = Math.max(0f, rowHover[i] - dt * 6f);
        int viewW = w - Math.round(8 * k);   // вузька смуга справа під повзунок
        g.enableScissor(x - 2, listTop, x + w + 2, listTop + listH);
        try {
            for (int i = 0; i < remaining; i++) {
                int rank = PODIUM + 1 + i;
                int ry = listTop + i * step - Math.round(scrollCur);
                if (ry + rowH < listTop) continue;
                if (ry > listTop + listH) break;
                LeaderboardRow r = board.row(rank);
                if (r == null) {
                    // Шматок ще їде з сервера — рядок-«скелет».
                    g.fill(x, ry, x + viewW, ry + rowH, LeaderboardUi.a(ManiacUiTheme.SLOT_FILL, 0.6f * open));
                    LeaderboardUi.text(g, font, "#" + rank, x + 25 * k, ry + rowH / 2f - 4 * k, 1.0f * k,
                        LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 1);
                    continue;
                }
                float ea = ease(now - listAt, Math.min(i, 10) * 40L + 120L, 360);
                int slide = Math.round((1f - ea) * 24 * k);
                int[] rect = {x, Math.max(ry, listTop), viewW, Math.min(ry + rowH, listTop + listH) - Math.max(ry, listTop)};
                boolean hov = LeaderboardUi.inside(mx, my, rect);
                if (i < rowHover.length) rowHover[i] = UiAnim.approach(rowHover[i], hov ? 1f : 0f, 14f, dt);
                if (hov) { hoverRow = r; hoverMx = mx; hoverMy = my; }
                drawRow(g, r, x - slide, ry, viewW, rowH, i < rowHover.length ? rowHover[i] : 0f,
                    r.id().equals(me), top1, accent, ea * open, now);
            }
        } finally {
            g.disableScissor();
        }
        drawScrollbar(g, x + w - Math.round(4 * k), listTop, Math.round(3 * k), listH, contentH, accent, open);
        if (total > 0) {
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.count", total), x + w - Math.round(8 * k), listTop - 11 * k,
                0.75f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 2);
        }
    }

    private void drawScrollbar(GuiGraphics g, int x, int y, int w, int h, int contentH, int accent, float open) {
        if (maxScroll <= 0 || contentH <= 0) return;
        g.fill(x, y, x + w, y + h, LeaderboardUi.a(ManiacUiTheme.BORDER, 0.5f * open));
        int thumbH = Math.max(Math.round(18 * k), Math.round(h * (h / (float) contentH)));
        int thumbY = y + Math.round((h - thumbH) * (scrollCur / maxScroll));
        g.fill(x, thumbY, x + w, thumbY + thumbH, LeaderboardUi.a(accent, 0.9f * open));
    }

    private void drawShimmer(GuiGraphics g, int x, int y, int w, int rowH, int rowGap, int rows, long now, float open) {
        for (int i = 0; i < rows; i++) {
            int cy = y + i * (rowH + rowGap);
            g.fill(x, cy, x + w, cy + rowH, LeaderboardUi.a(ManiacUiTheme.SLOT_FILL, open));
            float s = ((now / 900f) + i * 0.15f) % 1f;
            int sx = x + Math.round(s * (w + 80 * k)) - Math.round(80 * k);
            g.fillGradient(Math.max(x, sx), cy, Math.min(x + w, sx + Math.round(60 * k)), cy + rowH,
                0x00FFFFFF, LeaderboardUi.a(0xFFFFFFFF, 0.05f * open));
            ManiacUiTheme.border1px(g, x, cy, w, rowH, LeaderboardUi.a(ManiacUiTheme.BORDER, open));
        }
        LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.loading"), x + w / 2f, y + rows * (rowH + rowGap) + 4 * k,
            0.9f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, open), 1);
    }

    /** Рядок 4+: місце, 3D-бюст, нік із підписом, значення справа й смужка «наскільки близько до першого». */
    private void drawRow(GuiGraphics g, LeaderboardRow r, int x, int y, int w, int h, float hv, boolean mine,
                         long top1, int accent, float al, long now) {
        int medal = LeaderboardUi.medal(r.rank());
        int edge = mine ? accent : ManiacUiTheme.BORDER;
        g.fill(x, y, x + w, y + h, LeaderboardUi.a(mine ? LeaderboardUi.mix(ManiacUiTheme.SLOT_FILL, accent, 0.10f)
            : ManiacUiTheme.SLOT_FILL, al));
        if (hv > 0.01f) {
            g.fillGradient(x, y, x + Math.round(w * (0.35f + 0.65f * hv)), y + h,
                LeaderboardUi.a(accent, 0.16f * hv * al), LeaderboardUi.a(accent, 0f));
        }
        g.fill(x, y, x + 3, y + h, LeaderboardUi.a(mine ? accent : LeaderboardUi.mix(ManiacUiTheme.BORDER, accent, hv), al));
        ManiacUiTheme.border1px(g, x, y, w, h, LeaderboardUi.a(LeaderboardUi.mix(edge, accent, hv * 0.7f), al));

        // Місце.
        LeaderboardUi.text(g, font, "#" + r.rank(), x + 25 * k, y + h / 2f - 5 * k, 1.15f * k,
            LeaderboardUi.a(medal != 0 ? medal : (r.rank() <= 10 ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_DIM), al), 1);

        // 3D-бюст (нерухомий).
        int bs = h - Math.round(6 * k);
        int bx = x + Math.round(46 * k), by = y + (h - bs) / 2;
        drawBustBox(g, r, bx, by, bs, bs, 180f, accent, al, false);

        // Нік + підпис.
        float tx = bx + bs + 9 * k;
        float valueW = Math.max(font.width(LeaderboardUi.main(cat, r.value())) * 1.2f * k, 60 * k);
        float maxW = x + w - valueW - 18 * k - tx;
        LeaderboardUi.text(g, font, LeaderboardUi.fit(font, r.name(), 1.0f * k, maxW), tx, y + 7 * k, 1.0f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_TITLE, al), 0);
        if (mine) {
            float nw = font.width(LeaderboardUi.fit(font, r.name(), 1.0f * k, maxW)) * k;
            chip(g, Math.round(tx + nw + 5 * k), Math.round(y + 6 * k), LeaderboardUi.tr("maniacmod.lb.you"), accent, al);
        }
        String subline = LeaderboardUi.tr("maniacmod.lb.row.level", r.level()) + " · "
            + LeaderboardUi.rankName(group, r.level()) + " · "
            + LeaderboardUi.tr("maniacmod.lb.row.games", r.matches());
        LeaderboardUi.text(g, font, LeaderboardUi.fit(font, subline, 0.74f * k, maxW), tx, y + h - 15 * k, 0.74f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 0);
        if (r.online()) {
            g.fill(bx + bs - Math.round(6 * k), by + Math.round(2 * k), bx + bs - Math.round(2 * k), by + Math.round(6 * k),
                LeaderboardUi.a(ManiacTabTheme.OK, al));
        }

        // Значення справа.
        float vx = x + w - 10 * k;
        LeaderboardUi.text(g, font, LeaderboardUi.main(cat, r.value()), vx, y + 8 * k, 1.2f * k,
            LeaderboardUi.a(medal != 0 ? medal : ManiacUiTheme.TEXT_TITLE, al), 2);
        String sub = LeaderboardUi.sub(cat, r.value());
        if (sub != null) {
            LeaderboardUi.text(g, font, sub, vx, y + h - 15 * k, 0.74f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 2);
        }

        // Смужка відносно першого місця.
        float frac = Math.max(0.02f, Math.min(1f, r.value() / (float) Math.max(1L, top1)));
        int barW = Math.round((w - 6) * frac * UiAnim.easeOutCubic(UiAnim.clamp01(al)));
        g.fill(x + 3, y + h - 2, x + 3 + barW, y + h - 1, LeaderboardUi.a(accent, 0.75f * al));

        if (hv > 0.3f) {
            UiFx.brackets(g, x, y, w, h, LeaderboardUi.a(ManiacUiTheme.CORNER_ACCENT, hv * al), Math.round(6 * k), hv, 2);
        }
    }

    private void chip(GuiGraphics g, int x, int y, String label, int color, float al) {
        int cw = Math.round(font.width(label) * 0.7f * k) + Math.round(8 * k);
        int ch = Math.round(9 * k);
        g.fill(x, y, x + cw, y + ch, LeaderboardUi.a(color, 0.85f * al));
        LeaderboardUi.text(g, font, label, x + cw / 2f, y + ch / 2f - 3.5f * k, 0.7f * k, LeaderboardUi.a(0xFF060708, al), 1);
    }

    /**
     * Рамка з нерухомим 3D-бюстом: фон-колба, сяйво кольору, модель, «туман» знизу, рамка. Анімація лише
     * в появі (затемнення, що розсіюється) — сама модель стоїть незмінно.
     */
    private void drawBustBox(GuiGraphics g, LeaderboardRow r, int x, int y, int w, int h, float yaw, int glow, float al,
                             boolean big) {
        g.fill(x, y, x + w, y + h, LeaderboardUi.a(ManiacUiTheme.PANEL_BG, al));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, LeaderboardUi.a(glow, 0.0f), LeaderboardUi.a(glow, 0.22f * al));
        boolean drawn = r != null && al > 0.35f
            && LeaderboardAvatar.draw(g, r.id(), r.name(), r.skin(), x + 1, y + 1, w - 2, h - 2, yaw);
        if (!drawn) LeaderboardAvatar.drawSilhouette(g, x + 1, y + 1, w - 2, h - 2, LeaderboardUi.a(0xFF0B0D0E, 0.9f * al));
        int fog = Math.round(h * (big ? 0.28f : 0.22f));
        g.fillGradient(x + 1, y + h - fog, x + w - 1, y + h - 1, 0x00000000, LeaderboardUi.a(0xFF050607, 0.85f * al));
        if (al < 0.999f) g.fill(x, y, x + w, y + h, LeaderboardUi.a(0xFF020304, 0.9f * (1f - al)));
        ManiacUiTheme.border1px(g, x, y, w, h, LeaderboardUi.a(LeaderboardUi.mix(ManiacUiTheme.BORDER, glow, 0.5f), al));
    }

    // ── Подіум ───────────────────────────────────────────────────────────

    private void drawPodium(GuiGraphics g, int x, int y, int w, int h, List<LeaderboardRow> rows, boolean loaded,
                            int accent, long now, int mx, int my, float open) {
        int gap = Math.round(10 * k);
        int colW = (w - 2 * gap) / 3;
        int[] order = {1, 0, 2};                         // ліворуч — друге місце, центр — перше, праворуч — третє
        float[] heightFrac = {1.0f, 0.90f, 0.84f};       // за індексом місця
        float[] yaw = {180f, 166f, 194f};
        UUID me = myId();
        for (int s = 0; s < 3; s++) {
            int ri = order[s];
            int cx = x + s * (colW + gap);
            int ch = Math.round(h * heightFrac[ri]);
            float ea = ease(now - listAt, ri == 0 ? 0L : 140L + ri * 90L, 560);
            int cy = y + h - ch + Math.round((1f - ea) * 34 * k);
            LeaderboardRow r = loaded && ri < rows.size() ? rows.get(ri) : null;
            float al = (loaded ? ea : 0.5f) * open;
            drawPodiumCard(g, r, ri + 1, cx, cy, colW, ch, yaw[ri], al, now, r != null && r.id().equals(me));
            if (r != null && LeaderboardUi.inside(mx, my, new int[]{cx, cy, colW, ch})) {
                hoverRow = r; hoverMx = mx; hoverMy = my;
            }
        }
    }

    private void drawPodiumCard(GuiGraphics g, LeaderboardRow r, int rank, int x, int y, int w, int h, float yaw,
                                float al, long now, boolean mine) {
        int mc = LeaderboardUi.medal(rank);
        float pulse = 0.7f + 0.3f * (float) Math.sin(now / 900.0 + rank);
        g.fill(x, y, x + w, y + h, LeaderboardUi.a(ManiacUiTheme.PANEL_BG, al));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + Math.round(h * 0.75f),
            LeaderboardUi.a(mc, (rank == 1 ? 0.26f : 0.16f) * pulse * al), LeaderboardUi.a(mc, 0f));
        if (rank == 1) {
            // Промінь прожектора над чемпіоном.
            int bw = Math.round(w * 0.5f);
            g.fillGradient(x + (w - bw) / 2, y + 1, x + (w + bw) / 2, y + Math.round(h * 0.55f),
                LeaderboardUi.a(GOLD_BEAM, 0.12f * pulse * al), 0x00000000);
        }
        g.fill(x, y, x + w, y + 2, LeaderboardUi.a(mc, al));

        // Іскри кольору медалі.
        for (int i = 0; i < 9; i++) {
            float sx = hash(rank * 31L + i * 3L + 1), sp = 0.03f + 0.05f * hash(rank * 31L + i * 3L + 2),
                ph = hash(rank * 31L + i * 3L + 3);
            float fy = 1f - ((now / 1000f * sp + ph) % 1f);
            int px = x + 3 + Math.round(sx * (w - 6)), py = y + 4 + Math.round(fy * (h - 8));
            float tw = 0.5f + 0.5f * (float) Math.sin(now / 600.0 + i * 1.9);
            g.fill(px, py, px + 1, py + 1, LeaderboardUi.a(mc, (0.10f + 0.30f * tw) * al));
        }

        int pad = Math.round(8 * k);
        int crownSpace = rank == 1 ? Math.round(12 * k) : 0;
        int bx = x + pad, by = y + pad + crownSpace;
        int bw = w - 2 * pad;
        int bh = Math.round(h * 0.56f) - crownSpace;
        drawBustBox(g, r, bx, by, bw, bh, yaw, mc, al, true);
        UiFx.brackets(g, bx, by, bw, bh, LeaderboardUi.a(mc, 0.9f * al), Math.round(8 * k), 0f, 0);
        if (rank == 1) {
            LeaderboardUi.crown(g, x + w / 2, by - Math.round(11 * k), Math.max(1, Math.round(1.6f * k)),
                LeaderboardUi.a(GOLD_BEAM, al));
        }

        // Значок місця в куті бюста.
        int cs = Math.round(16 * k);
        g.fill(bx, by, bx + cs, by + cs, LeaderboardUi.a(mc, 0.95f * al));
        LeaderboardUi.text(g, font, String.valueOf(rank), bx + cs / 2f, by + cs / 2f - 4f * k, 1.1f * k,
            LeaderboardUi.a(0xFF060708, al), 1);
        if (r != null && r.online()) {
            g.fill(bx + bw - Math.round(7 * k), by + Math.round(3 * k), bx + bw - Math.round(3 * k), by + Math.round(7 * k),
                LeaderboardUi.a(ManiacTabTheme.OK, al));
        }

        // Табличка.
        float cx = x + w / 2f;
        float ty = by + bh + 7 * k;
        if (r == null) {
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.podium.free"), cx, ty, 1.1f * k,
                LeaderboardUi.a(mc, 0.9f * al), 1);
            LeaderboardUi.text(g, font, LeaderboardUi.fit(font, LeaderboardUi.tr("maniacmod.lb.podium.claim", rank), 0.78f * k, w - 12 * k),
                cx, ty + 15 * k, 0.78f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 1);
            return;
        }
        String name = LeaderboardUi.fit(font, r.name(), 1.15f * k, w - 18 * k);
        LeaderboardUi.text(g, font, name, cx, ty, 1.15f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_TITLE, al), 1);
        if (mine) {
            chip(g, Math.round(cx + font.width(name) * 1.15f * k / 2f + 4 * k), Math.round(ty), LeaderboardUi.tr("maniacmod.lb.you"), mc, al);
        }
        LeaderboardUi.text(g, font, LeaderboardUi.main(cat, r.value()), cx, ty + 14 * k, 1.5f * k, LeaderboardUi.a(mc, al), 1);
        String info = LeaderboardUi.tr("maniacmod.lb.row.level", r.level()) + " · " + LeaderboardUi.rankName(group, r.level());
        LeaderboardUi.text(g, font, LeaderboardUi.fit(font, info, 0.74f * k, w - 12 * k), cx, ty + 32 * k, 0.74f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 1);
        String fav = favouriteLine(r);
        if (fav != null && h > 190 * k) {
            LeaderboardUi.text(g, font, LeaderboardUi.fit(font, fav, 0.7f * k, w - 12 * k), cx, ty + 43 * k, 0.7f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al * 0.85f), 1);
        }
    }

    private static final int GOLD_BEAM = LeaderboardUi.GOLD;

    private static String favouriteLine(LeaderboardRow r) {
        if (r.favManiac().isEmpty()) return null;
        ManiacArchetype a = ManiacRegistry.all().get(r.favManiac());
        return LeaderboardUi.tr("maniacmod.lb.row.fav", a != null ? a.displayName() : r.favManiac());
    }

    // ── Картка «ТИ» ──────────────────────────────────────────────────────

    private void drawMeCard(GuiGraphics g, int x, int y, int w, int h, int accent, long now, float open) {
        float ea = ease(now - openedAt, 160, 500);
        float al = ea * open;
        int slide = Math.round((1f - ea) * 24 * k);
        x += slide;
        g.fill(x, y, x + w, y + h, LeaderboardUi.a(ManiacUiTheme.PANEL_BG, al));
        g.fill(x, y, x + w, y + 2, LeaderboardUi.a(accent, al));
        ManiacUiTheme.border1px(g, x, y, w, h, LeaderboardUi.a(ManiacUiTheme.BORDER, al));
        UiFx.brackets(g, x, y, w, h, LeaderboardUi.a(ManiacUiTheme.CORNER_ACCENT, 0.9f * al), Math.round(10 * k), 0f, 0);

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int pad = Math.round(10 * k);
        float cx = x + w / 2f;

        LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.me.title").toUpperCase(Locale.ROOT), x + pad, y + 8 * k,
            0.8f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 0);

        // Бюст власного гравця — той самий нерухомий віджет.
        int bw = w - 2 * pad;
        int bh = Math.round(Math.min(h * 0.30f, bw * 0.8f));
        int by = y + Math.round(20 * k);
        String myName = mc.player.getGameProfile().getName();
        drawBustBox(g, new LeaderboardRow(0, mc.player.getUUID(), myName, 0L, 1, 0, 0, "", 0, true, 0L, ""),
            x + pad, by, bw, bh, 180f, accent, al, true);

        float ty = by + bh + 8 * k;
        LeaderboardUi.text(g, font, LeaderboardUi.fit(font, myName, 1.2f * k, w - 2 * pad), cx, ty, 1.2f * k,
            LeaderboardUi.a(ManiacUiTheme.TEXT_TITLE, al), 1);
        ty += 15 * k;

        // Рівень і ранг із профілю.
        PlayerProfile prof = ClientProfileState.get();
        if (prof != null) {
            long xp = prof.totalXp();
            int level = XpCurve.levelOf(xp);
            Ranks.Role role = group == Group.MANIAC ? Ranks.Role.MANIAC : Ranks.Role.SURVIVOR;
            Ranks.Rank rank = Ranks.ofLevel(role, level);
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.row.level", level) + " · "
                    + LeaderboardUi.rankName(group, level), cx, ty, 0.82f * k, LeaderboardUi.a(RankFx.tierColor(role, rank.tier()), al), 1);
            ty += 12 * k;
        }

        var pg = ClientLeaderboardState.board(cat);
        var ov = ClientLeaderboardState.overview();
        int meRank = pg == null ? 0 : pg.meRank;
        long meValue = pg == null ? 0L : pg.meValue;

        // Блок «твоє місце в вибраній таблиці».
        int boxY = Math.round(ty + 2 * k);
        int boxH = Math.round(58 * k);
        g.fill(x + pad, boxY, x + w - pad, boxY + boxH, LeaderboardUi.a(ManiacUiTheme.SLOT_FILL, al));
        ManiacUiTheme.border1px(g, x + pad, boxY, w - 2 * pad, boxH, LeaderboardUi.a(ManiacUiTheme.BORDER, al));
        LeaderboardUi.text(g, font, LeaderboardUi.fit(font, LeaderboardUi.tr(cat.nameKey()), 0.74f * k, w - 2 * pad - 10 * k),
            x + pad + 6 * k, boxY + 5 * k, 0.74f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 0);
        if (pg == null) {
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.loading"), cx, boxY + 26 * k, 0.9f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 1);
        } else if (meRank > 0) {
            int medal = LeaderboardUi.medal(meRank);
            LeaderboardUi.text(g, font, "#" + meRank, x + pad + 8 * k, boxY + 18 * k, 2.1f * k,
                LeaderboardUi.a(medal != 0 ? medal : accent, al), 0);
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.me.of", pg.total), x + w - pad - 8 * k, boxY + 17 * k,
                0.76f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 2);
            LeaderboardUi.text(g, font, LeaderboardUi.main(cat, meValue), x + w - pad - 8 * k, boxY + 29 * k, 1.05f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_TITLE, al), 2);
        } else {
            LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.me.unranked"), cx, boxY + 24 * k, 1.0f * k,
                LeaderboardUi.a(ManiacUiTheme.TEXT_BODY, al), 1);
        }

        // Мотивація: скільки бракує або який відрив.
        int msgY = boxY + boxH + Math.round(7 * k);
        String msg = null;
        int msgColor = ManiacUiTheme.TEXT_BODY;
        if (pg != null) {
            if (meRank == 0) {
                msg = LeaderboardUi.tr("maniacmod.lb.me.unranked.hint");
            } else if (meRank == 1) {
                msg = pg.nearName.isEmpty() ? LeaderboardUi.tr("maniacmod.lb.me.top1")
                    : LeaderboardUi.tr("maniacmod.lb.me.lead", pg.nearName, LeaderboardUi.delta(cat, meValue - pg.nearValue));
                msgColor = LeaderboardUi.GOLD;
            } else if (!pg.nearName.isEmpty()) {
                msg = LeaderboardUi.tr("maniacmod.lb.me.gap", meRank - 1, pg.nearName,
                    LeaderboardUi.delta(cat, pg.nearValue - meValue + (cat.format() == LeaderboardCategory.Format.PERCENT ? 0L : 1L)));
                msgColor = accent;
            }
        }
        if (msg != null) {
            int lineY = msgY;
            for (var line : font.split(Component.literal(msg), Math.round((w - 2 * pad) / (0.82f * k)))) {
                g.pose().pushPose();
                g.pose().translate(x + pad, lineY, 0f);
                g.pose().scale(0.82f * k, 0.82f * k, 1f);
                g.drawString(font, line, 0, 0, LeaderboardUi.a(msgColor, al), false);
                g.pose().popPose();
                lineY += Math.round(11 * k);
            }
            msgY = lineY + Math.round(4 * k);
        }

        // Найкращі позиції.
        if (ov != null && msgY < y + h - 40 * k) {
            List<LeaderboardOverviewPacket.Entry> best = new ArrayList<>();
            for (var e : ov.byCategory().values()) if (e.meRank() > 0) best.add(e);
            best.sort(java.util.Comparator.comparingDouble(e -> e.meRank() / (double) Math.max(1, e.total())));
            if (!best.isEmpty()) {
                g.fill(x + pad, msgY, x + w - pad, msgY + 1, LeaderboardUi.a(ManiacUiTheme.BORDER, al));
                LeaderboardUi.text(g, font, LeaderboardUi.tr("maniacmod.lb.me.best").toUpperCase(Locale.ROOT), x + pad,
                    msgY + 5 * k, 0.72f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_DIM, al), 0);
                int ly = Math.round(msgY + 17 * k);
                for (int i = 0; i < Math.min(3, best.size()) && ly < y + h - 12 * k; i++) {
                    var e = best.get(i);
                    LeaderboardCategory c = LeaderboardCategory.byOrdinal(e.category());
                    if (c == null) continue;
                    int medal = LeaderboardUi.medal(e.meRank());
                    LeaderboardUi.text(g, font, "#" + e.meRank(), x + pad, ly, 0.9f * k,
                        LeaderboardUi.a(medal != 0 ? medal : LeaderboardUi.accent(c.group()), al), 0);
                    LeaderboardUi.text(g, font, LeaderboardUi.fit(font, LeaderboardUi.tr(c.nameKey()), 0.78f * k,
                        w - 2 * pad - 30 * k), x + pad + 28 * k, ly + 1, 0.78f * k, LeaderboardUi.a(ManiacUiTheme.TEXT_BODY, al), 0);
                    ly += Math.round(12 * k);
                }
            }
        }
    }

    // ── Підказка ─────────────────────────────────────────────────────────

    private void drawRowTooltip(GuiGraphics g, LeaderboardRow r) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(r.name()));
        lines.add(Component.literal(LeaderboardUi.tr("maniacmod.lb.row.level", r.level()) + " · "
            + LeaderboardUi.rankName(group, r.level())));
        lines.add(Component.literal(LeaderboardUi.tr(cat.nameKey()) + ": " + LeaderboardUi.main(cat, r.value())));
        lines.add(Component.literal(LeaderboardUi.tr("maniacmod.lb.tip.record", r.wins(), r.matches())));
        if (r.crowns() > 0) lines.add(Component.literal(LeaderboardUi.tr("maniacmod.lb.tip.crowns", r.crowns())));
        String fav = favouriteLine(r);
        if (fav != null) lines.add(Component.literal(fav));
        lines.add(Component.literal(r.online() ? LeaderboardUi.tr("maniacmod.lb.tip.online")
            : r.lastSeenMs() > 0L ? LeaderboardUi.tr("maniacmod.lb.tip.seen", LeaderboardUi.ago(r.lastSeenMs())) : ""));
        lines.removeIf(c -> c.getString().isEmpty());
        g.renderComponentTooltip(font, lines, hoverMx, hoverMy);
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        if (LeaderboardUi.inside(mx, my, btnRect[B_BACK])) { UiAnim.playClick(); onClose(); return true; }
        Group[] groups = Group.values();
        for (int i = 0; i < groups.length; i++) {
            if (LeaderboardUi.inside(mx, my, btnRect[B_TAB0 + i])) {
                UiAnim.playClick();
                selectGroup(groups[i]);
                return true;
            }
        }
        for (int i = 0; i < Math.min(cats.size(), catRect.length); i++) {
            if (LeaderboardUi.inside(mx, my, catRect[i])) {
                if (cats.get(i) != cat) UiAnim.playClick();
                selectCategory(cats.get(i));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollBy((float) -delta * (40f + 24f * k));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> { onClose(); return true; }
            case GLFW.GLFW_KEY_PAGE_UP -> { scrollBy(-height * 0.5f); return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { scrollBy(height * 0.5f); return true; }
            case GLFW.GLFW_KEY_HOME -> { scrollBy(-1_000_000f); return true; }
            case GLFW.GLFW_KEY_END -> { scrollBy(1_000_000f); return true; }
            case GLFW.GLFW_KEY_LEFT -> { stepCategory(-1); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { stepCategory(1); return true; }
            case GLFW.GLFW_KEY_UP -> { stepCategory(-1); return true; }
            case GLFW.GLFW_KEY_DOWN -> { stepCategory(1); return true; }
            case GLFW.GLFW_KEY_TAB -> {
                Group[] gs = Group.values();
                selectGroup(gs[(group.ordinal() + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? gs.length - 1 : 1)) % gs.length]);
                UiAnim.playClick();
                return true;
            }
            case GLFW.GLFW_KEY_1 -> { selectGroup(Group.OVERALL); return true; }
            case GLFW.GLFW_KEY_2 -> { selectGroup(Group.SURVIVOR); return true; }
            case GLFW.GLFW_KEY_3 -> { selectGroup(Group.MANIAC); return true; }
            default -> { return super.keyPressed(key, scan, mods); }
        }
    }

    private void stepCategory(int d) {
        int i = cats.indexOf(cat);
        if (i < 0) return;
        int n = cats.size();
        selectCategory(cats.get(((i + d) % n + n) % n));
        UiAnim.playClick();
    }
}

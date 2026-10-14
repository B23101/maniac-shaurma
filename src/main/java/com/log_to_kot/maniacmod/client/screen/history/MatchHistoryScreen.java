package com.log_to_kot.maniacmod.client.screen.history;

import com.log_to_kot.maniacmod.client.history.ClientHistoryState;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.history.MatchBrief;
import com.log_to_kot.maniacmod.history.MatchRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Історія матчів: ліворуч — картки матчів (коли грали, скільки, хто був, чим усе скінчилось), праворуч —
 * «досьє» вибраного матчу з 3D-бюстом маніяка. Клік по вибраній картці (або «Відкрити») — детальний екран.
 *
 * <h3>Два режими з одного екрана</h3>
 * <ul>
 *   <li>{@code target == null} — АДМІНСЬКИЙ архів усіх матчів (пошук за ніком/маніяком, фільтр за результатом);
 *       сервер віддає його лише тим, хто має права;</li>
 *   <li>{@code target != null} — ОСОБИСТА історія гравця (розділ «Історія ігор» у профілі): лише матчі, де він
 *       брав участь, з його роллю, результатом і XP. Той самий екран — другого джерела вигляду нема.</li>
 * </ul>
 *
 * <p>Дані — {@link ClientHistoryState}; екран нічого не рахує, лише просить сторінку в сервера
 * (фільтр/сторінка/пошук) і малює те, що прийшло. Керування: ←/→ — сторінки, ↑/↓ — вибір, Enter —
 * відкрити, колесо — сторінки, Esc — назад.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MatchHistoryScreen extends Screen {

    private static final int MAX_ROWS = 14;
    private static final int SEARCH_MAX = 24;
    private static final long SEARCH_DEBOUNCE_MS = 350L;

    private static final int B_BACK = 0, B_F0 = 1, B_PREV = 4, B_NEXT = 5, B_OPEN = 6;

    private final Screen parent;
    private final UUID target;
    private final String targetName;

    private int filter;
    private String query = "";
    private boolean searchFocus;
    private int offset;
    private int limit = 5;
    private String requestedKey = "";
    private long queryDirtyAt;
    private long seenVersion = -1L;
    private long openedAt;
    private long listAt;
    private String selectedId = "";
    /** 0 — досьє сховане (матч не вибрано), 1 — повністю виїхало. */
    private float dossierT;

    private final ManiacBustWidget bust = new ManiacBustWidget();
    private final long[] clock = {0L};
    private float k = 1f;

    private final float[] cardHover = new float[MAX_ROWS];
    private final int[][] cardRect = new int[MAX_ROWS][4];
    private final float[] btnHover = new float[8];
    private final int[][] btnRect = new int[8][4];
    private final int[] searchRect = new int[4];

    /** Адмінський архів усіх матчів. */
    public MatchHistoryScreen(Screen parent) {
        this(parent, null, null);
    }

    /** Особиста історія гравця {@code target} (власна або чужого профілю). */
    public MatchHistoryScreen(Screen parent, UUID target, String targetName) {
        super(Component.translatable("maniacmod.history.title"));
        this.parent = parent;
        this.target = target;
        this.targetName = targetName == null ? "" : targetName;
    }

    private boolean adminArchive() { return target == null; }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    // ── Дані ─────────────────────────────────────────────────────────────

    private ClientHistoryState.ListView view() { return ClientHistoryState.list(target); }

    private void ensureData(long now) {
        if (searchFocus && queryDirtyAt != 0L && now - queryDirtyAt < SEARCH_DEBOUNCE_MS) return;
        queryDirtyAt = 0L;
        String key = filter + "|" + query + "|" + offset + "|" + limit;
        if (key.equals(requestedKey)) return;
        requestedKey = key;
        ClientHistoryState.requestList(target, query, filter, offset, limit);
    }

    private List<MatchBrief> items() {
        var v = view();
        return v == null ? List.of() : v.items();
    }

    private MatchBrief selected() {
        for (MatchBrief b : items()) if (b.id.equals(selectedId)) return b;
        return null;
    }

    private void syncSelection() {
        long ver = ClientHistoryState.version();
        if (ver == seenVersion) return;
        seenVersion = ver;
        listAt = System.currentTimeMillis();
        var v = view();
        if (v == null) return;
        // Сторінку за межами списку (після фільтра) повертаємо на початок.
        if (v.items().isEmpty() && v.total() > 0 && offset > 0) {
            offset = 0;
            return;
        }
        // Вибір лише за кліком/стрілками: якщо вибраного матча на новій сторінці нема — знімаємо вибір.
        if (selected() == null) selectedId = "";
    }

    private void resetPaging() {
        offset = 0;
        selectedId = "";
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.currentTimeMillis();
        if (openedAt == 0L) openedAt = now;
        float dt = UiAnim.frameDt(clock);
        k = Math.max(0.75f, Math.min(1.6f, height / 540f));
        float open = ease(now - openedAt, 0, 450);

        int m = Math.round(14 * k);
        int topH = Math.round(40 * k);
        int bodyTop = m + topH;
        int bodyH = height - bodyTop - m;
        int gap = Math.round(14 * k);
        int dossierW = Math.min(Math.round(width * 0.34f), Math.round(380 * k));
        // Досьє з'являється лише коли матч вибрано: без вибору картки з маніяком нема взагалі,
        // а список займає всю ширину. Ширина списку плавно стискається на час виїзду.
        dossierT = UiAnim.approach(dossierT, selected() != null ? 1f : 0f, 10f, dt);
        float slideIn = UiAnim.easeOutCubic(UiAnim.clamp01(dossierT));
        int listW = width - 2 * m - Math.round((gap + dossierW) * slideIn);
        int pagerH = Math.round(24 * k);
        int cardH = Math.round(54 * k);
        int cardGap = Math.round(6 * k);
        int rows = Math.max(3, Math.min(MAX_ROWS, (bodyH - pagerH - cardGap) / (cardH + cardGap)));
        limit = rows;

        ensureData(now);
        syncSelection();

        MatchBrief sel = selected();
        int accent = sel == null ? HistoryUi.SILVER : HistoryUi.resultColor(sel.survivorsWon, sel.end);

        // Фон.
        g.fill(0, 0, width, height, HistoryUi.a(0xFF020304, 0.9f * open));
        g.fillGradient(0, 0, width, Math.round(height * 0.6f), HistoryUi.a(accent, 0.13f * open), 0x00000000);
        g.fillGradient(0, Math.round(height * 0.7f), width, height, 0x00000000, HistoryUi.a(0xFF000000, 0.55f * open));
        drawDust(g, now, accent, open);

        drawTopBar(g, m, m, width - 2 * m, topH, mx, my, dt, accent, open);
        drawList(g, m, bodyTop, listW, bodyH, pagerH, cardH, cardGap, rows, mx, my, dt, now, open);
        if (sel != null && slideIn > 0.01f) {
            drawDossier(g, m + listW + gap, bodyTop, dossierW, bodyH, sel, accent, mx, my, dt, now, open * slideIn);
        } else {
            btnRect[B_OPEN][2] = 0;
        }

        super.render(g, mx, my, pt);
    }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    private void drawDust(GuiGraphics g, long now, int accent, float open) {
        for (int i = 0; i < 26; i++) {
            float sx = hash(i * 3L + 1), sp = 0.012f + 0.03f * hash(i * 3L + 2), ph = hash(i * 3L + 3);
            float fy = 1f - ((now / 1000f * sp + ph) % 1f);
            int px = Math.round(sx * width), py = Math.round(fy * height);
            float tw = 0.5f + 0.5f * (float) Math.sin(now / 800.0 + i * 1.7);
            g.fill(px, py, px + 1, py + 1, HistoryUi.a(accent, (0.08f + 0.18f * tw) * open));
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
        String overline = tr(adminArchive() ? "maniacmod.history.overline.admin" : "maniacmod.history.overline.player");
        HistoryUi.text(g, font, overline, x, y + 2 * k, 0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        String title = adminArchive() ? tr("maniacmod.history.title")
            : tr("maniacmod.history.title.player", targetName.isEmpty() ? "—" : targetName);
        title = title.toUpperCase(Locale.ROOT);
        HistoryUi.text(g, font, title, x, y + 13 * k, 1.6f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, open), 0);
        int lw = Math.round(font.width(title) * 1.6f * k);
        g.fill(x, y + h - 2, x + lw, y + h, HistoryUi.a(accent, open));
        var v = view();
        if (v != null) {
            HistoryUi.text(g, font, tr("maniacmod.history.count", v.total()), x + lw + 10 * k, y + 22 * k, 0.85f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_BODY, open), 0);
        }

        // Назад.
        String back = tr("maniacmod.history.back").toUpperCase(Locale.ROOT);
        int bw = Math.max(Math.round(110 * k), font.width(back) + Math.round(36 * k));
        int bh = Math.round(22 * k);
        int by = y + (h - bh) / 2 - Math.round(2 * k);
        setRect(B_BACK, x + w - bw, by, bw, bh);
        drawBtn(g, B_BACK, back, mx, my, dt, false, accent, open);

        // Фільтри за результатом.
        int fw = Math.round(120 * k);
        int fgap = Math.round(6 * k);
        int fx = x + w - bw - Math.round(16 * k) - 3 * fw - 2 * fgap;
        for (int i = 0; i < 3; i++) {
            setRect(B_F0 + i, fx + i * (fw + fgap), by, fw, bh);
            String label = tr("maniacmod.history.filter." + i).toUpperCase(Locale.ROOT);
            drawBtn(g, B_F0 + i, label, mx, my, dt, filter == i, accent, open);
        }

        // Пошук (лише в адмінському архіві).
        if (adminArchive()) {
            int sw = Math.round(170 * k);
            int sx = fx - Math.round(14 * k) - sw;
            searchRect[0] = sx; searchRect[1] = by; searchRect[2] = sw; searchRect[3] = bh;
            g.fill(sx, by, sx + sw, by + bh, HistoryUi.a(ManiacUiTheme.MENU_PANEL_DARK, open));
            ManiacUiTheme.border1px(g, sx, by, sw, bh, HistoryUi.a(searchFocus
                ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER, open));
            boolean empty = query.isEmpty();
            String shown = empty ? tr("maniacmod.history.search") : query;
            boolean caret = searchFocus && (System.currentTimeMillis() / 500L) % 2L == 0L;
            HistoryUi.text(g, font, HistoryUi.fit(font, shown, k, sw - 14 * k) + (caret && !empty ? "_" : ""),
                sx + 7 * k, by + bh / 2f - 4f * k, k,
                HistoryUi.a(empty ? ManiacUiTheme.TEXT_DIM : ManiacUiTheme.TEXT_TITLE, open), 0);
            if (empty && caret) g.fill(sx + Math.round(6 * k), by + 4, sx + Math.round(6 * k) + 1, by + bh - 4,
                HistoryUi.a(ManiacUiTheme.TEXT_TITLE, open));
        } else {
            searchRect[2] = 0;
        }
    }

    // ── Список ───────────────────────────────────────────────────────────

    private void drawList(GuiGraphics g, int x, int y, int w, int h, int pagerH, int cardH, int cardGap, int rows,
                          int mx, int my, float dt, long now, float open) {
        var v = view();
        List<MatchBrief> items = items();
        int listBottom = y + h - pagerH;

        if (v == null) {
            drawShimmer(g, x, y, w, cardH, cardGap, rows, now, open);
        } else if (items.isEmpty()) {
            HistoryUi.text(g, font, tr("maniacmod.history.empty"), x + w / 2f, y + h / 2f - 10 * k, 1.1f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_BODY, open), 1);
            HistoryUi.text(g, font, tr(adminArchive() ? "maniacmod.history.empty.admin"
                    : "maniacmod.history.empty.player"), x + w / 2f, y + h / 2f + 6 * k, 0.8f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 1);
        }

        for (int i = 0; i < MAX_ROWS; i++) cardRect[i][2] = 0;
        for (int i = 0; i < items.size() && i < rows; i++) {
            MatchBrief b = items.get(i);
            float ea = ease(now - listAt, i * 55L, 380);
            int slide = Math.round((1f - ea) * 26 * k);
            int cy = y + i * (cardH + cardGap);
            if (cy + cardH > listBottom) break;
            cardRect[i][0] = x; cardRect[i][1] = cy; cardRect[i][2] = w; cardRect[i][3] = cardH;
            boolean hover = HistoryUi.inside(mx, my, cardRect[i]);
            cardHover[i] = UiAnim.approach(cardHover[i], hover ? 1f : 0f, 14f, dt);
            drawCard(g, b, x - slide, cy, w, cardH, cardHover[i], b.id.equals(selectedId), ea * open, now);
        }

        // Пейджер.
        if (v != null && v.total() > 0) {
            int pages = Math.max(1, (v.total() + limit - 1) / limit);
            int page = Math.min(pages, v.offset() / limit + 1);
            int py = y + h - pagerH + Math.round(2 * k);
            int bw = Math.round(34 * k), bh = pagerH - Math.round(4 * k);
            setRect(B_PREV, x, py, bw, bh);
            setRect(B_NEXT, x + w - bw, py, bw, bh);
            drawBtn(g, B_PREV, "<", mx, my, dt, false, HistoryUi.SILVER, open);
            drawBtn(g, B_NEXT, ">", mx, my, dt, false, HistoryUi.SILVER, open);
            HistoryUi.text(g, font, tr("maniacmod.history.page", page, pages), x + w / 2f, py + bh / 2f - 4 * k, 0.9f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_BODY, open), 1);
        } else {
            btnRect[B_PREV][2] = 0;
            btnRect[B_NEXT][2] = 0;
        }
    }

    private void drawShimmer(GuiGraphics g, int x, int y, int w, int cardH, int cardGap, int rows, long now, float open) {
        for (int i = 0; i < Math.min(rows, 4); i++) {
            int cy = y + i * (cardH + cardGap);
            g.fill(x, cy, x + w, cy + cardH, HistoryUi.a(ManiacUiTheme.SLOT_FILL, open));
            float s = ((now / 900f) + i * 0.15f) % 1f;
            int sx = x + Math.round(s * (w + 80 * k)) - Math.round(80 * k);
            g.fillGradient(Math.max(x, sx), cy, Math.min(x + w, sx + Math.round(60 * k)), cy + cardH,
                0x00FFFFFF, HistoryUi.a(0xFFFFFFFF, 0.05f * open));
            ManiacUiTheme.border1px(g, x, cy, w, cardH, HistoryUi.a(ManiacUiTheme.BORDER, open));
        }
        HistoryUi.text(g, font, tr("maniacmod.history.loading"), x + w / 2f, y + 4 * (cardH + cardGap) + 6 * k, 0.9f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 1);
    }

    private void drawCard(GuiGraphics g, MatchBrief b, int x, int y, int w, int h, float hv, boolean selected,
                          float alpha, long now) {
        int rc = HistoryUi.resultColor(b.survivorsWon, b.end);
        g.fill(x, y, x + w, y + h, HistoryUi.a(HistoryUi.mix(ManiacUiTheme.SLOT_FILL, 0xFF171B1E, hv), alpha));
        if (selected) {
            float p = 0.6f + 0.4f * (float) Math.sin(now / 420.0);
            g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, HistoryUi.a(rc, 0f), HistoryUi.a(rc, 0.14f * p * alpha));
        }
        ManiacUiTheme.border1px(g, x, y, w, h, HistoryUi.a(selected
            ? HistoryUi.mix(ManiacUiTheme.BORDER_BRIGHT, rc, 0.5f) : HistoryUi.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, hv),
            alpha));
        g.fill(x, y, x + Math.round(3 * k) + (selected ? 1 : 0), y + h, HistoryUi.a(rc, alpha));
        if (selected) UiFx.brackets(g, x, y, w, h, HistoryUi.a(rc, 0.9f * alpha), Math.round(7 * k), 0f, 0);

        int pad = Math.round(12 * k);
        float ty = y + 6 * k;
        // Ряд 1: результат + «коли».
        HistoryUi.text(g, font, HistoryUi.resultTitle(b.survivorsWon, b.end).toUpperCase(Locale.ROOT),
            x + pad, ty, 0.95f * k, HistoryUi.a(rc, alpha), 0);
        HistoryUi.text(g, font, HistoryUi.date(b.startedAtMs) + "  " + HistoryUi.time(b.startedAtMs)
                + "  ·  " + HistoryUi.ago(b.startedAtMs),
            x + w - pad, ty, 0.75f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 2);

        // Ряд 2: маніяк · час · стадія.
        float ty2 = y + 19 * k;
        String info = HistoryUi.maniacName(b.maniacId) + "  ·  " + HistoryUi.clock(b.playedSeconds)
            + "  ·  " + tr("maniacmod.history.card.gens", b.generatorsDone, b.generatorsRequired);
        HistoryUi.text(g, font, HistoryUi.fit(font, info, 0.85f * k, w * 0.62f), x + pad, ty2, 0.85f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_BODY, alpha), 0);
        drawStagePips(g, x + w - pad - Math.round(4 * 12 * k), Math.round(ty2) + 1, b.stage, alpha, 1f);

        // Ряд 3: голови учасників.
        int face = Math.round(13 * k);
        int fx = x + pad;
        int fy = y + h - face - Math.round(5 * k);
        UUID me = target;
        for (MatchBrief.Part p : b.players) {
            if (fx + face > x + w * 0.64f) break;
            PlayerFaceRenderer.draw(g, HistoryUi.skinOf(p.id), fx, fy, face);
            int frame = p.role == MatchRecord.Role.MANIAC ? ManiacUiTheme.BAR_FILL_DANGER
                : HistoryUi.outcomeColor(p.outcome);
            ManiacUiTheme.border1px(g, fx - 1, fy - 1, face + 2, face + 2, HistoryUi.a(frame, 0.85f * alpha));
            if (me != null && me.equals(p.id)) {
                g.fill(fx, fy + face + 1, fx + face, fy + face + 2, HistoryUi.a(HistoryUi.SILVER, alpha));
            }
            if (alpha < 0.98f) g.fill(fx, fy, fx + face, fy + face, HistoryUi.a(0xFF000000, 1f - alpha));
            fx += face + Math.round(4 * k);
        }
        int shown = Math.max(0, (int) ((fx - x - pad) / (face + 4 * k)));
        if (shown < b.players.size()) {
            HistoryUi.text(g, font, "+" + (b.players.size() - shown), fx, fy + face / 2f - 3 * k, 0.8f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);
        }

        // Праворуч внизу: у моїй історії — моя роль/результат/XP; в архіві — лічильник гравців.
        MatchBrief.Part mine = target == null ? null : b.part(target);
        if (mine != null) {
            String role = tr(mine.role == MatchRecord.Role.MANIAC ? "maniacmod.history.role.maniac"
                : "maniacmod.history.role.survivor").toUpperCase(Locale.ROOT);
            String line = role + "  ·  " + HistoryUi.outcomeName(mine.outcome).toUpperCase(Locale.ROOT)
                + (mine.xp > 0 ? "  ·  +" + mine.xp + " XP" : "");
            HistoryUi.text(g, font, line, x + w - pad, fy + face / 2f - 3 * k, 0.8f * k,
                HistoryUi.a(HistoryUi.outcomeColor(mine.outcome), alpha), 2);
        } else {
            HistoryUi.text(g, font, tr("maniacmod.history.card.players", b.players.size()), x + w - pad,
                fy + face / 2f - 3 * k, 0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 2);
        }
    }

    /** Чотири «лампочки» стадій: горять ті, що матч пройшов. */
    private void drawStagePips(GuiGraphics g, int x, int y, String stage, float alpha, float scale) {
        int reached = HistoryUi.stageIndex(stage);
        int s = Math.max(4, Math.round(8 * k * scale));
        int gap = Math.max(2, Math.round(4 * k * scale));
        for (int i = 0; i < HistoryUi.STAGES.length; i++) {
            int px = x + i * (s + gap);
            boolean lit = i <= reached;
            g.fill(px, y, px + s, y + Math.max(3, s / 2),
                HistoryUi.a(lit ? HistoryUi.mix(HistoryUi.WARN, HistoryUi.BLOOD, i / 3f) : ManiacUiTheme.BORDER, alpha));
        }
    }

    // ── Досьє ────────────────────────────────────────────────────────────

    private void drawDossier(GuiGraphics g, int x, int y, int w, int h, MatchBrief sel, int accent, int mx, int my,
                             float dt, long now, float open) {
        int bustH = Math.round(h * 0.54f);
        bust.render(g, font, x, y, w, bustH, HistoryUi.archetype(sel.maniacId), sel.maniacId, accent, now, mx, my, k);

        // Плашка з іменем маніяка поверх туману.
        HistoryUi.text(g, font, tr("maniacmod.history.role.maniac").toUpperCase(Locale.ROOT), x + 12 * k,
            y + bustH - 36 * k, 0.75f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        HistoryUi.text(g, font, HistoryUi.fit(font, HistoryUi.maniacName(sel.maniacId).toUpperCase(Locale.ROOT),
                1.5f * k, w - 24 * k), x + 12 * k, y + bustH - 25 * k, 1.5f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_TITLE, open), 0);

        int ty = y + bustH + Math.round(8 * k);
        int panelH = h - bustH - Math.round(8 * k);
        g.fill(x, ty, x + w, ty + panelH, HistoryUi.a(ManiacUiTheme.PANEL_BG, 0.85f * open));
        ManiacUiTheme.border1px(g, x, ty, w, panelH, HistoryUi.a(ManiacUiTheme.BORDER, open));
        g.fill(x, ty, x + w, ty + 2, HistoryUi.a(accent, open));

        int pad = Math.round(12 * k);
        float ly = ty + 10 * k;
        HistoryUi.text(g, font, HistoryUi.resultTitle(sel.survivorsWon, sel.end).toUpperCase(Locale.ROOT), x + pad, ly,
            1.15f * k, HistoryUi.a(accent, open), 0);
        ly += 15 * k;
        HistoryUi.text(g, font, HistoryUi.endName(sel.end), x + pad, ly, 0.8f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        ly += 14 * k;

        String[][] rows = {
            {"maniacmod.history.when", HistoryUi.date(sel.startedAtMs) + " " + HistoryUi.time(sel.startedAtMs)},
            {"maniacmod.history.played", HistoryUi.clock(sel.playedSeconds) + "  (" + HistoryUi.clock(sel.totalSeconds) + ")"},
            {"maniacmod.history.players", String.valueOf(sel.players.size())},
            {"maniacmod.history.generators", sel.generatorsDone + " / " + sel.generatorsRequired},
            {"maniacmod.history.escaped", sel.escaped + " / " + sel.survivorTotal},
            {"maniacmod.history.stage", HistoryUi.stageName(sel.stage)},
        };
        for (String[] r : rows) {
            HistoryUi.text(g, font, tr(r[0]), x + pad, ly, 0.82f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
            HistoryUi.text(g, font, HistoryUi.fit(font, r[1], 0.9f * k, w * 0.58f), x + w - pad, ly, 0.9f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_BODY, open), 2);
            ly += 12 * k;
        }

        // Трек стадій.
        ly += 4 * k;
        drawStageTrack(g, x + pad, Math.round(ly), w - 2 * pad, sel.stage, open);

        // Кнопка «Відкрити».
        int bh = Math.round(24 * k);
        int by = ty + panelH - bh - Math.round(10 * k);
        setRect(B_OPEN, x + pad, by, w - 2 * pad, bh);
        drawBtn(g, B_OPEN, tr("maniacmod.history.open").toUpperCase(Locale.ROOT), mx, my, dt, true, accent, open);
    }

    private void drawStageTrack(GuiGraphics g, int x, int y, int w, String stage, float open) {
        int reached = HistoryUi.stageIndex(stage);
        int n = HistoryUi.STAGES.length;
        int seg = (w - (n - 1) * Math.round(4 * k)) / n;
        for (int i = 0; i < n; i++) {
            int sx = x + i * (seg + Math.round(4 * k));
            boolean lit = i <= reached;
            int col = lit ? HistoryUi.mix(HistoryUi.WARN, HistoryUi.BLOOD, i / 3f) : ManiacUiTheme.BORDER;
            g.fill(sx, y, sx + seg, y + Math.round(4 * k), HistoryUi.a(col, open));
            HistoryUi.text(g, font, HistoryUi.fit(font, HistoryUi.stageName(HistoryUi.STAGES[i]), 0.62f * k, seg),
                sx, y + 7 * k, 0.62f * k, HistoryUi.a(lit ? ManiacUiTheme.TEXT_BODY : ManiacUiTheme.TEXT_DIM, open), 0);
        }
    }

    // ── Кнопки ───────────────────────────────────────────────────────────

    private void setRect(int i, int x, int y, int w, int h) {
        btnRect[i][0] = x; btnRect[i][1] = y; btnRect[i][2] = w; btnRect[i][3] = h;
    }

    private void drawBtn(GuiGraphics g, int i, String label, int mx, int my, float dt, boolean active, int accent,
                         float open) {
        int[] r = btnRect[i];
        boolean hover = HistoryUi.inside(mx, my, r);
        btnHover[i] = UiAnim.approach(btnHover[i], hover ? 1f : 0f, 16f, dt);
        float hv = btnHover[i];
        int slant = 5, sw = r[2] - slant;
        ManiacMenuTheme.slantFill(g, r[0], r[1], sw, r[3], slant,
            HistoryUi.a(active ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, open));
        ManiacMenuTheme.slantFill(g, r[0], r[1], Math.round(sw * UiAnim.easeOutCubic(hv)), r[3], slant,
            HistoryUi.a(ManiacUiTheme.MENU_ACCENT_BROWN, open));
        ManiacMenuTheme.slantBorder(g, r[0], r[1], sw, r[3], slant, HistoryUi.a(
            HistoryUi.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, Math.max(hv, active ? 0.6f : 0f)), open));
        if (active) g.fill(r[0] + slant, r[1] + r[3] - 2, r[0] + slant + sw, r[1] + r[3], HistoryUi.a(accent, open));
        int tc = active ? ManiacUiTheme.TEXT_ACCENT : HistoryUi.mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_BODY, hv);
        HistoryUi.text(g, font, HistoryUi.fit(font, label, k, sw - 8), r[0] + slant / 2f + sw / 2f,
            r[1] + r[3] / 2f - 4f * k, k, HistoryUi.a(tc, open), 1);
    }

    private static String tr(String key, Object... args) { return HistoryUi.tr(key, args); }

    // ── Ввід ─────────────────────────────────────────────────────────────

    private void openSelected() {
        MatchBrief sel = selected();
        if (sel == null) return;
        UiAnim.playClick();
        Minecraft.getInstance().setScreen(new MatchDetailScreen(this, sel, target, targetName));
    }

    private void page(int dir) {
        var v = view();
        if (v == null) return;
        int next = offset + dir * limit;
        if (next < 0 || next >= v.total()) return;
        offset = next;
        selectedId = "";
        UiAnim.playClick();
    }

    private void moveSelection(int dir) {
        List<MatchBrief> items = items();
        if (items.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(selectedId)) idx = i;
        int n = idx < 0 ? 0 : Math.max(0, Math.min(items.size() - 1, idx + dir));
        if (n != idx) {
            selectedId = items.get(n).id;
            UiAnim.playClick();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        searchFocus = searchRect[2] > 0 && HistoryUi.inside(mx, my, searchRect);
        if (searchFocus) return true;
        if (HistoryUi.inside(mx, my, btnRect[B_BACK])) { UiAnim.playClick(); onClose(); return true; }
        for (int i = 0; i < 3; i++) {
            if (HistoryUi.inside(mx, my, btnRect[B_F0 + i]) && filter != i) {
                filter = i;
                resetPaging();
                UiAnim.playClick();
                return true;
            }
        }
        if (btnRect[B_PREV][2] > 0 && HistoryUi.inside(mx, my, btnRect[B_PREV])) { page(-1); return true; }
        if (btnRect[B_NEXT][2] > 0 && HistoryUi.inside(mx, my, btnRect[B_NEXT])) { page(1); return true; }
        if (btnRect[B_OPEN][2] > 0 && HistoryUi.inside(mx, my, btnRect[B_OPEN])) { openSelected(); return true; }
        List<MatchBrief> items = items();
        for (int i = 0; i < items.size() && i < MAX_ROWS; i++) {
            if (cardRect[i][2] > 0 && HistoryUi.inside(mx, my, cardRect[i])) {
                if (items.get(i).id.equals(selectedId)) openSelected();
                else { selectedId = items.get(i).id; UiAnim.playClick(); }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (delta != 0) page(delta < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (searchFocus) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                searchFocus = false;
                queryDirtyAt = 0L;
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
                resetPaging();
                queryDirtyAt = System.currentTimeMillis();
                return true;
            }
            return true;   // поки пишемо — гарячі клавіші екрана не чіпаємо
        }
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> { page(-1); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { page(1); return true; }
            case GLFW.GLFW_KEY_UP -> { moveSelection(-1); return true; }
            case GLFW.GLFW_KEY_DOWN -> { moveSelection(1); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { openSelected(); return true; }
            default -> { }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!searchFocus) return super.charTyped(c, mods);
        if (c >= 32 && c != 127 && query.length() < SEARCH_MAX) {
            query += c;
            resetPaging();
            queryDirtyAt = System.currentTimeMillis();
        }
        return true;
    }
}

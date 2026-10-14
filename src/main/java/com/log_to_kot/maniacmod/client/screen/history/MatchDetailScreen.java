package com.log_to_kot.maniacmod.client.screen.history;

import com.log_to_kot.maniacmod.client.history.ClientHistoryState;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.history.HistoryEventType;
import com.log_to_kot.maniacmod.history.MatchBrief;
import com.log_to_kot.maniacmod.history.MatchRecord;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Один матч у подробицях. Чотири вкладки (←/→, Tab або клік):
 *
 * <ol>
 *   <li><b>Огляд</b> — результат, смуга часу всього матчу з маркерами подій, до якої стадії дійшла гра
 *       (і скільки в кожній пробули), цілі, зведені лічильники;</li>
 *   <li><b>Гравці</b> — кожен учасник: роль, результат, КОЛИ й ЧОМУ вибув (фаза, причина, хто добив),
 *       удари/падіння/гаки/підйоми/ремонт; клік розгортає пастки;</li>
 *   <li><b>Хронологія</b> — усі події матчу по порядку, з фільтрами;</li>
 *   <li><b>Досвід</b> — за що кожен отримав XP, ранг і рівень до/після.</li>
 * </ol>
 *
 * <p>Справа — 3D-бюст маніяка цього матчу ({@link ManiacBustWidget}) і короткий підсумок. Дані —
 * {@link ClientHistoryState#detail}: повний запис прилітає окремою відповіддю, а до того екран показує
 * «завантаження»; відмова сервера (нема прав/матчу) — окремий напис, не вічне очікування.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MatchDetailScreen extends Screen {

    private enum Tab { OVERVIEW, PLAYERS, TIMELINE, XP }

    private final Screen parent;
    private final MatchBrief brief;
    private final UUID context;
    private final String contextName;

    private Tab tab = Tab.OVERVIEW;
    private long openedAt;
    private long tabAt;
    private float k = 1f;
    private final long[] clock = {0L};
    private final ManiacBustWidget bust = new ManiacBustWidget();

    private float scroll;
    private float scrollTarget;
    private float contentH;
    private int viewH;
    private int expanded = -1;
    private int timelineFilter;

    private final float[] tabHover = new float[4];
    private final int[][] tabRect = new int[4][4];
    private final int[] backRect = new int[4];
    private float backHover;
    private final int[][] chipRect = new int[4][4];
    private final java.util.Map<Integer, int[]> rowRects = new java.util.HashMap<>();

    public MatchDetailScreen(Screen parent, MatchBrief brief, UUID context, String contextName) {
        super(Component.translatable("maniacmod.history.detail"));
        this.parent = parent;
        this.brief = brief;
        this.context = context;
        this.contextName = contextName == null ? "" : contextName;
    }

    @Override
    protected void init() {
        super.init();
        ClientHistoryState.requestDetail(brief.id, context);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    private MatchRecord record() {
        var d = ClientHistoryState.detail(brief.id);
        return d == null ? null : d.record();
    }

    private boolean denied() {
        var d = ClientHistoryState.detail(brief.id);
        return d != null && d.record() == null;
    }

    private static String tr(String key, Object... args) { return HistoryUi.tr(key, args); }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    private void setTab(Tab t) {
        if (t == tab) return;
        tab = t;
        tabAt = System.currentTimeMillis();
        scroll = 0f;
        scrollTarget = 0f;
        expanded = -1;
        UiAnim.playClick();
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.currentTimeMillis();
        if (openedAt == 0L) { openedAt = now; tabAt = now; }
        float dt = UiAnim.frameDt(clock);
        k = Math.max(0.75f, Math.min(1.6f, height / 540f));
        float open = ease(now - openedAt, 0, 450);
        MatchRecord r = record();

        int accent = HistoryUi.resultColor(brief.survivorsWon, r == null ? brief.end : r.end);
        g.fill(0, 0, width, height, HistoryUi.a(0xFF020304, 0.92f * open));
        g.fillGradient(0, 0, width, Math.round(height * 0.6f), HistoryUi.a(accent, 0.13f * open), 0x00000000);
        g.fillGradient(0, Math.round(height * 0.7f), width, height, 0x00000000, HistoryUi.a(0xFF000000, 0.55f * open));

        int m = Math.round(14 * k);
        int topH = Math.round(40 * k);
        int bodyTop = m + topH;
        int bodyH = height - bodyTop - m;
        int gap = Math.round(14 * k);
        int sideW = Math.min(Math.round(width * 0.27f), Math.round(300 * k));
        int mainW = width - 2 * m - gap - sideW;

        drawTopBar(g, m, m, width - 2 * m, topH, mx, my, dt, accent, open);
        drawSide(g, m + mainW + gap, bodyTop, sideW, bodyH, r, accent, mx, my, now, open);

        int mainX = m, mainY = bodyTop;
        if (r == null) {
            drawPlaceholder(g, mainX, mainY, mainW, bodyH, now, open);
        } else {
            scroll = UiAnim.approach(scroll, scrollTarget, 14f, dt);
            float tabOpen = ease(now - tabAt, 0, 380);
            viewH = bodyH;
            g.enableScissor(mainX, mainY, mainX + mainW, mainY + bodyH);
            int oy = Math.round(-scroll + (1f - tabOpen) * 10 * k);
            float used = switch (tab) {
                case OVERVIEW -> drawOverview(g, r, mainX, mainY + oy, mainW, mx, my, now, open * tabOpen, accent);
                case PLAYERS -> drawPlayers(g, r, mainX, mainY + oy, mainW, mx, my, now, open * tabOpen);
                case TIMELINE -> drawTimeline(g, r, mainX, mainY + oy, mainW, mx, my, open * tabOpen);
                case XP -> drawXp(g, r, mainX, mainY + oy, mainW, now, open * tabOpen);
            };
            g.disableScissor();
            contentH = used;
            float maxScroll = Math.max(0f, contentH - bodyH);
            if (scrollTarget > maxScroll) scrollTarget = maxScroll;
            if (scrollTarget < 0f) scrollTarget = 0f;
            drawScrollbar(g, mainX + mainW - 3, mainY, bodyH, maxScroll, open);
        }
        super.render(g, mx, my, pt);
    }

    private void drawScrollbar(GuiGraphics g, int x, int y, int h, float maxScroll, float open) {
        if (maxScroll <= 0f) return;
        float frac = h / (h + maxScroll);
        int th = Math.max(16, Math.round(h * frac));
        int ty = y + Math.round((h - th) * (scroll / maxScroll));
        g.fill(x, y, x + 2, y + h, HistoryUi.a(ManiacUiTheme.BORDER, 0.5f * open));
        g.fill(x, ty, x + 2, ty + th, HistoryUi.a(ManiacUiTheme.BORDER_BRIGHT, open));
    }

    private void drawPlaceholder(GuiGraphics g, int x, int y, int w, int h, long now, float open) {
        String msg = denied() ? tr("maniacmod.history.denied") : tr("maniacmod.history.loading");
        g.fill(x, y, x + w, y + h, HistoryUi.a(ManiacUiTheme.PANEL_BG, 0.7f * open));
        ManiacUiTheme.border1px(g, x, y, w, h, HistoryUi.a(ManiacUiTheme.BORDER, open));
        if (!denied()) {
            float s = (now % 1400L) / 1400f;
            int sx = x + Math.round(s * w);
            g.fillGradient(Math.max(x, sx - 60), y, Math.min(x + w, sx + 60), y + h,
                0x00FFFFFF, HistoryUi.a(0xFFFFFFFF, 0.04f * open));
        }
        HistoryUi.text(g, font, msg, x + w / 2f, y + h / 2f - 4 * k, 1.1f * k,
            HistoryUi.a(denied() ? HistoryUi.BLOOD : ManiacUiTheme.TEXT_BODY, open), 1);
    }

    // ── Верхня смуга ─────────────────────────────────────────────────────

    private void drawTopBar(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float dt, int accent, float open) {
        HistoryUi.text(g, font, tr("maniacmod.history.detail.overline", brief.id), x, y + 2 * k, 0.8f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        String title = (HistoryUi.date(brief.startedAtMs) + "  " + HistoryUi.time(brief.startedAtMs));
        HistoryUi.text(g, font, title, x, y + 13 * k, 1.6f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, open), 0);
        int lw = Math.round(font.width(title) * 1.6f * k);
        g.fill(x, y + h - 2, x + lw, y + h, HistoryUi.a(accent, open));

        // Вкладки.
        Tab[] tabs = Tab.values();
        int tw = Math.round(112 * k), th = Math.round(22 * k), tg = Math.round(6 * k);
        int total = tabs.length * tw + (tabs.length - 1) * tg;
        int tx0 = x + w / 2 - total / 2 + Math.round(40 * k);
        int ty = y + (h - th) / 2 - Math.round(2 * k);
        for (int i = 0; i < tabs.length; i++) {
            int tx = tx0 + i * (tw + tg);
            tabRect[i][0] = tx; tabRect[i][1] = ty; tabRect[i][2] = tw; tabRect[i][3] = th;
            boolean active = tabs[i] == tab;
            boolean hover = HistoryUi.inside(mx, my, tabRect[i]);
            tabHover[i] = UiAnim.approach(tabHover[i], hover ? 1f : 0f, 16f, dt);
            int slant = 5, sw = tw - slant;
            ManiacMenuTheme.slantFill(g, tx, ty, sw, th, slant,
                HistoryUi.a(active ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, open));
            ManiacMenuTheme.slantBorder(g, tx, ty, sw, th, slant, HistoryUi.a(HistoryUi.mix(ManiacUiTheme.BORDER,
                ManiacUiTheme.BORDER_BRIGHT, Math.max(tabHover[i], active ? 0.5f : 0f)), open));
            String label = tr("maniacmod.history.tab." + tabs[i].name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
            int tc = active ? ManiacUiTheme.TEXT_ACCENT
                : HistoryUi.mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_BODY, tabHover[i]);
            HistoryUi.text(g, font, label, tx + slant / 2f + sw / 2f, ty + th / 2f - 4f * k, k, HistoryUi.a(tc, open), 1);
        }
        int ux = tabRect[tab.ordinal()][0];
        g.fill(ux, ty + th + Math.round(3 * k), ux + tw - 5, ty + th + Math.round(3 * k) + 2, HistoryUi.a(accent, open));

        // Назад.
        String back = tr("maniacmod.history.back").toUpperCase(Locale.ROOT);
        int bw = Math.max(Math.round(110 * k), font.width(back) + Math.round(36 * k));
        int bh = Math.round(22 * k);
        int bx = x + w - bw;
        backRect[0] = bx; backRect[1] = ty; backRect[2] = bw; backRect[3] = bh;
        backHover = UiAnim.approach(backHover, HistoryUi.inside(mx, my, backRect) ? 1f : 0f, 16f, dt);
        int slant = 5, sw = bw - slant;
        ManiacMenuTheme.slantFill(g, bx, ty, sw, bh, slant, HistoryUi.a(ManiacUiTheme.MENU_PANEL_DARK, open));
        ManiacMenuTheme.slantFill(g, bx, ty, Math.round(sw * UiAnim.easeOutCubic(backHover)), bh, slant,
            HistoryUi.a(ManiacUiTheme.MENU_ACCENT_BROWN, open));
        ManiacMenuTheme.slantBorder(g, bx, ty, sw, bh, slant,
            HistoryUi.a(HistoryUi.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, backHover), open));
        HistoryUi.text(g, font, back, bx + slant / 2f + sw / 2f, ty + bh / 2f - 4f * k, k,
            HistoryUi.a(HistoryUi.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, backHover), open), 1);
    }

    // ── Права колонка: бюст + підсумок ───────────────────────────────────

    private void drawSide(GuiGraphics g, int x, int y, int w, int h, MatchRecord r, int accent, int mx, int my,
                          long now, float open) {
        String maniacId = r == null ? brief.maniacId : r.maniacId;
        int bustH = Math.round(h * 0.56f);
        bust.render(g, font, x, y, w, bustH, HistoryUi.archetype(maniacId), maniacId, accent, now, mx, my, k);
        HistoryUi.text(g, font, tr("maniacmod.history.role.maniac").toUpperCase(Locale.ROOT), x + 12 * k,
            y + bustH - 36 * k, 0.75f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        HistoryUi.text(g, font, HistoryUi.fit(font, HistoryUi.maniacName(maniacId).toUpperCase(Locale.ROOT), 1.5f * k,
            w - 24 * k), x + 12 * k, y + bustH - 25 * k, 1.5f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, open), 0);

        int ty = y + bustH + Math.round(8 * k);
        int ph = h - bustH - Math.round(8 * k);
        g.fill(x, ty, x + w, ty + ph, HistoryUi.a(ManiacUiTheme.PANEL_BG, 0.85f * open));
        ManiacUiTheme.border1px(g, x, ty, w, ph, HistoryUi.a(ManiacUiTheme.BORDER, open));
        g.fill(x, ty, x + w, ty + 2, HistoryUi.a(accent, open));
        int pad = Math.round(12 * k);
        float ly = ty + 10 * k;
        MatchRecord.End end = r == null ? brief.end : r.end;
        HistoryUi.text(g, font, HistoryUi.resultTitle(brief.survivorsWon, end).toUpperCase(Locale.ROOT), x + pad, ly,
            1.15f * k, HistoryUi.a(accent, open), 0);
        ly += 15 * k;
        HistoryUi.text(g, font, HistoryUi.endName(end), x + pad, ly, 0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        ly += 15 * k;
        String[][] rows = {
            {"maniacmod.history.played", HistoryUi.clock(brief.playedSeconds)},
            {"maniacmod.history.players", String.valueOf(brief.players.size())},
            {"maniacmod.history.generators", brief.generatorsDone + " / " + brief.generatorsRequired},
            {"maniacmod.history.escaped", brief.escaped + " / " + brief.survivorTotal},
            {"maniacmod.history.stage", HistoryUi.stageName(brief.stage)},
        };
        for (String[] row : rows) {
            HistoryUi.text(g, font, tr(row[0]), x + pad, ly, 0.82f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
            HistoryUi.text(g, font, HistoryUi.fit(font, row[1], 0.9f * k, w * 0.55f), x + w - pad, ly, 0.9f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_BODY, open), 2);
            ly += 12 * k;
        }
        if (context != null && !contextName.isEmpty()) {
            HistoryUi.text(g, font, tr("maniacmod.history.viewing", contextName), x + pad, ty + ph - 14 * k, 0.75f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, open), 0);
        }
    }

    // ── Вкладка 1: ОГЛЯД ─────────────────────────────────────────────────

    private float drawOverview(GuiGraphics g, MatchRecord r, int x, int y, int w, int mx, int my, long now,
                               float alpha, int accent) {
        int pad = Math.round(12 * k);
        float cy = y;

        // Банер результату.
        int bh = Math.round(56 * k);
        panel(g, x, (int) cy, w, bh, accent, alpha);
        HistoryUi.text(g, font, HistoryUi.resultTitle(r.survivorsWon, r.end).toUpperCase(Locale.ROOT), x + pad,
            cy + 10 * k, 1.8f * k, HistoryUi.a(accent, alpha), 0);
        HistoryUi.text(g, font, HistoryUi.endName(r.end) + "  ·  " + tr("maniacmod.history.overview.reached",
            HistoryUi.stageName(r.stage)), x + pad, cy + 34 * k, 0.9f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, alpha), 0);
        HistoryUi.text(g, font, HistoryUi.clock(r.playedSeconds), x + w - pad, cy + 12 * k, 1.8f * k,
            HistoryUi.a(ManiacUiTheme.TEXT_TITLE, alpha), 2);
        HistoryUi.text(g, font, tr("maniacmod.history.overview.total", HistoryUi.clock(r.totalSeconds)),
            x + w - pad, cy + 36 * k, 0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 2);
        cy += bh + 10 * k;

        // Смуга часу матчу.
        cy = drawMatchTrack(g, r, x, (int) cy, w, mx, my, alpha) + 10 * k;

        // Стадії.
        cy = drawStages(g, r, x, (int) cy, w, now, alpha) + 10 * k;

        // Цілі.
        cy = drawObjectives(g, r, x, (int) cy, w, alpha) + 10 * k;

        // Лічильники.
        cy = drawTotals(g, r, x, (int) cy, w, now, alpha);
        return cy - y + 8 * k;
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h, int accent, float alpha) {
        g.fill(x, y, x + w, y + h, HistoryUi.a(ManiacUiTheme.PANEL_BG, 0.8f * alpha));
        ManiacUiTheme.border1px(g, x, y, w, h, HistoryUi.a(ManiacUiTheme.BORDER, alpha));
        g.fill(x, y, x + w, y + 2, HistoryUi.a(accent, alpha));
    }

    private static int phaseColor(String phase) {
        return switch (phase) {
            case "HUNT" -> HistoryUi.SILVER;
            case "POWERED" -> HistoryUi.CYAN;
            case "HELI_WAIT" -> HistoryUi.WARN;
            case "FINALE" -> HistoryUi.BLOOD;
            case "ENDING", "VICTORY_SCENE" -> 0xFF56606E;
            default -> 0xFF2A3038;
        };
    }

    /** Горизонтальна смуга всього матчу: кольорові відрізки фаз + маркери ключових подій (наведи — підказка). */
    private float drawMatchTrack(GuiGraphics g, MatchRecord r, int x, int y, int w, int mx, int my, float alpha) {
        int pad = Math.round(12 * k);
        int ph = Math.round(78 * k);
        panel(g, x, y, w, ph, HistoryUi.SILVER, alpha);
        HistoryUi.text(g, font, tr("maniacmod.history.overview.timeline").toUpperCase(Locale.ROOT), x + pad, y + 8 * k,
            0.85f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);

        int bx = x + pad, bw = w - 2 * pad, by = y + Math.round(26 * k), bh = Math.round(12 * k);
        long total = 1L;
        for (MatchRecord.PhaseSpan s : r.phases) total = Math.max(total, s.endTick);
        total = Math.max(total, r.totalSeconds * 20L);
        for (MatchRecord.PhaseSpan s : r.phases) {
            int x0 = bx + Math.round(bw * (s.startTick / (float) total));
            int x1 = bx + Math.round(bw * (s.endTick / (float) total));
            if (x1 <= x0) x1 = x0 + 1;
            g.fill(x0, by, x1, by + bh, HistoryUi.a(phaseColor(s.phase), 0.85f * alpha));
            g.fill(x0, by, x0 + 1, by + bh, HistoryUi.a(0xFF000000, 0.6f * alpha));
        }
        // Початок активної гри.
        if (r.playStartTick >= 0) {
            int px = bx + Math.round(bw * (r.playStartTick / (float) total));
            g.fill(px, by - 3, px + 1, by + bh + 3, HistoryUi.a(HistoryUi.SILVER, alpha));
        }

        // Маркери подій.
        MatchRecord.Event hovered = null;
        int hoverX = 0;
        for (MatchRecord.Event e : r.events) {
            if (!isTrackEvent(e.type)) continue;
            int ex = bx + Math.round(bw * (e.tick / (float) total));
            int col = HistoryUi.eventColor(e.type);
            boolean up = e.type == HistoryEventType.ESCAPED || e.type == HistoryEventType.GENERATOR_DONE;
            int my0 = up ? by - Math.round(7 * k) : by + bh + 1;
            g.fill(ex - 1, my0, ex + 1, my0 + Math.round(6 * k), HistoryUi.a(col, alpha));
            if (Math.abs(mx - ex) <= 3 && my >= by - 9 * k && my <= by + bh + 9 * k) { hovered = e; hoverX = ex; }
        }
        // Легенда фаз.
        int lx = bx;
        int ly = y + ph - Math.round(16 * k);
        for (String stage : HistoryUi.STAGES) {
            g.fill(lx, ly + 2, lx + Math.round(8 * k), ly + 2 + Math.round(6 * k), HistoryUi.a(phaseColor(stage), alpha));
            String name = HistoryUi.stageName(stage);
            HistoryUi.text(g, font, name, lx + 11 * k, ly + 1, 0.72f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, alpha), 0);
            lx += Math.round(font.width(name) * 0.72f * k) + Math.round(26 * k);
        }
        if (hovered != null) {
            int relTick = hovered.tick - Math.max(0, r.playStartTick);
            String line = HistoryUi.clock(Math.max(0, relTick / 20)) + "  " + HistoryUi.eventText(r, hovered);
            g.renderTooltip(font, Component.literal(line), mx, my);
            g.fill(hoverX, by - 4, hoverX + 1, by + bh + 4, HistoryUi.a(0xFFFFFFFF, 0.9f * alpha));
        }
        return y + ph;
    }

    private static boolean isTrackEvent(HistoryEventType t) {
        return t == HistoryEventType.ESCAPED || t == HistoryEventType.ELIMINATED
            || t == HistoryEventType.GENERATOR_DONE || t == HistoryEventType.HOOKED
            || t == HistoryEventType.DOWNED;
    }

    /** Чотири стадії: пройдені горять, біля кожної — скільки в ній пробули. */
    private float drawStages(GuiGraphics g, MatchRecord r, int x, int y, int w, long now, float alpha) {
        int pad = Math.round(12 * k);
        int h = Math.round(72 * k);
        panel(g, x, y, w, h, HistoryUi.WARN, alpha);
        HistoryUi.text(g, font, tr("maniacmod.history.overview.stages").toUpperCase(Locale.ROOT), x + pad, y + 8 * k,
            0.85f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);
        int reached = HistoryUi.stageIndex(r.stage);
        int n = HistoryUi.STAGES.length;
        int gap = Math.round(8 * k);
        int cw = (w - 2 * pad - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) {
            int cx = x + pad + i * (cw + gap);
            int cyy = y + Math.round(22 * k);
            int chh = Math.round(40 * k);
            boolean lit = i <= reached;
            boolean last = i == reached;
            float pulse = last ? 0.6f + 0.4f * (float) Math.sin(now / 450.0) : 1f;
            int col = phaseColor(HistoryUi.STAGES[i]);
            g.fill(cx, cyy, cx + cw, cyy + chh, HistoryUi.a(lit ? HistoryUi.mix(ManiacUiTheme.SLOT_FILL, col, 0.22f * pulse)
                : ManiacUiTheme.SLOT_FILL, alpha));
            ManiacUiTheme.border1px(g, cx, cyy, cw, chh, HistoryUi.a(lit ? col : ManiacUiTheme.BORDER, alpha));
            HistoryUi.text(g, font, HistoryUi.fit(font, HistoryUi.stageName(HistoryUi.STAGES[i]).toUpperCase(Locale.ROOT),
                0.8f * k, cw - 8 * k), cx + cw / 2f, cyy + 8 * k, 0.8f * k,
                HistoryUi.a(lit ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_DIM, alpha), 1);
            int secs = secondsIn(r, HistoryUi.STAGES[i]);
            HistoryUi.text(g, font, lit ? HistoryUi.clock(secs) : "—", cx + cw / 2f, cyy + 22 * k, 1.05f * k,
                HistoryUi.a(lit ? col : ManiacUiTheme.TEXT_DIM, alpha), 1);
            if (last) HistoryUi.text(g, font, "▲", cx + cw / 2f, cyy + chh + 1 * k, 0.8f * k, HistoryUi.a(col, alpha), 1);
        }
        return y + h;
    }

    private static int secondsIn(MatchRecord r, String phase) {
        int ticks = 0;
        for (MatchRecord.PhaseSpan s : r.phases) if (s.phase.equals(phase)) ticks += Math.max(0, s.endTick - s.startTick);
        return ticks / 20;
    }

    private float drawObjectives(GuiGraphics g, MatchRecord r, int x, int y, int w, float alpha) {
        int pad = Math.round(12 * k);
        MatchObjectives.Objective[] all = MatchObjectives.Objective.values();
        int chipH = Math.round(16 * k);
        int gap = Math.round(6 * k);
        // Рядки чипів: переносимо, якщо не влазять.
        int cx = x + pad, cy = y + Math.round(24 * k);
        int rows = 1;
        List<int[]> pos = new ArrayList<>();
        for (MatchObjectives.Objective o : all) {
            String label = tr("maniacmod.history.objective." + o.name().toLowerCase(Locale.ROOT));
            int cw = Math.round(font.width(label) * 0.8f * k) + Math.round(18 * k);
            if (cx + cw > x + w - pad) { cx = x + pad; cy += chipH + gap; rows++; }
            pos.add(new int[]{cx, cy, cw});
            cx += cw + gap;
        }
        int h = Math.round(24 * k) + rows * (chipH + gap) + Math.round(4 * k);
        panel(g, x, y, w, h, HistoryUi.CYAN, alpha);
        HistoryUi.text(g, font, tr("maniacmod.history.overview.objectives").toUpperCase(Locale.ROOT), x + pad, y + 8 * k,
            0.85f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);
        for (int i = 0; i < all.length; i++) {
            boolean done = (r.objectiveMask & (1 << all[i].ordinal())) != 0;
            int[] p = pos.get(i);
            int col = done ? HistoryUi.OK : ManiacUiTheme.BORDER;
            g.fill(p[0], p[1], p[0] + p[2], p[1] + chipH, HistoryUi.a(done ? HistoryUi.mix(ManiacUiTheme.SLOT_FILL, col, 0.2f)
                : ManiacUiTheme.SLOT_FILL, alpha));
            ManiacUiTheme.border1px(g, p[0], p[1], p[2], chipH, HistoryUi.a(col, alpha));
            g.fill(p[0] + Math.round(5 * k), p[1] + chipH / 2 - 1, p[0] + Math.round(9 * k), p[1] + chipH / 2 + 2,
                HistoryUi.a(done ? HistoryUi.OK : ManiacUiTheme.TEXT_DIM, alpha));
            HistoryUi.text(g, font, tr("maniacmod.history.objective." + all[i].name().toLowerCase(Locale.ROOT)),
                p[0] + Math.round(13 * k), p[1] + chipH / 2f - 3 * k, 0.8f * k,
                HistoryUi.a(done ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_DIM, alpha), 0);
        }
        return y + h;
    }

    private float drawTotals(GuiGraphics g, MatchRecord r, int x, int y, int w, long now, float alpha) {
        int hits = 0, downs = 0, hooks = 0, rescues = 0, repair = 0, placed = 0, caught = 0, left = 0;
        for (MatchRecord.Player p : r.players) {
            if (p.role == MatchRecord.Role.MANIAC) {
                hits += p.hitsDealt;
                hooks += p.hooks;
                for (MatchRecord.TrapLine t : p.traps) { placed += t.placed; caught += t.caught; }
            } else {
                downs += p.downs;
                rescues += p.rescues;
                repair += p.repairSeconds;
            }
            if (p.outcome == MatchRecord.Outcome.LEFT) left++;
        }
        String[][] tiles = {
            {"maniacmod.history.tile.hits", String.valueOf(hits)},
            {"maniacmod.history.tile.downs", String.valueOf(downs)},
            {"maniacmod.history.tile.hooks", String.valueOf(hooks)},
            {"maniacmod.history.tile.rescues", String.valueOf(rescues)},
            {"maniacmod.history.tile.repair", String.valueOf(repair)},
            {"maniacmod.history.tile.traps", placed + " / " + caught},
            {"maniacmod.history.tile.eliminated", String.valueOf(r.eliminated)},
            {"maniacmod.history.tile.left", String.valueOf(left)},
        };
        int cols = w > 520 * k ? 4 : 2;
        int gap = Math.round(8 * k);
        int tw = (w - (cols - 1) * gap) / cols;
        int th = Math.round(46 * k);
        float cnt = ease(now - tabAt, 100, 900);
        for (int i = 0; i < tiles.length; i++) {
            int tx = x + (i % cols) * (tw + gap);
            int ty = y + (i / cols) * (th + gap);
            g.fill(tx, ty, tx + tw, ty + th, HistoryUi.a(ManiacUiTheme.SLOT_FILL, alpha));
            ManiacUiTheme.border1px(g, tx, ty, tw, th, HistoryUi.a(ManiacUiTheme.BORDER, alpha));
            g.fill(tx, ty, tx + 2, ty + th, HistoryUi.a(HistoryUi.SILVER, alpha));
            String value = tiles[i][1];
            if (!value.contains("/")) {
                try { value = String.valueOf(Math.round(Integer.parseInt(value) * cnt)); } catch (NumberFormatException ignored) { }
            }
            HistoryUi.text(g, font, value, tx + 12 * k, ty + 9 * k, 1.5f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, alpha), 0);
            HistoryUi.text(g, font, HistoryUi.fit(font, tr(tiles[i][0]), 0.75f * k, tw - 20 * k), tx + 12 * k, ty + 31 * k,
                0.75f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);
        }
        int rowsN = (tiles.length + cols - 1) / cols;
        return y + rowsN * (th + gap) - gap;
    }

    // ── Вкладка 2: ГРАВЦІ ────────────────────────────────────────────────

    private List<Integer> sortedPlayers(MatchRecord r) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < r.players.size(); i++) idx.add(i);
        idx.sort(Comparator
            .comparingInt((Integer i) -> r.players.get(i).role == MatchRecord.Role.MANIAC ? 0 : 1)
            .thenComparingInt(i -> r.players.get(i).outcome.ordinal())
            .thenComparing(i -> r.players.get(i).name.toLowerCase(Locale.ROOT)));
        return idx;
    }

    private int relSeconds(MatchRecord r, int tick) {
        return Math.max(0, (tick - Math.max(0, r.playStartTick)) / 20);
    }

    private String endInfo(MatchRecord r, MatchRecord.Player p) {
        return switch (p.outcome) {
            case ESCAPED -> tr("maniacmod.history.p.escaped", HistoryUi.clock(relSeconds(r, p.endTick)),
                HistoryUi.phaseName(p.endPhase));
            case ELIMINATED -> tr("maniacmod.history.p.out", HistoryUi.clock(relSeconds(r, p.endTick)),
                HistoryUi.phaseName(p.endPhase), HistoryUi.causeText(p.endCause, r.nameOf(p.killerIdx)));
            case LEFT -> tr("maniacmod.history.p.left", HistoryUi.clock(relSeconds(r, p.endTick)));
            case CAUGHT -> tr("maniacmod.history.p.caught", HistoryUi.clock(p.survivedSeconds));
            case MANIAC -> tr("maniacmod.history.p.maniac", p.hitsDealt, p.hooks, p.downs);
        };
    }

    private float drawPlayers(GuiGraphics g, MatchRecord r, int x, int y, int w, int mx, int my, long now, float alpha) {
        rowRects.clear();
        int rowH = Math.round(44 * k);
        int gap = Math.round(6 * k);
        int pad = Math.round(12 * k);
        float cy = y;
        List<Integer> order = sortedPlayers(r);
        for (int n = 0; n < order.size(); n++) {
            int idx = order.get(n);
            MatchRecord.Player p = r.players.get(idx);
            float ea = ease(now - tabAt, n * 45L, 360);
            int ox = Math.round((1f - ea) * 24 * k);
            float a = alpha * ea;
            boolean open = idx == expanded;
            List<String> extra = open ? expandLines(r, p) : List.of();
            int extraH = open ? Math.round((extra.size() * 11 + 10) * k) : 0;
            int h = rowH + extraH;
            int ry = (int) cy;
            rowRects.put(idx, new int[]{x, ry, w, rowH});
            int oc = HistoryUi.outcomeColor(p.outcome);
            boolean hover = HistoryUi.inside(mx, my, new int[]{x, ry, w, rowH}) && my >= y - 2 && my < y + viewH;
            g.fill(x + ox, ry, x + w + ox, ry + h, HistoryUi.a(HistoryUi.mix(ManiacUiTheme.SLOT_FILL, 0xFF171B1E,
                hover ? 1f : 0f), a));
            ManiacUiTheme.border1px(g, x + ox, ry, w, h, HistoryUi.a(open ? ManiacUiTheme.BORDER_BRIGHT : ManiacUiTheme.BORDER, a));
            g.fill(x + ox, ry, x + ox + Math.round(3 * k), ry + h, HistoryUi.a(oc, a));

            int face = Math.round(26 * k);
            int fx = x + ox + pad, fy = ry + (rowH - face) / 2;
            PlayerFaceRenderer.draw(g, HistoryUi.skinOf(p.id), fx, fy, face);
            ManiacUiTheme.border1px(g, fx - 1, fy - 1, face + 2, face + 2, HistoryUi.a(oc, 0.9f * a));
            if (a < 0.98f) g.fill(fx, fy, fx + face, fy + face, HistoryUi.a(0xFF000000, 1f - a));

            int tx = fx + face + Math.round(10 * k);
            HistoryUi.text(g, font, p.name, tx, ry + 7 * k, 1.05f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, a), 0);
            String role = tr(p.role == MatchRecord.Role.MANIAC ? "maniacmod.history.role.maniac"
                : "maniacmod.history.role.survivor").toUpperCase(Locale.ROOT);
            int nameW = Math.round(font.width(p.name) * 1.05f * k);
            HistoryUi.text(g, font, role, tx + nameW + 8 * k, ry + 9 * k, 0.7f * k,
                HistoryUi.a(p.role == MatchRecord.Role.MANIAC ? HistoryUi.BLOOD : ManiacUiTheme.TEXT_DIM, a), 0);
            HistoryUi.text(g, font, HistoryUi.fit(font, endInfo(r, p), 0.82f * k, w - (tx - x) - 150 * k), tx, ry + 22 * k,
                0.82f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, a), 0);
            String stats = tr("maniacmod.history.p.stats", p.hitsDealt + p.hitsTaken, p.downs, p.hooks, p.rescues,
                p.repairSeconds);
            HistoryUi.text(g, font, HistoryUi.fit(font, stats, 0.72f * k, w - (tx - x) - 150 * k), tx, ry + 33 * k, 0.72f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, a), 0);

            // Права частина: результат + XP.
            HistoryUi.text(g, font, HistoryUi.outcomeName(p.outcome).toUpperCase(Locale.ROOT), x + w + ox - pad, ry + 9 * k,
                0.9f * k, HistoryUi.a(oc, a), 2);
            if (p.xpTotal > 0) {
                HistoryUi.text(g, font, "+" + p.xpTotal + " XP", x + w + ox - pad, ry + 24 * k, 0.9f * k,
                    HistoryUi.a(HistoryUi.SILVER, a), 2);
            }
            if (context != null && context.equals(p.id)) {
                HistoryUi.text(g, font, tr("maniacmod.history.you"), x + w + ox - pad, ry + 34 * k, 0.65f * k,
                    HistoryUi.a(ManiacUiTheme.TEXT_DIM, a), 2);
            }

            if (open) {
                float ly = ry + rowH + 3 * k;
                for (String line : extra) {
                    HistoryUi.text(g, font, line, x + ox + pad + 4 * k, ly, 0.78f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, a), 0);
                    ly += 11 * k;
                }
            }
            cy += h + gap;
        }
        return cy - y;
    }

    /** Розгорнутий рядок гравця: пастки (для маніяка) і за що нараховано XP. */
    private List<String> expandLines(MatchRecord r, MatchRecord.Player p) {
        List<String> out = new ArrayList<>();
        if (p.role == MatchRecord.Role.MANIAC) {
            if (p.traps.isEmpty()) out.add(tr("maniacmod.history.p.no_traps"));
            for (MatchRecord.TrapLine t : p.traps) {
                out.add(tr("maniacmod.history.p.trap", HistoryUi.trapName(t.trapId), t.placed, t.caught));
            }
        } else {
            out.add(tr("maniacmod.history.p.survived", HistoryUi.clock(p.survivedSeconds),
                p.hitsTaken, p.downs, p.rescues, p.repairSeconds));
        }
        if (p.xp.isEmpty()) out.add(tr("maniacmod.history.p.no_xp"));
        for (MatchRecord.XpLine l : p.xp) out.add("+" + l.xp + " XP  ·  " + tr("maniacmod.reward." + l.key));
        return out;
    }

    // ── Вкладка 3: ХРОНОЛОГІЯ ────────────────────────────────────────────

    private float drawTimeline(GuiGraphics g, MatchRecord r, int x, int y, int w, int mx, int my, float alpha) {
        // Чипи-фільтри.
        int chipH = Math.round(18 * k), chipGap = Math.round(6 * k);
        int cx = x;
        for (int i = 0; i < 4; i++) {
            String label = tr("maniacmod.history.tl.filter." + i).toUpperCase(Locale.ROOT);
            int cw = Math.round(font.width(label) * 0.8f * k) + Math.round(20 * k);
            chipRect[i][0] = cx; chipRect[i][1] = y; chipRect[i][2] = cw; chipRect[i][3] = chipH;
            boolean on = timelineFilter == i;
            boolean hover = HistoryUi.inside(mx, my, chipRect[i]);
            g.fill(cx, y, cx + cw, y + chipH, HistoryUi.a(on ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, alpha));
            ManiacUiTheme.border1px(g, cx, y, cw, chipH, HistoryUi.a(on || hover ? ManiacUiTheme.BORDER_BRIGHT
                : ManiacUiTheme.BORDER, alpha));
            if (on) g.fill(cx, y + chipH - 2, cx + cw, y + chipH, HistoryUi.a(HistoryUi.SILVER, alpha));
            HistoryUi.text(g, font, label, cx + cw / 2f, y + chipH / 2f - 3 * k, 0.8f * k,
                HistoryUi.a(on ? ManiacUiTheme.TEXT_TITLE : ManiacUiTheme.TEXT_DIM, alpha), 1);
            cx += cw + chipGap;
        }

        int rowH = Math.round(15 * k);
        float cy = y + chipH + 10 * k;
        int shown = 0;
        int pad = Math.round(8 * k);
        for (MatchRecord.Event e : r.events) {
            if (timelineFilter != 0) {
                int cat = HistoryUi.eventCategory(e.type);
                if (cat != timelineFilter) continue;
            }
            int ry = (int) cy;
            int col = HistoryUi.eventColor(e.type);
            boolean phase = e.type == HistoryEventType.PHASE;
            if (ry + rowH >= y - rowH && ry <= y + viewH + rowH + scroll) {
                if (phase) g.fill(x, ry + rowH / 2, x + w, ry + rowH / 2 + 1, HistoryUi.a(ManiacUiTheme.BORDER, 0.7f * alpha));
                else if (shown % 2 == 0) g.fill(x, ry, x + w, ry + rowH, HistoryUi.a(0xFFFFFFFF, 0.025f * alpha));
                int rel = e.tick - Math.max(0, r.playStartTick);
                String time = (rel < 0 ? "−" : "") + HistoryUi.clock(Math.abs(rel) / 20);
                HistoryUi.text(g, font, time, x + pad, ry + rowH / 2f - 3 * k, 0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 0);
                g.fill(x + Math.round(58 * k), ry + rowH / 2 - 2, x + Math.round(58 * k) + 4, ry + rowH / 2 + 2,
                    HistoryUi.a(col, alpha));
                String text = HistoryUi.eventText(r, e);
                HistoryUi.text(g, font, HistoryUi.fit(font, text, 0.85f * k, w - 74 * k - pad), x + Math.round(68 * k),
                    ry + rowH / 2f - 3 * k, 0.85f * k, HistoryUi.a(phase ? ManiacUiTheme.TEXT_DIM : mix(col), alpha), 0);
            }
            cy += rowH;
            shown++;
        }
        if (shown == 0) {
            HistoryUi.text(g, font, tr("maniacmod.history.tl.empty"), x + w / 2f, cy + 10 * k, 0.9f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 1);
        }
        return cy - y + 6 * k;
    }

    /** Текст події трохи світліший за її колір, щоб читався на темному фоні. */
    private static int mix(int col) {
        return HistoryUi.mix(col, 0xFFFFFFFF, 0.45f);
    }

    // ── Вкладка 4: ДОСВІД ────────────────────────────────────────────────

    private float drawXp(GuiGraphics g, MatchRecord r, int x, int y, int w, long now, float alpha) {
        int pad = Math.round(12 * k);
        int gap = Math.round(8 * k);
        float cy = y;
        int shownCards = 0;
        for (int n : sortedPlayers(r)) {
            MatchRecord.Player p = r.players.get(n);
            if (p.xpBefore < 0 && p.xp.isEmpty()) continue;
            float ea = ease(now - tabAt, shownCards * 70L, 400);
            float a = alpha * ea;
            int lines = Math.max(1, p.xp.size());
            int h = Math.round((44 + lines * 11 + 10) * k);
            int ry = (int) cy;
            int oc = HistoryUi.outcomeColor(p.outcome);
            g.fill(x, ry, x + w, ry + h, HistoryUi.a(ManiacUiTheme.SLOT_FILL, a));
            ManiacUiTheme.border1px(g, x, ry, w, h, HistoryUi.a(ManiacUiTheme.BORDER, a));
            g.fill(x, ry, x + Math.round(3 * k), ry + h, HistoryUi.a(oc, a));

            int face = Math.round(26 * k);
            PlayerFaceRenderer.draw(g, HistoryUi.skinOf(p.id), x + pad, ry + Math.round(8 * k), face);
            if (a < 0.98f) g.fill(x + pad, ry + Math.round(8 * k), x + pad + face, ry + Math.round(8 * k) + face,
                HistoryUi.a(0xFF000000, 1f - a));
            int tx = x + pad + face + Math.round(10 * k);
            HistoryUi.text(g, font, p.name, tx, ry + 8 * k, 1.05f * k, HistoryUi.a(ManiacUiTheme.TEXT_TITLE, a), 0);

            Ranks.Role role = p.role == MatchRecord.Role.MANIAC ? Ranks.Role.MANIAC : Ranks.Role.SURVIVOR;
            if (p.xpBefore >= 0 && p.xpAfter >= 0) {
                int lvB = XpCurve.levelOf(p.xpBefore), lvA = XpCurve.levelOf(p.xpAfter);
                Ranks.Rank rb = Ranks.ofXp(role, p.xpBefore), ra = Ranks.ofXp(role, p.xpAfter);
                String rankLine = rankText(rb) + "  →  " + rankText(ra) + "   ·   "
                    + tr("maniacmod.history.xp.level", lvB, lvA);
                HistoryUi.text(g, font, HistoryUi.fit(font, rankLine, 0.8f * k, w - (tx - x) - 120 * k), tx, ry + 22 * k,
                    0.8f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, a), 0);
                // Смуга рівня до → після.
                int bw = w - (tx - x) - pad - 6, by = ry + Math.round(34 * k);
                float fb = XpCurve.intoLevel(p.xpBefore) / (float) XpCurve.needed(lvB);
                float fa = lvA > lvB ? 1f : XpCurve.intoLevel(p.xpAfter) / (float) XpCurve.needed(lvA);
                float grow = ease(now - tabAt, 200 + shownCards * 70L, 900);
                g.fill(tx, by, tx + bw, by + Math.round(5 * k), HistoryUi.a(ManiacUiTheme.BAR_BG, a));
                g.fill(tx, by, tx + Math.round(bw * fb), by + Math.round(5 * k), HistoryUi.a(0xFF56606E, a));
                g.fill(tx + Math.round(bw * fb), by, tx + Math.round(bw * (fb + (fa - fb) * grow)), by + Math.round(5 * k),
                    HistoryUi.a(role == Ranks.Role.MANIAC ? ManiacUiTheme.BAR_FILL_DANGER : ManiacUiTheme.BAR_FILL_NEUTRAL, a));
                if (lvA > lvB) {
                    HistoryUi.text(g, font, tr("maniacmod.history.xp.levelup").toUpperCase(Locale.ROOT), x + w - pad,
                        ry + 8 * k, 0.85f * k, HistoryUi.a(HistoryUi.OK, a), 2);
                }
            }
            HistoryUi.text(g, font, "+" + p.xpTotal + " XP", x + w - pad, ry + 20 * k, 1.1f * k,
                HistoryUi.a(HistoryUi.SILVER, a), 2);

            float ly = ry + 46 * k;
            if (p.xp.isEmpty()) {
                HistoryUi.text(g, font, tr("maniacmod.history.p.no_xp"), tx, ly, 0.78f * k, HistoryUi.a(ManiacUiTheme.TEXT_DIM, a), 0);
            }
            for (MatchRecord.XpLine l : p.xp) {
                HistoryUi.text(g, font, tr("maniacmod.reward." + l.key), tx, ly, 0.78f * k, HistoryUi.a(ManiacUiTheme.TEXT_BODY, a), 0);
                HistoryUi.text(g, font, "+" + l.xp, x + w - pad, ly, 0.78f * k, HistoryUi.a(HistoryUi.SILVER, a), 2);
                ly += 11 * k;
            }
            cy += h + gap;
            shownCards++;
        }
        if (shownCards == 0) {
            HistoryUi.text(g, font, tr("maniacmod.history.xp.none"), x + w / 2f, y + 20 * k, 0.95f * k,
                HistoryUi.a(ManiacUiTheme.TEXT_DIM, alpha), 1);
            return 40 * k;
        }
        return cy - y;
    }

    private static String rankText(Ranks.Rank rank) {
        String base = HistoryUi.tr(rank.langKey());
        if (rank.top()) return base;
        String[] roman = {"I", "II", "III", "IV", "V"};
        int d = Math.max(1, Math.min(roman.length, rank.division()));
        return base + " " + roman[d - 1];
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        if (HistoryUi.inside(mx, my, backRect)) { UiAnim.playClick(); onClose(); return true; }
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            if (HistoryUi.inside(mx, my, tabRect[i])) { setTab(tabs[i]); return true; }
        }
        if (tab == Tab.TIMELINE) {
            for (int i = 0; i < 4; i++) {
                if (chipRect[i][2] > 0 && HistoryUi.inside(mx, my, chipRect[i])) {
                    timelineFilter = i;
                    scrollTarget = 0f;
                    UiAnim.playClick();
                    return true;
                }
            }
        }
        if (tab == Tab.PLAYERS) {
            for (var e : rowRects.entrySet()) {
                if (HistoryUi.inside(mx, my, e.getValue())) {
                    expanded = expanded == e.getKey() ? -1 : e.getKey();
                    UiAnim.playClick();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollTarget -= (float) (delta * 32 * k);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Tab[] tabs = Tab.values();
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT -> { setTab(tabs[(tab.ordinal() + 1) % tabs.length]); return true; }
            case GLFW.GLFW_KEY_LEFT -> { setTab(tabs[(tab.ordinal() + tabs.length - 1) % tabs.length]); return true; }
            case GLFW.GLFW_KEY_TAB -> { setTab(tabs[(tab.ordinal() + 1) % tabs.length]); return true; }
            case GLFW.GLFW_KEY_DOWN -> { scrollTarget += 40 * k; return true; }
            case GLFW.GLFW_KEY_UP -> { scrollTarget -= 40 * k; return true; }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { scrollTarget += viewH * 0.8f; return true; }
            case GLFW.GLFW_KEY_PAGE_UP -> { scrollTarget -= viewH * 0.8f; return true; }
            default -> { }
        }
        return super.keyPressed(key, scan, mods);
    }
}

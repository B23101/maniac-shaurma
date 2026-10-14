package com.log_to_kot.maniacmod.client.screen.admin;

import com.log_to_kot.maniacmod.client.screen.esc.ManiacEscMenuScreen;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.rating.AdminReviewsPacket;
import com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Адмін-екран відгуків: хто що поставив і з яким коментарем.
 * GUI-еквівалент {@code /maniac reviews}, але з ПОВНИМИ даними, а не
 * лише п'ятьма останніми коментарями.
 *
 * ── Вкладки ──────────────────────────────────────────────────────────
 *   • ВІДГУКИ  — ліворуч список гравців (новіші першими, з середньою
 *     оцінкою), праворуч — вибраний: коментар і КОЖНА його оцінка
 *     (гра / маньяки, три критерії);
 *   • ЗВЕДЕННЯ — середні оцінки гри та кожного маньяка за всіма
 *     гравцями (рахує сервер, клієнт лише малює).
 *
 * ── Дані й права ─────────────────────────────────────────────────────
 * Усе приходить одним {@link AdminReviewsPacket} (сервер віддає його лише
 * тим, хто має {@code hasPermissions(2)}). Екран нічого не змінює на
 * сервері — це перегляд. Esc / «Назад» повертає в ESC-меню без вступної
 * анімації і без звуку.
 */
@OnlyIn(Dist.CLIENT)
public final class AdminReviewsScreen extends Screen {

    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int DIM = 0xFF7A7A7A;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;

    private static final int ROW_H = 28;
    private static final int CARD_H = 32;
    private static final int CARD_GAP = 4;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private enum Tab { REVIEWS, SUMMARY }

    private AdminReviewsPacket data;
    private Tab tab = Tab.REVIEWS;
    private int selected = 0;
    private float listScroll;
    private float detailScroll;
    private float summaryScroll;
    private int detailContentH;

    private final HorrorBackdrop backdrop = new HorrorBackdrop();
    private final long openedAt = Util.getMillis();
    private final long[] clock = new long[1];
    private float hoverBack;

    // ── Розкладка ───────────────────────────────────────────────────────
    private int px, py, pw, ph;
    private int cx, cy, cw, ch;
    private int listW;
    private int detailX, detailW;

    public AdminReviewsScreen(AdminReviewsPacket data) {
        super(Component.translatable("maniacmod.admin.reviews.title"));
        this.data = data;
    }

    /** Нові дані від сервера (екран уже відкритий). */
    public void update(AdminReviewsPacket packet) {
        this.data = packet;
        if (selected >= packet.reviews().size()) selected = Math.max(0, packet.reviews().size() - 1);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() { back(); }

    private void back() {
        minecraft.setScreen(new ManiacEscMenuScreen(true));
    }

    private void layout() {
        pw = Mth.clamp(width - 40, 320, 620);
        ph = Mth.clamp(height - 30, 200, 360);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 12;
        cy = py + 54;
        cw = pw - 24;
        ch = ph - 54 - 12;
        listW = Math.min(190, (int) (cw * 0.4f));
        detailX = cx + listW + 8;
        detailW = cw - listW - 8;
    }

    private int backX() { return px + pw - 92; }
    private int backY() { return py + 10; }
    private static final int BACK_W = 80;
    private static final int BACK_H = 18;
    private static final int TAB_W = 96;
    private static final int TAB_H = 16;

    private int tabX(Tab t) { return px + 12 + (t == Tab.REVIEWS ? 0 : TAB_W + 6); }
    private int tabY() { return py + 32; }

    // ── Рендер ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = Util.getMillis();
        float dt = UiAnim.frameDt(clock);
        float a = UiAnim.easeOutCubic((now - openedAt) / 350f);
        layout();
        clampScroll();

        backdrop.render(g, width, height, 1f);

        g.fill(px, py, px + pw, py + ph, BloodFx.a(PANEL, 0.94f * a));
        BloodFx.border1(g, px, py, pw, ph, BloodFx.a(BORDER, a));
        g.fill(px, py, px + pw, py + 2, BloodFx.a(BloodFx.BRIGHT, a));

        // заголовок
        text(g, I18n.get("maniacmod.admin.reviews.title").toUpperCase(Locale.ROOT), px + 12, py + 9, 1.3f,
            BloodFx.a(SILVER, a), true);
        text(g, I18n.get("maniacmod.admin.reviews.count", data.reviews().size()), px + 12, py + 22, 0.8f,
            BloodFx.a(DIM, a), false);

        // кнопка «Назад»
        boolean hovBack = inRect(mx, my, backX(), backY(), BACK_W, BACK_H);
        hoverBack = UiAnim.approach(hoverBack, hovBack ? 1f : 0f, 16f, dt);
        g.fill(backX(), backY(), backX() + BACK_W, backY() + BACK_H, BloodFx.a(0xFF0A0B0C, 0.92f * a));
        int blade = Math.round(BACK_W * UiAnim.easeOutCubic(hoverBack));
        if (blade > 0) {
            g.fill(backX(), backY(), backX() + blade, backY() + BACK_H, BloodFx.a(BloodFx.MID, 0.9f * a));
        }
        BloodFx.border1(g, backX(), backY(), BACK_W, BACK_H,
            BloodFx.a(BloodFx.mix(BORDER, BloodFx.HOT, hoverBack), a));
        String backLabel = I18n.get("maniacmod.admin.reviews.back");
        text(g, backLabel, backX() + (BACK_W - this.font.width(backLabel)) / 2f, backY() + 5, 1f,
            BloodFx.a(BloodFx.mix(BODY, 0xFFFFFFFF, hoverBack), a), true);

        // вкладки
        for (Tab t : Tab.values()) {
            boolean active = t == tab;
            boolean hov = inRect(mx, my, tabX(t), tabY(), TAB_W, TAB_H);
            int bg = active ? BloodFx.MID : (hov ? 0xFF1A1D1F : 0xFF0F1214);
            g.fill(tabX(t), tabY(), tabX(t) + TAB_W, tabY() + TAB_H, BloodFx.a(bg, a));
            BloodFx.border1(g, tabX(t), tabY(), TAB_W, TAB_H,
                BloodFx.a(active ? BloodFx.HOT : BORDER, a));
            String label = I18n.get(t == Tab.REVIEWS ? "maniacmod.admin.reviews.tab.reviews"
                : "maniacmod.admin.reviews.tab.summary").toUpperCase(Locale.ROOT);
            text(g, label, tabX(t) + (TAB_W - this.font.width(label) * 0.85f) / 2f, tabY() + 4, 0.85f,
                BloodFx.a(active ? 0xFFFFFFFF : BODY, a), false);
        }
        g.fill(px + 12, py + 51, px + pw - 12, py + 52, BloodFx.a(BORDER, a));

        if (tab == Tab.REVIEWS) renderReviews(g, mx, my, a);
        else renderSummary(g, a);

        text(g, I18n.get("maniacmod.admin.reviews.hint"), px + 12, py + ph - 10, 0.7f, BloodFx.a(DIM, a), false);
    }

    // ── Вкладка «Відгуки» ───────────────────────────────────────────────

    private void renderReviews(GuiGraphics g, int mx, int my, float a) {
        List<AdminReviewsPacket.Review> reviews = data.reviews();
        if (reviews.isEmpty()) {
            text(g, I18n.get("maniacmod.admin.reviews.empty"), cx, cy + 6, 1f, BloodFx.a(DIM, a), false);
            return;
        }

        // список гравців
        g.enableScissor(cx, cy, cx + listW, cy + ch);
        for (int i = 0; i < reviews.size(); i++) {
            int ry = Math.round(cy + i * ROW_H - listScroll);
            if (ry + ROW_H < cy || ry > cy + ch) continue;
            AdminReviewsPacket.Review r = reviews.get(i);
            boolean sel = i == selected;
            boolean hov = inRect(mx, my, cx, cy, listW, ch) && my >= ry && my < ry + ROW_H;
            int bg = sel ? BloodFx.mix(0xFF0B0D0E, BloodFx.MID, 0.55f) : (hov ? 0xFF14181A : 0xFF0E1012);
            g.fill(cx, ry, cx + listW - 6, ry + ROW_H - 2, BloodFx.a(bg, a));
            g.fill(cx, ry, cx + 2, ry + ROW_H - 2, BloodFx.a(sel ? BloodFx.HOT : BORDER, a));

            String avg = average(r.ratings()) > 0f ? "★ " + fmt(average(r.ratings())) : "—";
            int avgW = Math.round(this.font.width(avg) * 0.9f);
            int maxName = listW - 6 - 12 - avgW - 6;
            String name = this.font.plainSubstrByWidth(r.player(), Math.max(10, maxName));
            text(g, name, cx + 8, ry + 4, 1f, BloodFx.a(SILVER, a), false);
            text(g, avg, cx + listW - 6 - 6 - avgW, ry + 5, 0.9f,
                BloodFx.a(r.ratings().isEmpty() ? DIM : BloodFx.HOT, a), false);
            text(g, formatDate(r.timestamp()), cx + 8, ry + 16, 0.7f, BloodFx.a(DIM, a), false);
            if (!r.comment().isBlank()) {
                text(g, "✎", cx + listW - 6 - 14, ry + 15, 0.8f, BloodFx.a(BODY, a), false);
            }
        }
        g.disableScissor();
        scrollbar(g, cx + listW - 4, cy, ch, listScroll, reviews.size() * ROW_H, a);

        // деталі вибраного
        g.fill(detailX - 4, cy, detailX - 3, cy + ch, BloodFx.a(BORDER, a));
        g.enableScissor(detailX, cy, detailX + detailW, cy + ch);
        detailContentH = drawDetail(g, reviews.get(Mth.clamp(selected, 0, reviews.size() - 1)),
            detailX, Math.round(cy - detailScroll), detailW - 8, a);
        g.disableScissor();
        scrollbar(g, detailX + detailW - 4, cy, ch, detailScroll, detailContentH, a);
    }

    /** Малює відгук і повертає повну висоту вмісту (для прокрутки). */
    private int drawDetail(GuiGraphics g, AdminReviewsPacket.Review r, int x, int top, int w, float a) {
        int y = top;
        text(g, r.player(), x, y, 1.3f, BloodFx.a(SILVER, a), true);
        y += 16;

        float avg = average(r.ratings());
        String meta = formatDate(r.timestamp())
            + (avg > 0f ? "  ·  " + I18n.get("maniacmod.admin.reviews.avg", fmt(avg)) : "");
        text(g, meta, x, y, 0.8f, BloodFx.a(DIM, a), false);
        y += 14;
        g.fill(x, y, x + w, y + 1, BloodFx.a(BORDER, a));
        y += 6;

        text(g, I18n.get("maniacmod.admin.reviews.comment").toUpperCase(Locale.ROOT), x, y, 0.7f,
            BloodFx.a(DIM, a), false);
        y += 11;
        if (r.comment().isBlank()) {
            text(g, I18n.get("maniacmod.admin.reviews.no_comment"), x, y, 0.9f, BloodFx.a(DIM, a), false);
            y += 12;
        } else {
            float cs = 0.95f;
            var lines = this.font.split(Component.literal(r.comment()), (int) (w / cs));
            int ca = BloodFx.a(BODY, a);
            for (var line : lines) {
                if ((ca >>> 24) >= 4) {
                    g.pose().pushPose();
                    g.pose().translate(x, y, 0f);
                    g.pose().scale(cs, cs, 1f);
                    g.drawString(this.font, line, 0, 0, ca, false);
                    g.pose().popPose();
                }
                y += 10;
            }
        }
        y += 8;

        text(g, I18n.get("maniacmod.admin.reviews.ratings").toUpperCase(Locale.ROOT), x, y, 0.7f,
            BloodFx.a(DIM, a), false);
        y += 11;
        if (r.ratings().isEmpty()) {
            text(g, I18n.get("maniacmod.admin.reviews.no_ratings"), x, y, 0.9f, BloodFx.a(DIM, a), false);
            y += 12;
        } else {
            for (ManiacRatingSyncPacket.Entry e : r.ratings()) {
                drawRatingCard(g, x, y, w, nameOf(e.id()), "", e.id(), e.look(), e.abilities(), e.fear(), a);
                y += CARD_H + CARD_GAP;
            }
        }
        return y - top + 4;
    }

    // ── Вкладка «Зведення» ──────────────────────────────────────────────

    private void renderSummary(GuiGraphics g, float a) {
        List<AdminReviewsPacket.Summary> list = data.summaries();
        if (list.isEmpty()) {
            text(g, I18n.get("maniacmod.admin.reviews.empty"), cx, cy + 6, 1f, BloodFx.a(DIM, a), false);
            return;
        }
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        int y = Math.round(cy - summaryScroll);
        for (AdminReviewsPacket.Summary s : list) {
            if (s.voters() <= 0) {
                g.fill(cx, y, cx + cw - 8, y + CARD_H, BloodFx.a(0xFF0E1012, a));
                g.fill(cx, y, cx + 2, y + CARD_H, BloodFx.a(BORDER, a));
                text(g, nameOf(s.id()), cx + 8, y + 6, 1f, BloodFx.a(BODY, a), false);
                text(g, I18n.get("maniacmod.admin.reviews.none_yet"), cx + 8, y + 18, 0.8f,
                    BloodFx.a(DIM, a), false);
            } else {
                float overall = (s.look() + s.abilities() + s.fear()) / 3f;
                String right = I18n.get("maniacmod.admin.reviews.voters", s.voters())
                    + "  ·  ★ " + fmt(overall);
                drawRatingCard(g, cx, y, cw - 8, nameOf(s.id()), right, s.id(),
                    s.look(), s.abilities(), s.fear(), a);
            }
            y += CARD_H + CARD_GAP;
        }
        g.disableScissor();
        scrollbar(g, cx + cw - 4, cy, ch, summaryScroll, summaryContentH(), a);
    }

    private int summaryContentH() {
        return data.summaries().size() * (CARD_H + CARD_GAP);
    }

    // ── Спільні малювалки ───────────────────────────────────────────────

    /** Картка «назва + три критерії зі шкалами». Висота — {@link #CARD_H}. */
    private void drawRatingCard(GuiGraphics g, int x, int y, int w, String name, String right,
                                String id, float v0, float v1, float v2, float a) {
        g.fill(x, y, x + w, y + CARD_H, BloodFx.a(0xFF0E1012, a));
        g.fill(x, y, x + 2, y + CARD_H, BloodFx.a(BloodFx.BRIGHT, a));

        int rightW = right.isEmpty() ? 0 : Math.round(this.font.width(right) * 0.8f) + 6;
        String shown = this.font.plainSubstrByWidth(name, Math.max(20, w - 12 - rightW));
        text(g, shown, x + 8, y + 3, 1f, BloodFx.a(SILVER, a), false);
        if (!right.isEmpty()) {
            text(g, right, x + w - rightW + 2, y + 5, 0.8f, BloodFx.a(BODY, a), false);
        }

        String[] labels = labelsFor(id);
        float[] vals = {v0, v1, v2};
        int colW = (w - 16) / 3;
        for (int i = 0; i < 3; i++) {
            int colX = x + 8 + i * colW;
            int barW = colW - 8;
            text(g, labels[i], colX, y + 14, 0.7f, BloodFx.a(DIM, a), false);
            String val = vals[i] > 0f ? fmt(vals[i]) : "—";
            int vw = Math.round(this.font.width(val) * 0.8f);
            text(g, val, colX + barW - vw, y + 14, 0.8f,
                BloodFx.a(vals[i] > 0f ? SILVER : DIM, a), false);
            g.fill(colX, y + 24, colX + barW, y + 27, BloodFx.a(0xFF05070A, a));
            int fill = Math.round(barW * Mth.clamp(vals[i] / 5f, 0f, 1f));
            if (fill > 0) {
                int col = BloodFx.mix(BloodFx.MID, ManiacTabTheme.OK, Mth.clamp((vals[i] - 1f) / 4f, 0f, 1f));
                g.fill(colX, y + 24, colX + fill, y + 27, BloodFx.a(col, a));
            }
        }
    }

    private void scrollbar(GuiGraphics g, int x, int y, int h, float scroll, int contentH, float a) {
        if (contentH <= h) return;
        g.fill(x, y, x + 2, y + h, BloodFx.a(0xFF15181A, a));
        float ratio = h / (float) contentH;
        int thumbH = Math.max(14, Math.round(h * ratio));
        float maxScroll = contentH - h;
        int thumbY = y + Math.round((h - thumbH) * (maxScroll <= 0f ? 0f : scroll / maxScroll));
        g.fill(x, thumbY, x + 2, thumbY + thumbH, BloodFx.a(BloodFx.BRIGHT, a));
    }

    private String[] labelsFor(String id) {
        if (ManiacRatingSyncPacket.GAME_ID.equals(id)) {
            return new String[]{
                I18n.get("maniacmod.rating.cat.visual"),
                I18n.get("maniacmod.rating.cat.gameplay"),
                I18n.get("maniacmod.rating.cat.map")};
        }
        return new String[]{
            I18n.get("maniacmod.rating.cat.look"),
            I18n.get("maniacmod.rating.cat.abilities"),
            I18n.get("maniacmod.rating.cat.fear")};
    }

    private static String nameOf(String id) {
        if (ManiacRatingSyncPacket.GAME_ID.equals(id)) return I18n.get("maniacmod.rating.tab.game");
        if (ManiacRegistry.exists(id)) return ManiacRegistry.get(id).displayName();
        return id;
    }

    /** Середнє по всіх виставлених критеріях усіх записів; 0 — нічого не виставлено. */
    private static float average(List<ManiacRatingSyncPacket.Entry> entries) {
        int n = 0;
        float sum = 0f;
        for (var e : entries) {
            if (e.look() > 0f) { sum += e.look(); n++; }
            if (e.abilities() > 0f) { sum += e.abilities(); n++; }
            if (e.fear() > 0f) { sum += e.fear(); n++; }
        }
        return n == 0 ? 0f : sum / n;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "—";
        try {
            return DATE_FMT.format(Instant.parse(iso));
        } catch (Exception ex) {
            return iso;
        }
    }

    private void text(GuiGraphics g, String s, float x, float y, float scale, int argb, boolean shadow) {
        if ((argb >>> 24) < 4 || s.isEmpty()) return;
        if (scale == 1f) {
            g.drawString(this.font, s, Math.round(x), Math.round(y), argb, shadow);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(this.font, s, 0, 0, argb, shadow);
        g.pose().popPose();
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Введення ────────────────────────────────────────────────────────

    private void clampScroll() {
        listScroll = Mth.clamp(listScroll, 0f, Math.max(0, data.reviews().size() * ROW_H - ch));
        detailScroll = Mth.clamp(detailScroll, 0f, Math.max(0, detailContentH - ch));
        summaryScroll = Mth.clamp(summaryScroll, 0f, Math.max(0, summaryContentH() - ch));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        float step = (float) (-delta * 20.0);
        if (tab == Tab.SUMMARY) {
            summaryScroll += step;
        } else if (mx < detailX - 4) {
            listScroll += step;
        } else {
            detailScroll += step;
        }
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(double dmx, double dmy, int button) {
        int mx = (int) dmx;
        int my = (int) dmy;
        if (button != 0) return super.mouseClicked(dmx, dmy, button);

        if (inRect(mx, my, backX(), backY(), BACK_W, BACK_H)) {
            UiAnim.playClick();
            back();
            return true;
        }
        for (Tab t : Tab.values()) {
            if (inRect(mx, my, tabX(t), tabY(), TAB_W, TAB_H)) {
                if (tab != t) UiAnim.playClick();
                tab = t;
                return true;
            }
        }
        if (tab == Tab.REVIEWS && inRect(mx, my, cx, cy, listW, ch)) {
            int idx = (int) ((my - cy + listScroll) / ROW_H);
            if (idx >= 0 && idx < data.reviews().size() && idx != selected) {
                selected = idx;
                detailScroll = 0f;
                UiAnim.playClick();
            }
            return true;
        }
        return super.mouseClicked(dmx, dmy, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> {
                back();
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT -> {
                tab = tab == Tab.REVIEWS ? Tab.SUMMARY : Tab.REVIEWS;
                return true;
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (tab == Tab.REVIEWS && selected < data.reviews().size() - 1) {
                    selected++;
                    detailScroll = 0f;
                    keepSelectedVisible();
                }
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                if (tab == Tab.REVIEWS && selected > 0) {
                    selected--;
                    detailScroll = 0f;
                    keepSelectedVisible();
                }
                return true;
            }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void keepSelectedVisible() {
        float top = selected * ROW_H;
        if (top < listScroll) listScroll = top;
        else if (top + ROW_H > listScroll + ch) listScroll = top + ROW_H - ch;
    }
}

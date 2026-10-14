package com.log_to_kot.maniacmod.client.screen.rating;

import com.log_to_kot.maniacmod.client.screen.esc.ManiacEscMenuScreen;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.HorrorBackdrop;
import com.log_to_kot.maniacmod.client.style.StarFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.rating.ManiacRatingSubmitPacket;
import com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Екран оцінок маньяків. Логіка — як у {@code ModeRatingScreen}
 * снайперів: вкладки, три критерії із зірками з кроком 0.5, коментар,
 * «Підтвердити» / «Пізніше». Відмінність — вкладка = ОДИН МАНЬЯК (а не
 * режим), критерії свої, а стиль — кривавий, під мод.
 *
 * ── Критерії ─────────────────────────────────────────────────────────
 * Вигляд · Здібності · Страх. Три, бо формат пакета й файлу — три
 * числа на маньяка (див. {@code ManiacRatingSyncPacket.Entry}).
 *
 * ── Керування ────────────────────────────────────────────────────────
 * ЛКМ по лівій половині зірки — x.5, по правій — x.0; повторний клік по
 * тій самій оцінці скидає її. ← → перемикають маньяка. Esc = «Пізніше».
 *
 * ── Вихід ────────────────────────────────────────────────────────────
 * {@code exitAfterSubmit} = true: екран відкрито перед виходом з гри —
 * будь-яке завершення (підтвердити / пізніше) виходить зі світу.
 * false: відкрито з ESC-меню — завершення повертає в нього.
 */
@OnlyIn(Dist.CLIENT)
public final class ManiacRatingScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int PAD = 14;
    private static final int TAB_H = 22;
    private static final int ROW_H = 30;
    private static final int STAR_R = 8;
    private static final int STAR_STEP = 24;
    private static final int COMMENT_H = 44;
    private static final int BTN_H = 24;
    private static final long CLOSE_MS = 200L;

    private static final int SILVER = 0xFFF2F4F8;
    private static final int BODY = 0xFFB9B9B9;
    private static final int DIM = 0xFF7A7A7A;
    private static final int PANEL = 0xFF0B0D0E;
    private static final int BORDER = 0xFF292D2E;
    private static final int STAR_OFF = 0xFF3A3F47;
    private static final int STAR_ON = 0xFFE0303A;
    private static final int STAR_HOT = 0xFFFF6A70;
    private static final int GOOD = 0xFF4FD07A;

    private static final String[] CAT_KEYS = {
        "maniacmod.rating.cat.look", "maniacmod.rating.cat.abilities", "maniacmod.rating.cat.fear"};
    /** Критерії вкладки «Гра» — ті самі, що в снайперів: візуал / геймплей / карта. */
    private static final String[] GAME_CAT_KEYS = {
        "maniacmod.rating.cat.visual", "maniacmod.rating.cat.gameplay", "maniacmod.rating.cat.map"};

    private final ClientManiacRatingState state = ClientManiacRatingState.get();
    private final boolean exitAfterSubmit;
    private final HorrorBackdrop backdrop = new HorrorBackdrop();
    private final long openedAt = Util.getMillis();
    private final long[] frameClock = new long[1];

    private EditBox commentBox;
    private int selectedTab;
    private long closeStart = -1L;

    private float hoverSubmit;
    private float hoverLater;
    private float[] hoverTabs = new float[0];
    private int hoverCat = -1;
    private float hoverVal;
    private long[] starPop = new long[ClientManiacRatingState.CATEGORIES];
    private int[] starPopIdx = new int[ClientManiacRatingState.CATEGORIES];

    private int px;
    private int py;
    private int panelH;
    private int tabsY;
    private int catY;
    private int summaryY;
    private int commentY;
    private int submitY;
    private int laterY;

    public ManiacRatingScreen(boolean exitAfterSubmit) {
        super(Component.translatable("maniacmod.rating.title"));
        this.exitAfterSubmit = exitAfterSubmit;
    }

    // ── Життєвий цикл ───────────────────────────────────────────────────

    @Override
    protected void init() {
        hoverTabs = new float[Math.max(1, state.knownManiacs().size())];
        layout();

        String prev = commentBox != null ? commentBox.getValue() : state.comment();
        commentBox = new EditBox(this.font, px + PAD + 6, commentY + 18, PANEL_W - PAD * 2 - 12, 16,
            Component.empty());
        commentBox.setMaxLength(200);
        commentBox.setBordered(false);
        commentBox.setTextColor(0xFFF2F4F8);
        commentBox.setHint(Component.translatable("maniacmod.rating.comment.placeholder"));
        if (prev != null && !prev.isEmpty()) commentBox.setValue(prev);
        addRenderableWidget(commentBox);
    }

    private void layout() {
        int titleH = 50;
        int tabsH = TAB_H + 10;
        int catsH = ClientManiacRatingState.CATEGORIES * ROW_H + 8;
        int summaryH = 18;
        int commentH = COMMENT_H + 8;
        int buttonsH = BTN_H * 2 + 8;
        panelH = PAD + titleH + tabsH + catsH + summaryH + commentH + buttonsH + PAD;
        panelH = Math.min(panelH, height - 12);

        px = (width - PANEL_W) / 2;
        py = Math.max(6, (height - panelH) / 2);

        int y = py + PAD + titleH;
        tabsY = y;
        y += tabsH;
        catY = y;
        y += catsH;
        summaryY = y;
        y += summaryH;
        commentY = y;
        y += commentH;
        submitY = y;
        y += BTN_H + 8;
        laterY = y;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    public void onClose() { onRateLater(); }

    private static boolean isGame(String id) {
        return ManiacRatingSyncPacket.GAME_ID.equals(id);
    }

    private String nameOf(String id) {
        if (isGame(id)) return I18n.get("maniacmod.rating.tab.game");
        return ManiacRegistry.exists(id) ? ManiacRegistry.get(id).displayName() : id;
    }

    // ── Рендер ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        long now = Util.getMillis();
        long age = now - openedAt;

        float life = 1f;
        if (closeStart >= 0L) {
            float p = UiAnim.clamp01((now - closeStart) / (float) CLOSE_MS);
            life = 1f - p * p;
            if (p >= 1f) {
                finishClose();
                return;
            }
        }

        float dt = UiAnim.frameDt(frameClock);
        layout();
        updateHover(mx, my, dt);

        backdrop.render(g, width, height, life);

        float ease = UiAnim.easeOutBack(UiAnim.clamp01(age / 450f), 0.6f);
        float a = UiAnim.clamp01(age / 300f) * life;
        g.pose().pushPose();
        g.pose().translate(width / 2f, height / 2f, 0f);
        float sc = 0.86f + 0.14f * ease;
        g.pose().scale(sc, sc, 1f);
        g.pose().translate(-width / 2f, -height / 2f, 0f);

        renderPanel(g, now, age, a, mx, my);

        g.pose().popPose();

        // EditBox поза масштабом: інакше курсор введення розходиться з мишею
        if (a > 0.9f) super.render(g, mx, my, partialTick);
    }

    private void renderPanel(GuiGraphics g, long now, long age, float a, int mx, int my) {
        float beat = backdrop.beat(now);

        g.fill(px, py, px + PANEL_W, py + panelH, BloodFx.a(PANEL, 0.93f * a));
        BloodFx.border1(g, px, py, PANEL_W, panelH, BloodFx.a(BORDER, a));
        g.fill(px, py, px + PANEL_W, py + 2, BloodFx.a(BloodFx.BRIGHT, a));

        // кров, що стікає з верхньої грані панелі
        int dripN = 14;
        for (int k = 0; k < dripN; k++) {
            float s = BloodFx.hash01(50L + k * 13L);
            float grow = UiAnim.easeOutCubic((age - 250L - k * 55L) / 800f);
            if (grow <= 0f) continue;
            float len = (5f + s * 22f) * grow + 1.1f * (float) Math.sin(now / 850.0 + k);
            int x = px + 8 + Math.round((k + s * 0.6f) * (PANEL_W - 24f) / dripN);
            BloodFx.drip(g, x, py + 2, 2 + (k % 2), len, now, s, a);
        }

        // заголовок
        String title = I18n.get("maniacmod.rating.title").toUpperCase(Locale.ROOT);
        float ts = 1.6f;
        int tw = Math.round(this.font.width(title) * ts);
        g.pose().pushPose();
        g.pose().translate(px + (PANEL_W - tw) / 2f, py + PAD + 8, 20f);
        g.pose().scale(ts, ts, 1f);
        if (a > 0.02f) {
            g.drawString(this.font, title, 1, 1, BloodFx.a(BloodFx.MID, a), false);
            g.drawString(this.font, title, 0, 0, BloodFx.a(SILVER, a), false);
        }
        g.pose().popPose();
        String sub = I18n.get("maniacmod.rating.subtitle");
        drawCentered(g, sub, px + PANEL_W / 2, py + PAD + 8 + Math.round(9 * ts) + 5, BloodFx.a(DIM, a));

        List<String> ids = state.knownManiacs();
        if (ids.isEmpty()) {
            drawCentered(g, I18n.get("maniacmod.rating.empty"), px + PANEL_W / 2, catY + 30, BloodFx.a(BODY, a));
        } else {
            if (selectedTab >= ids.size()) selectedTab = 0;
            renderTabs(g, a, ids);
            renderCategories(g, now, a, ids.get(selectedTab), beat);
            renderSummary(g, a, ids.get(selectedTab));
        }

        renderComment(g, a);
        renderButton(g, now, a, submitY, "maniacmod.rating.submit", GOOD, hoverSubmit, true);
        renderButton(g, now, a, laterY,
            exitAfterSubmit ? "maniacmod.rating.later" : "maniacmod.rating.close",
            BloodFx.BRIGHT, hoverLater, false);
    }

    private void renderTabs(GuiGraphics g, float a, List<String> ids) {
        int totalW = PANEL_W - PAD * 2;
        int tabW = totalW / ids.size();
        for (int i = 0; i < ids.size(); i++) {
            int tx = px + PAD + i * tabW;
            boolean active = i == selectedTab;
            float h = i < hoverTabs.length ? hoverTabs[i] : 0f;
            ClientManiacRatingState.Rating r = state.rating(ids.get(i));

            int fill = active ? BloodFx.a(0xFF3A0A0E, 0.95f * a)
                : BloodFx.a(BloodFx.mix(0xFF0B0D0E, 0xFF1A0A0C, h), 0.9f * a);
            g.fill(tx + 1, tabsY, tx + tabW - 1, tabsY + TAB_H, fill);
            BloodFx.border1(g, tx + 1, tabsY, tabW - 2, TAB_H,
                BloodFx.a(active ? BloodFx.BRIGHT : BloodFx.mix(BORDER, BloodFx.MID, h), a));
            if (active) g.fill(tx + 2, tabsY + TAB_H - 2, tx + tabW - 2, tabsY + TAB_H - 1, BloodFx.a(BloodFx.HOT, a));

            String label = nameOf(ids.get(i));
            int maxW = tabW - 22;
            if (this.font.width(label) > maxW) label = this.font.plainSubstrByWidth(label, maxW - 6) + "…";
            int lw = this.font.width(label);
            int col = active ? 0xFFFFFFFF : BloodFx.mix(BODY, 0xFFFFFFFF, h);
            g.drawString(this.font, label, tx + (tabW - lw) / 2, tabsY + (TAB_H - 8) / 2, BloodFx.a(col, a), false);

            // крапка: оцінено повністю / частково
            if (!r.isEmpty()) {
                BloodFx.blob(g, tx + tabW - 7f, tabsY + 6f, 2f,
                    BloodFx.a(r.isComplete() ? GOOD : 0xFFE0A030, a));
            }
        }
    }

    private void renderCategories(GuiGraphics g, long now, float a, String id, float beat) {
        ClientManiacRatingState.Rating rating = state.rating(id);
        int starsX = px + PAD + 150;

        for (int c = 0; c < ClientManiacRatingState.CATEGORIES; c++) {
            int rowY = catY + c * ROW_H;
            g.fill(px + PAD, rowY, px + PANEL_W - PAD, rowY + ROW_H - 4, BloodFx.a(0xFF0A0B0C, 0.85f * a));
            BloodFx.border1(g, px + PAD, rowY, PANEL_W - PAD * 2, ROW_H - 4, BloodFx.a(BORDER, 0.8f * a));
            g.fill(px + PAD, rowY, px + PAD + 2, rowY + ROW_H - 4, BloodFx.a(BloodFx.MID, a));

            g.drawString(this.font, I18n.get((isGame(id) ? GAME_CAT_KEYS : CAT_KEYS)[c]), px + PAD + 10, rowY + (ROW_H - 4 - 8) / 2,
                BloodFx.a(SILVER, a), false);

            float cur = rating.get(c);
            float shown = hoverCat == c ? hoverVal : cur;
            boolean preview = hoverCat == c;

            for (int s = 0; s < 5; s++) {
                float cx = starsX + s * STAR_STEP + STAR_R;
                float cy = rowY + (ROW_H - 4) / 2f;
                float pop = 0f;
                if (starPopIdx[c] == s && now - starPop[c] < 320L) {
                    pop = 1f - (now - starPop[c]) / 320f;
                }
                float r = STAR_R * (1f + 0.35f * pop * pop);

                StarFx.draw(g, cx, cy, r, BloodFx.a(STAR_OFF, a));
                int on = preview ? BloodFx.mix(STAR_ON, STAR_HOT, 0.5f) : STAR_ON;
                float onA = preview ? 0.8f : 1f;
                if (shown >= s + 1f) {
                    StarFx.draw(g, cx, cy, r, BloodFx.a(on, onA * a));
                } else if (shown >= s + 0.5f) {
                    StarFx.draw(g, cx, cy, r, BloodFx.a(on, onA * a), cx);
                }
            }

            String num = cur > 0f ? String.format(Locale.ROOT, "%.1f", cur) : "—";
            g.drawString(this.font, num, starsX + 5 * STAR_STEP + 6, rowY + (ROW_H - 4 - 8) / 2,
                BloodFx.a(cur > 0f ? STAR_HOT : DIM, a), false);
        }
    }

    private void renderSummary(GuiGraphics g, float a, String id) {
        ClientManiacRatingState.Rating r = state.rating(id);
        float avg = r.average();
        String s = avg > 0f
            ? I18n.get("maniacmod.rating.average", String.format(Locale.ROOT, "%.1f", avg))
            : I18n.get("maniacmod.rating.average_none");
        drawCentered(g, s, px + PANEL_W / 2, summaryY + 4, BloodFx.a(avg > 0f ? BODY : DIM, a));
    }

    private void renderComment(GuiGraphics g, float a) {
        int bx = px + PAD;
        int w = PANEL_W - PAD * 2;
        g.fill(bx, commentY, bx + w, commentY + COMMENT_H, BloodFx.a(0xFF08090A, 0.9f * a));
        BloodFx.border1(g, bx, commentY, w, COMMENT_H, BloodFx.a(commentBox != null && commentBox.isFocused() ? BloodFx.MID : BORDER, a));
        g.drawString(this.font, I18n.get("maniacmod.rating.comment.label"), bx + 6, commentY + 5,
            BloodFx.a(DIM, a), false);
        if (commentBox != null) {
            commentBox.setX(bx + 6);
            commentBox.setY(commentY + 21);
            commentBox.setWidth(w - 12);
        }
    }

    private void renderButton(GuiGraphics g, long now, float a, int y, String key, int accent,
                              float hover, boolean primary) {
        int bx = px + PAD;
        int w = PANEL_W - PAD * 2;
        g.fill(bx, y, bx + w, y + BTN_H, BloodFx.a(0xFF0A0B0C, 0.92f * a));
        int blade = Math.round(w * UiAnim.easeOutCubic(hover));
        if (blade > 0) {
            g.fill(bx, y, bx + blade, y + BTN_H, BloodFx.a(primary ? 0xFF1F5A34 : BloodFx.MID, 0.9f * a));
            g.fill(bx + Math.max(0, blade - 3), y, bx + blade, y + BTN_H, BloodFx.a(accent, 0.8f * a));
        }
        BloodFx.border1(g, bx, y, w, BTN_H, BloodFx.a(BloodFx.mix(BORDER, accent, hover), a));
        String label = I18n.get(key);
        int lw = this.font.width(label);
        g.drawString(this.font, label, bx + (w - lw) / 2, y + (BTN_H - 8) / 2,
            BloodFx.a(BloodFx.mix(BODY, 0xFFFFFFFF, hover), a), true);
    }

    private void drawCentered(GuiGraphics g, String s, int cx, int y, int argb) {
        if ((argb >>> 24) < 4) return;
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, argb, false);
    }

    // ── Hover ───────────────────────────────────────────────────────────

    private void updateHover(int mx, int my, float dt) {
        int bx = px + PAD;
        int w = PANEL_W - PAD * 2;
        hoverSubmit = UiAnim.approach(hoverSubmit, inRect(mx, my, bx, submitY, w, BTN_H) ? 1f : 0f, 16f, dt);
        hoverLater = UiAnim.approach(hoverLater, inRect(mx, my, bx, laterY, w, BTN_H) ? 1f : 0f, 16f, dt);

        List<String> ids = state.knownManiacs();
        if (!ids.isEmpty()) {
            int tabW = (PANEL_W - PAD * 2) / ids.size();
            for (int i = 0; i < ids.size() && i < hoverTabs.length; i++) {
                hoverTabs[i] = UiAnim.approach(hoverTabs[i],
                    inRect(mx, my, px + PAD + i * tabW, tabsY, tabW, TAB_H) ? 1f : 0f, 16f, dt);
            }
        }

        hoverCat = -1;
        int[] hit = starHit(mx, my);
        if (hit != null) {
            hoverCat = hit[0];
            hoverVal = hit[1] / 2f;
        }
    }

    /** @return {категорія, значення×2 (1..10)} під курсором, або null. */
    private int[] starHit(int mx, int my) {
        int starsX = px + PAD + 150;
        for (int c = 0; c < ClientManiacRatingState.CATEGORIES; c++) {
            int rowY = catY + c * ROW_H;
            if (my < rowY || my >= rowY + ROW_H - 4) continue;
            for (int s = 0; s < 5; s++) {
                int sx = starsX + s * STAR_STEP;
                if (mx >= sx && mx < sx + STAR_STEP - 2) {
                    boolean left = mx < sx + STAR_R;
                    return new int[]{c, s * 2 + (left ? 1 : 2)};
                }
            }
        }
        return null;
    }

    // ── Введення ────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double dmx, double dmy, int button) {
        if (closeStart >= 0L) return true;
        if (button != 0) return super.mouseClicked(dmx, dmy, button);
        int mx = (int) dmx;
        int my = (int) dmy;

        // координати панелі зазнають масштабу лише під час появи; після неї збігаються
        List<String> ids = state.knownManiacs();
        if (!ids.isEmpty()) {
            int tabW = (PANEL_W - PAD * 2) / ids.size();
            for (int i = 0; i < ids.size(); i++) {
                if (inRect(mx, my, px + PAD + i * tabW, tabsY, tabW, TAB_H)) {
                    if (i != selectedTab) UiAnim.playClick();
                    selectedTab = i;
                    return true;
                }
            }
            int[] hit = starHit(mx, my);
            if (hit != null) {
                setRating(ids.get(selectedTab), hit[0], hit[1] / 2f);
                return true;
            }
        }

        int bx = px + PAD;
        int w = PANEL_W - PAD * 2;
        if (inRect(mx, my, bx, submitY, w, BTN_H)) {
            UiAnim.playClick();
            onSubmit();
            return true;
        }
        if (inRect(mx, my, bx, laterY, w, BTN_H)) {
            UiAnim.playClick();
            onRateLater();
            return true;
        }
        return super.mouseClicked(dmx, dmy, button);
    }

    private void setRating(String id, int cat, float value) {
        ClientManiacRatingState.Rating r = state.rating(id);
        float next = r.get(cat) == value ? 0f : value;
        r.set(cat, next);
        starPop[cat] = Util.getMillis();
        starPopIdx[cat] = Math.max(0, (int) Math.ceil(value) - 1);
        if (next > 0f) UiAnim.playStar(next);
        else UiAnim.playDismiss();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (closeStart >= 0L) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (commentBox != null && commentBox.isFocused()) {
                commentBox.setFocused(false);
                setFocused(null);
                return true;
            }
            onRateLater();
            return true;
        }
        boolean typing = commentBox != null && commentBox.isFocused();
        if (!typing) {
            int n = state.knownManiacs().size();
            if (n > 1 && keyCode == GLFW.GLFW_KEY_RIGHT) {
                selectedTab = (selectedTab + 1) % n;
                UiAnim.playClick();
                return true;
            }
            if (n > 1 && keyCode == GLFW.GLFW_KEY_LEFT) {
                selectedTab = (selectedTab - 1 + n) % n;
                UiAnim.playClick();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Дії ─────────────────────────────────────────────────────────────

    private void onSubmit() {
        if (commentBox != null) state.setComment(commentBox.getValue());

        List<ManiacRatingSyncPacket.Entry> entries = new ArrayList<>();
        for (var e : state.ratings().entrySet()) {
            var r = e.getValue();
            entries.add(new ManiacRatingSyncPacket.Entry(e.getKey(), r.look, r.abilities, r.fear));
        }
        ModNetwork.toServer(new ManiacRatingSubmitPacket(entries, state.comment()));
        state.markSubmitted();
        beginClose(true);
    }

    private void onRateLater() {
        if (commentBox != null) state.setComment(commentBox.getValue());
        state.setRateLater(true);
        beginClose(false);
    }

    private void beginClose(boolean submitted) {
        if (closeStart >= 0L) return;
        closeStart = Util.getMillis();
    }

    private void finishClose() {
        if (exitAfterSubmit) {
            ManiacEscMenuScreen.leaveGame();
        } else {
            minecraft.setScreen(new ManiacEscMenuScreen(true));
        }
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}

package com.log_to_kot.maniacmod.client.screen.profile;

import com.log_to_kot.maniacmod.client.ClientProfileState;
import com.log_to_kot.maniacmod.client.overlay.roster.ProfileModels;
import com.log_to_kot.maniacmod.client.screen.menu.ManiacMenuTheme;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.ProfileIcons;
import com.log_to_kot.maniacmod.client.style.ProfileIcons.Icon;
import com.log_to_kot.maniacmod.client.style.RankFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.progression.Ranks;
import com.log_to_kot.maniacmod.progression.XpCurve;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Профіль гравця. Два розділи — ВИЖИВШИЙ і МАНІЯК.
 *
 * <pre>
 *  ┌ ПРОФІЛЬ ───────  [ВИЖИВШИЙ] [МАНІЯК]  ─────── [НАЗАД] ┐
 *  │ ┌ сцена ───────┐ ┌ СТАТИСТИКА ───────────────────────┐ │
 *  │ │  голова,     │ │ (○ 63%)  результати / параметри   │ │
 *  │ │  руки, тіло  │ │ [іконка] [іконка] [іконка] [ік.]  │ │
 *  │ │ ╤══ банер ══╤ │ │ [іконка] [іконка] [іконка] [ік.]  │ │
 *  │ │ │ НІК       │ │ └───────────────────────────────────┘ │
 *  │ │ │ ◆ РАНГ    │ │                                       │
 *  │ │ │ ▰▰▰▱ XP   │ │                                       │
 *  │ └─╯ ╰─────────┘                                         │
 * </pre>
 *
 * <h3>Сцена</h3>
 * 3D-модель видна лише до пояса: нижню частину закриває банер на жердині (опуклий низ із
 * «ластівчиним хвостом», ледь гойдається). Під моделлю — нік (у розділі маніяка — назва маніяка)
 * і ранг із прогресом. Модель повертається за курсором. У розділі маніяка по боках сцени —
 * стрілки: маніяки, моделі й банери переїжджають каруселлю, статистика міняється разом із ними.
 *
 * <h3>Статистика</h3>
 * Герой-блок (кільце відсотка перемог + розподіл результатів / параметри маніяка) і плитки з
 * піксельними іконками, кожна зі своєю анімацією ({@link ProfileIcons}). Числа накручуються.
 *
 * <h3>Дані</h3>
 * {@link ClientProfileState} (відповідь сервера на запит при відкритті). Рівень і ранг — з XP
 * доріжки ролі: {@link XpCurve}, {@link Ranks}. Поки дані не прийшли — плитки «тліють» заглушкою.
 */
public final class ProfileScreen extends Screen {

    private enum Section { SURVIVOR, MANIAC }

    private final Screen parent;
    /** Чий профіль дивимось: {@code null} — власний. Інакше — гравець, по якому клікнули ПКМ у лобі. */
    private final UUID viewedId;
    private final String viewedName;

    private Section section = Section.SURVIVOR;
    private float sectionAnim;           // 0 = виживший, 1 = маніяк
    private final List<ManiacArchetype> maniacs = new ArrayList<>();
    private int mTarget;                 // необмежений індекс (карусель)
    private float mPos;
    private long statsAt;                // від цього моменту накручуються числа
    private long openedAt;
    private long lastDataAt;
    private int lastDir = 1;

    private float yawSmooth = 0f;
    private final float[] sectionHover = new float[2];
    private final float[] arrowHover = new float[2];
    private float backHover;
    private final float[] tileHover = new float[8];
    private final long[] lastNs = {0L};

    private final int[][] sectionRect = new int[2][4];
    private final int[][] arrowRect = new int[2][4];
    private final int[] backRect = new int[4];
    private final int[][] tileRect = new int[8][4];
    private final int[][] trapRect = new int[ManiacArchetype.MAX_TRAP_CATALOG][4];
    /** Підказка під курсором (що саме рахує лічильник); збирається щокадру. */
    private final List<Component> hoverTip = new ArrayList<>();

    private float k = 1f;
    private float fa = 1f;

    // Панель «Ранги» — драбина всіх ступенів із поточним.
    private boolean ranksOpen;
    private float ranksAnim;
    private long ranksOpenedAt;
    private float ranksBtnHover;
    private final int[] ranksBtnRect = new int[4];

    // Кнопка «Історія» (H) — особиста історія ігор цього профілю.
    private float historyBtnHover;
    private final int[] historyBtnRect = new int[4];

    public ProfileScreen(Screen parent) {
        this(parent, null, null);
    }

    /** Профіль іншого гравця (лобі, ПКМ по гравцю). {@code viewedId == null} — власний. */
    public ProfileScreen(Screen parent, UUID viewedId, String viewedName) {
        super(Component.translatable("maniacmod.profile.title"));
        this.parent = parent;
        this.viewedId = viewedId;
        this.viewedName = viewedName == null ? "" : viewedName;
    }

    private boolean viewingOther() { return viewedId != null; }

    private PlayerProfile profileData() {
        if (!viewingOther()) return ClientProfileState.get();
        var o = ClientProfileState.other(viewedId);
        return o == null ? null : o.profile();
    }

    private long dataReceivedAt() {
        if (!viewingOther()) return ClientProfileState.receivedAt();
        var o = ClientProfileState.other(viewedId);
        return o == null ? 0L : o.receivedAtMs();
    }

    private String displayName() {
        if (viewingOther()) {
            var o = ClientProfileState.other(viewedId);
            return o != null && !o.name().isEmpty() ? o.name() : viewedName.isEmpty() ? "—" : viewedName;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "—" : mc.player.getGameProfile().getName();
    }

    /** Сутність для 3D-моделі: свій гравець або чужий, якщо він ще в зоні видимості. */
    private net.minecraft.world.entity.Entity modelEntity() {
        Minecraft mc = Minecraft.getInstance();
        if (!viewingOther()) return mc.player;
        return mc.level == null ? null : mc.level.getPlayerByUUID(viewedId);
    }

    @Override
    protected void init() {
        super.init();
        maniacs.clear();
        maniacs.addAll(ManiacRegistry.all().values());
        maniacs.sort(Comparator.comparing(ManiacArchetype::id));
        if (viewingOther()) ClientProfileState.requestOther(viewedId);
        else ClientProfileState.request();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static float ease(long t, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((t - delay) / (float) dur));
    }

    private int c(int argb, float mul) {
        return GeneratorFuelUiTheme.alpha(argb, mul * fa);
    }

    private static boolean inside(double mx, double my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private static String fmtTime(long seconds) {
        seconds = Math.max(0L, seconds);
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d%s %02d%s", seconds / 3600, tr("maniacmod.profile.unit.h"),
                (seconds % 3600) / 60, tr("maniacmod.profile.unit.m"));
        }
        return String.format(Locale.ROOT, "%d%s %02d%s", seconds / 60, tr("maniacmod.profile.unit.m"),
            seconds % 60, tr("maniacmod.profile.unit.s"));
    }

    private void text(GuiGraphics g, String s, float x, float y, float scale, int argb, int align) {
        if (((argb >>> 24) & 0xFF) < 8 || s.isEmpty()) return;
        int w = font.width(s);
        float ox = align == 1 ? -w / 2f : align == 2 ? -w : 0f;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, s, Math.round(ox), 0, argb, false);
        g.pose().popPose();
    }

    private void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int fill) {
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.BAR_BG, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BAR_FRAME, 0.5f));
        int f = Math.round((w - 2) * UiAnim.clamp01(frac));
        if (f > 0) {
            g.fill(x + 1, y + 1, x + 1 + f, y + h - 1, c(fill, 1f));
            g.fill(x + 1, y + 1, x + 1 + f, y + 2, c(0x38FFFFFF, 1f));
        }
    }

    private int selectedIndex() {
        int n = maniacs.size();
        return n == 0 ? 0 : Math.floorMod(mTarget, n);
    }

    private ManiacArchetype selectedManiac() {
        return maniacs.isEmpty() ? null : maniacs.get(selectedIndex());
    }

    // ── Малювання ────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        long now = System.currentTimeMillis();
        if (openedAt == 0L) { openedAt = now; statsAt = now; }
        float dt = UiAnim.frameDt(lastNs);
        k = Math.max(0.75f, Math.min(1.6f, height / 540f));

        long dataAt = dataReceivedAt();
        if (dataAt > lastDataAt) { lastDataAt = dataAt; statsAt = now; }

        sectionAnim = UiAnim.approach(sectionAnim, section == Section.MANIAC ? 1f : 0f, 9f, dt);
        if (Math.abs(sectionAnim - (section == Section.MANIAC ? 1f : 0f)) < 0.002f) sectionAnim = section == Section.MANIAC ? 1f : 0f;
        mPos = UiAnim.approach(mPos, mTarget, 9f, dt);
        if (Math.abs(mPos - mTarget) < 0.002f) mPos = mTarget;

        float open = ease(now - openedAt, 0, 450);
        int accent = GeneratorFuelUiTheme.mix(ManiacUiTheme.BAR_FILL_NEUTRAL, ManiacUiTheme.BAR_FILL_DANGER, sectionAnim);

        // Фон: затемнений світ, тон ролі зверху, віньєтка знизу.
        g.fill(0, 0, width, height, GeneratorFuelUiTheme.alpha(0xFF020304, 0.86f * open));
        g.fillGradient(0, 0, width, Math.round(height * 0.6f), GeneratorFuelUiTheme.alpha(accent, 0.12f * open), 0x00000000);
        g.fillGradient(0, Math.round(height * 0.7f), width, height, 0x00000000, GeneratorFuelUiTheme.alpha(0xFF000000, 0.55f * open));

        int m = Math.round(14 * k);
        int topH = Math.round(40 * k);
        int bodyTop = m + topH;
        int bodyH = height - bodyTop - m;
        int stageW = Math.min(Math.round(width * 0.36f), Math.round(380 * k));
        int gap = Math.round(14 * k);
        int panelX = m + stageW + gap;
        int panelW = width - panelX - m;

        PlayerProfile prof = profileData();
        hoverTip.clear();

        fa = open;
        drawTopBar(g, m, m, width - 2 * m, topH, mx, my, dt, accent, now);
        fa = 1f;

        drawStage(g, m, bodyTop, stageW, bodyH, prof, accent, now, mx, my, dt, open);
        drawStatsPanel(g, panelX, bodyTop, panelW, bodyH, prof, accent, now, mx, my, dt, open);

        ranksAnim = UiAnim.approach(ranksAnim, ranksOpen ? 1f : 0f, 14f, dt);
        if (ranksAnim < 0.004f) ranksAnim = 0f;
        if (ranksAnim > 0f) drawRanksOverlay(g, now, accent, prof);

        super.render(g, mx, my, pt);
        if (!ranksOpen && !hoverTip.isEmpty()) g.renderComponentTooltip(font, hoverTip, mx, my);
    }

    // ── Верхня смуга ─────────────────────────────────────────────────────

    private void drawTopBar(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float dt, int accent, long now) {
        text(g, tr("maniacmod.profile.overline"), x, y + 2 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, tr("maniacmod.profile.title").toUpperCase(Locale.ROOT), x, y + 13 * k, 1.6f * k,
            c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        int lw = Math.round(font.width(tr("maniacmod.profile.title")) * 1.6f * k);
        g.fill(x, y + h - 2, x + lw, y + h, c(accent, 1f));

        // Перемикач розділів по центру.
        int tabW = Math.round(150 * k);
        int tabH = Math.round(22 * k);
        int tabGap = Math.round(8 * k);
        int tx0 = x + w / 2 - tabW - tabGap / 2;
        int ty = y + (h - tabH) / 2 - Math.round(2 * k);
        for (int i = 0; i < 2; i++) {
            int tx = tx0 + i * (tabW + tabGap);
            sectionRect[i][0] = tx; sectionRect[i][1] = ty; sectionRect[i][2] = tabW; sectionRect[i][3] = tabH;
            boolean active = (i == 1) == (section == Section.MANIAC);
            boolean hover = inside(mx, my, sectionRect[i]);
            sectionHover[i] = UiAnim.approach(sectionHover[i], hover ? 1f : 0f, 16f, dt);
            int slant = 5, sw = tabW - slant;
            ManiacMenuTheme.slantFill(g, tx, ty, sw, tabH, slant,
                c(active ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, 1f));
            ManiacMenuTheme.slantBorder(g, tx, ty, sw, tabH, slant, c(GeneratorFuelUiTheme.mix(
                ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, Math.max(sectionHover[i], active ? 0.5f : 0f)), 1f));
            String label = tr(i == 0 ? "maniacmod.profile.survivor" : "maniacmod.profile.maniac").toUpperCase(Locale.ROOT);
            int tc = active ? ManiacUiTheme.TEXT_ACCENT
                : GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_BODY, sectionHover[i]);
            text(g, label, tx + slant / 2f + sw / 2f, ty + tabH / 2f - 4f * k, k, c(tc, 1f), 1);
        }
        // Підкреслення-повзунок: їде між вкладками.
        int ux = Math.round(tx0 + sectionAnim * (tabW + tabGap));
        g.fill(ux, ty + tabH + Math.round(3 * k), ux + tabW - 5, ty + tabH + Math.round(3 * k) + 2, c(accent, 1f));

        // Кнопка «Назад».
        String back = tr("maniacmod.profile.back").toUpperCase(Locale.ROOT);
        int bw = Math.max(Math.round(110 * k), font.width(back) + Math.round(36 * k));
        int bx = x + w - bw;
        int bh = Math.round(22 * k);
        int by = y + (h - bh) / 2 - Math.round(2 * k);
        backRect[0] = bx; backRect[1] = by; backRect[2] = bw; backRect[3] = bh;
        backHover = UiAnim.approach(backHover, inside(mx, my, backRect) ? 1f : 0f, 16f, dt);
        int slant = 5, sw = bw - slant;
        ManiacMenuTheme.slantFill(g, bx, by, sw, bh, slant, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacMenuTheme.slantFill(g, bx, by, Math.round(sw * UiAnim.easeOutCubic(backHover)), bh, slant,
            c(ManiacUiTheme.MENU_ACCENT_BROWN, 1f));
        ManiacMenuTheme.slantBorder(g, bx, by, sw, bh, slant, c(GeneratorFuelUiTheme.mix(
            ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, backHover), 1f));
        text(g, back, bx + slant / 2f + sw / 2f, by + bh / 2f - 4f * k, k,
            c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, backHover), 1f), 1);

        // Кнопка «Ранги» (R) — лівіше від «Назад».
        String rk = tr("maniacmod.profile.ranks").toUpperCase(Locale.ROOT);
        int rw = Math.max(Math.round(100 * k), font.width(rk) + Math.round(36 * k));
        int rx = bx - Math.round(8 * k) - rw;
        ranksBtnRect[0] = rx; ranksBtnRect[1] = by; ranksBtnRect[2] = rw; ranksBtnRect[3] = bh;
        ranksBtnHover = UiAnim.approach(ranksBtnHover, (inside(mx, my, ranksBtnRect) || ranksOpen) ? 1f : 0f, 16f, dt);
        int rsw = rw - slant;
        ManiacMenuTheme.slantFill(g, rx, by, rsw, bh, slant, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacMenuTheme.slantFill(g, rx, by, Math.round(rsw * UiAnim.easeOutCubic(ranksBtnHover)), bh, slant,
            c(ManiacUiTheme.MENU_ACCENT_BROWN, 1f));
        ManiacMenuTheme.slantBorder(g, rx, by, rsw, bh, slant, c(GeneratorFuelUiTheme.mix(
            ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, ranksBtnHover), 1f));
        // Маленький ромб-емблема перед написом.
        int dr = Math.max(3, Math.round(4 * k));
        int dcx = rx + Math.round(14 * k), dcy = by + bh / 2;
        for (int d = -dr; d <= dr; d++) {
            int half = dr - Math.abs(d);
            g.fill(dcx - half, dcy + d, dcx + half + 1, dcy + d + 1,
                c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_DIM, accent, ranksBtnHover), 1f));
        }
        text(g, rk, rx + slant / 2f + rsw / 2f + 6f * k, by + bh / 2f - 4f * k, k,
            c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, ranksBtnHover), 1f), 1);

        // Кнопка «Історія» (H) — ліворуч, біля заголовка. Історія гравця видима тому, хто бачить його профіль
        // (власник; у лобі — будь-хто); сервер перевіряє це сам, див. HistoryAccess.
        String hk = tr("maniacmod.profile.history").toUpperCase(Locale.ROOT);
        int hw = Math.max(Math.round(100 * k), font.width(hk) + Math.round(36 * k));
        int hx = x + lw + Math.round(16 * k);   // ліворуч, біля заголовка: праворуч тісно від вкладок
        historyBtnRect[0] = hx; historyBtnRect[1] = by; historyBtnRect[2] = hw; historyBtnRect[3] = bh;
        historyBtnHover = UiAnim.approach(historyBtnHover, inside(mx, my, historyBtnRect) ? 1f : 0f, 16f, dt);
        int hsw = hw - slant;
        ManiacMenuTheme.slantFill(g, hx, by, hsw, bh, slant, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacMenuTheme.slantFill(g, hx, by, Math.round(hsw * UiAnim.easeOutCubic(historyBtnHover)), bh, slant,
            c(ManiacUiTheme.MENU_ACCENT_BROWN, 1f));
        ManiacMenuTheme.slantBorder(g, hx, by, hsw, bh, slant, c(GeneratorFuelUiTheme.mix(
            ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, historyBtnHover), 1f));
        // Іконка-«годинник»: кільце зі стрілками.
        int hr = Math.max(3, Math.round(4 * k));
        int hcx = hx + Math.round(14 * k), hcy = by + bh / 2;
        int hcol = c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_DIM, accent, historyBtnHover), 1f);
        ManiacUiTheme.border1px(g, hcx - hr, hcy - hr, hr * 2 + 1, hr * 2 + 1, hcol);
        g.fill(hcx, hcy - hr + 1, hcx + 1, hcy + 1, hcol);
        g.fill(hcx, hcy, hcx + hr - 1, hcy + 1, hcol);
        text(g, hk, hx + slant / 2f + hsw / 2f + 6f * k, by + bh / 2f - 4f * k, k,
            c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, historyBtnHover), 1f), 1);
    }

    /** Особиста історія ігор того, чий профіль відкрито (свого або чужого, якщо його видно в лобі). */
    private void openHistory() {
        Minecraft mc = Minecraft.getInstance();
        UUID id = viewingOther() ? viewedId : (mc.player == null ? null : mc.player.getUUID());
        if (id == null) return;
        UiAnim.playClick();
        mc.setScreen(new com.log_to_kot.maniacmod.client.screen.history.MatchHistoryScreen(this, id, displayName()));
    }

    // ── Панель «Ранги» ───────────────────────────────────────────────────

    /** Спільний XP обох ролей: рівень один, а драбина рангів залежить від ролі (розділу). */
    private static long sharedXp(PlayerProfile prof) {
        return prof == null ? 0L : prof.totalXp();
    }

    private Ranks.Role currentRole() {
        return section == Section.MANIAC ? Ranks.Role.MANIAC : Ranks.Role.SURVIVOR;
    }

    private void drawRanksOverlay(GuiGraphics g, long now, int accent, PlayerProfile prof) {
        float a = UiAnim.easeOutCubic(UiAnim.clamp01(ranksAnim));
        float save = fa;
        fa = a;
        g.fill(0, 0, width, height, c(0xFF020304, 0.84f));

        Ranks.Role role = currentRole();
        XpInfo xi = XpInfo.of(sharedXp(prof), role);
        int n = Ranks.TIERS;
        int curTier = xi.rank.tier();
        int pad = Math.round(18 * k);
        int pw = Math.min(width - 2 * Math.round(16 * k), Math.round(840 * k));
        int ph = Math.round(300 * k);
        int px = (width - pw) / 2;
        int py = (height - ph) / 2 + Math.round((1f - a) * 14 * k);

        g.fill(px, py, px + pw, py + ph, c(ManiacUiTheme.PANEL_BG, 1f));
        ManiacUiTheme.border1px(g, px, py, pw, ph, c(ManiacUiTheme.BORDER, 1f));
        g.fill(px, py, px + pw, py + 2, c(accent, 1f));

        // Шапка.
        text(g, tr("maniacmod.profile.ranks").toUpperCase(Locale.ROOT), px + pad, py + pad - 2 * k, 1.5f * k,
            c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        text(g, tr("maniacmod.profile.ranks.divisions") + "  ·  " + tr("maniacmod.profile.ranks.shared"),
            px + pad, py + pad + 16 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        String track = tr(role == Ranks.Role.SURVIVOR ? "maniacmod.profile.survivor" : "maniacmod.profile.maniac");
        text(g, track.toUpperCase(Locale.ROOT), px + pw - pad, py + pad, 1.0f * k, c(accent, 1f), 2);
        text(g, tr("maniacmod.result.level") + " " + xi.level, px + pw - pad, py + pad + 12 * k, 0.85f * k,
            c(ManiacUiTheme.TEXT_BODY, 1f), 2);

        // Картки ступенів.
        int gapC = Math.round(8 * k);
        int cardW = (pw - 2 * pad - (n - 1) * gapC) / n;
        int cardTop = py + Math.round(66 * k);
        int cardH = Math.round(150 * k);
        int emblemR = Math.max(6, Math.min(Math.round(15 * k), cardW / 2 - Math.round(8 * k)));
        long ot = now - ranksOpenedAt;

        // Позиція «курсора» на драбині: ступінь + частка всередині нього.
        float within;
        if (curTier >= n - 1) within = 0f;
        else {
            int lo = Ranks.minLevel(curTier), hi = Ranks.minLevel(curTier + 1);
            within = UiAnim.clamp01((xi.level - lo + xi.frac) / (float) (hi - lo));
        }
        float ladderPos = curTier + within;

        int firstCx = px + pad + cardW / 2;
        int lastCx = px + pad + (n - 1) * (cardW + gapC) + cardW / 2;
        int railY = cardTop + Math.round(34 * k);

        for (int i = 0; i < n; i++) {
            float ea = ease(ot, i * 55L, 420);
            int cx0 = px + pad + i * (cardW + gapC);
            int oy = Math.round((1f - ea) * 12 * k);
            int top = cardTop + oy;
            boolean cur = i == curTier, reached = i < curTier, locked = i > curTier;
            float ca = a * ea;
            float body = locked ? 0.5f : 1f;

            g.fill(cx0, top, cx0 + cardW, top + cardH, c(cur ? ManiacUiTheme.MENU_PANEL_DARK : ManiacUiTheme.SLOT_FILL, ea));
            if (cur) {
                float pulse = 0.55f + 0.45f * (float) Math.sin(now / 420.0);
                g.fillGradient(cx0 + 1, top + 1, cx0 + cardW - 1, top + cardH - 1,
                    c(accent, 0.0f), c(accent, 0.16f * ca * (0.6f + 0.4f * pulse)));
            }
            ManiacUiTheme.border1px(g, cx0, top, cardW, cardH, c(cur
                ? GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER_BRIGHT, accent, 0.5f)
                : ManiacUiTheme.BORDER, ea * (locked ? 0.6f : 1f)));
            g.fill(cx0, top, cx0 + cardW, top + (cur ? 3 : 1), c(cur ? accent : RankFx.tierColor(role, i), ea * body));
        }

        // Рейка-драбина через центри емблем; залита до поточної позиції.
        int railTop = railY;
        g.fill(firstCx, railTop, lastCx, railTop + 2, c(ManiacUiTheme.BORDER, 0.9f));
        int fillTo = firstCx + Math.round((lastCx - firstCx) * UiAnim.clamp01(ladderPos / (n - 1f))
            * ease(ot, 200, 900));
        g.fill(firstCx, railTop, fillTo, railTop + 2, c(accent, 1f));

        for (int i = 0; i < n; i++) {
            float ea = ease(ot, i * 55L, 420);
            int cx0 = px + pad + i * (cardW + gapC);
            int cx = cx0 + cardW / 2;
            int oy = Math.round((1f - ea) * 12 * k);
            boolean cur = i == curTier, reached = i < curTier, locked = i > curTier;
            float body = locked ? 0.45f : 1f;

            Ranks.Rank r = new Ranks.Rank(role, i, reached ? Ranks.DIVISIONS : (cur ? xi.rank.division() : 1), i == n - 1);
            int ey = railY + 1 + oy;
            // Фон за емблемою — щоб рейка не просвічувала крізь ромб.
            for (int d = -emblemR; d <= emblemR; d++) {
                int half = emblemR - Math.abs(d);
                g.fill(cx - half, ey + d, cx + half + 1, ey + d + 1, c(0xFF0A0C0B, ea));
            }
            RankFx.draw(g, cx, ey, emblemR, r, a * ea * body);

            int ny = ey + emblemR + Math.round(20 * k);
            String name = tr(r.langKey()).toUpperCase(Locale.ROOT);
            float ns = 0.9f * k;
            float nw = font.width(name) * ns;
            if (nw > cardW - 6 * k) ns *= (cardW - 6 * k) / nw;
            text(g, name, cx, ny, ns, c(cur ? ManiacUiTheme.TEXT_ACCENT : ManiacUiTheme.TEXT_BODY, ea * body * a), 1);

            String range = i == n - 1
                ? Component.translatable("maniacmod.profile.ranks.range_top", Ranks.minLevel(i)).getString()
                : Component.translatable("maniacmod.profile.ranks.range", Ranks.minLevel(i), Ranks.minLevel(i + 1) - 1).getString();
            text(g, range, cx, ny + 11 * k, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, ea * a * (locked ? 0.8f : 1f)), 1);

            String state;
            int stateCol;
            if (cur) { state = tr(viewingOther() ? "maniacmod.profile.ranks.they" : "maniacmod.profile.ranks.you"); stateCol = accent; }
            else if (reached) { state = tr("maniacmod.profile.ranks.passed"); stateCol = ManiacTabTheme.OK; }
            else {
                state = Component.translatable("maniacmod.profile.ranks.to_go", Ranks.minLevel(i) - xi.level).getString();
                stateCol = ManiacUiTheme.TEXT_DIM;
            }
            text(g, state.toUpperCase(Locale.ROOT), cx, cardTop + oy + cardH - 16 * k, 0.72f * k,
                c(stateCol, ea * a * (locked ? 0.8f : 1f)), 1);
        }

        // Прогрес поточного рівня.
        int by = cardTop + cardH + Math.round(16 * k);
        int bw = pw - 2 * pad;
        text(g, tr("maniacmod.result.level") + " " + xi.level, px + pad, by, 0.85f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
        text(g, xi.into + " / " + xi.need + " XP", px + pw - pad, by, 0.85f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 2);
        bar(g, px + pad, by + Math.round(11 * k), bw, Math.round(7 * k), xi.frac * ease(ot, 300, 900), accent);
        String nk = Ranks.nextTierKey(role, xi.level);
        if (nk != null) {
            text(g, Component.translatable("maniacmod.summary.next_rank", Component.translatable(nk),
                    Ranks.nextTierLevel(xi.level) - xi.level).getString(),
                px + pw / 2f, by + 24 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 1);
        }

        text(g, tr("maniacmod.profile.ranks.close"), width / 2f, py + ph + 8 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 0.8f), 1);
        fa = save;
    }

    private void toggleRanks() {
        ranksOpen = !ranksOpen;
        if (ranksOpen) ranksOpenedAt = System.currentTimeMillis();
        UiAnim.playClick();
    }

    // ── Сцена: 3D-модель + банер ─────────────────────────────────────────

    private void drawStage(GuiGraphics g, int x, int y, int w, int h, PlayerProfile prof, int accent,
                           long now, int mx, int my, float dt, float open) {
        fa = open;
        int bottom = y + h;
        g.fill(x, y, x + w, bottom, c(ManiacUiTheme.PANEL_BG, 0.7f));

        // Світло зверху за моделлю.
        g.fillGradient(x + 1, y + 1, x + w - 1, y + Math.round(h * 0.62f), c(accent, 0.0f), c(accent, 0.20f));

        // Пилинки, що повільно підіймаються.
        for (int i = 0; i < 18; i++) {
            float sx = hash(i * 3L + 1), sp = 0.018f + 0.03f * hash(i * 3L + 2), ph = hash(i * 3L + 3);
            float fy = 1f - ((now / 1000f * sp + ph) % 1f);
            int px = x + 4 + Math.round(sx * (w - 8));
            int py = y + 4 + Math.round(fy * (h - 8));
            float tw = 0.5f + 0.5f * (float) Math.sin(now / 700.0 + i * 1.7);
            g.fill(px, py, px + 1 + (i % 3 == 0 ? 1 : 0), py + 1 + (i % 3 == 0 ? 1 : 0), c(accent, 0.12f + 0.22f * tw));
        }

        int bannerTop = y + Math.round(h * 0.52f);
        int notch = Math.round(24 * k);
        int bw = Math.round(w * 0.76f);
        int bx = x + (w - bw) / 2;
        int fabricBottom = bottom - Math.round(10 * k);
        int cx = x + w / 2;

        // Модель відсікається сценою (і вище хвоста банера).
        float cursor = UiAnim.clamp01((mx - cx) / (w / 2f) * 0.5f + 0.5f) * 2f - 1f;
        yawSmooth = UiAnim.approach(yawSmooth, cursor * 32f, 8f, dt);

        g.enableScissor(x + 1, y + 1, x + w - 1, fabricBottom - notch);
        float unitOff = w * 0.95f;
        float hip = 0.45f;
        float avail = bannerTop - y - 16 * k;

        // Виживший (модель гравця).
        float sOff = -sectionAnim * unitOff;
        if (Math.abs(sOff) < w && sectionAnim < 0.999f) {
            var entity = modelEntity();
            if (entity != null && ProfileModels.playerAvailable()) {
                float H = 1.8f;
                float scale = avail / ((1f - hip) * H);
                ProfileModels.drawPlayer(g, entity, cx + sOff, bannerTop + hip * H * scale, scale,
                    180f + yawSmooth, 0f);
            }
        }
        // Маніяки (карусель).
        int n = maniacs.size();
        if (sectionAnim > 0.001f && ProfileModels.maniacAvailable()) {
            for (int j = 0; j < n; j++) {
                float d = j - mPos;
                if (n > 0) d -= n * Math.round(d / (float) n);
                float off = (1f - sectionAnim) * unitOff + d * unitOff;
                if (Math.abs(off) >= w) continue;
                ManiacArchetype a = maniacs.get(j);
                float H = Math.max(1f, a.declaredHitboxHeight());
                float scale = avail / ((1f - hip) * H);
                ProfileModels.drawManiac(g, a, cx + off, bannerTop + hip * H * scale, scale, 180f + yawSmooth * 0.8f);
            }
        }
        g.disableScissor();

        drawBanner(g, bx, bannerTop, bw, fabricBottom, notch, cx, prof, accent, now);

        // Рамка сцени поверх усього.
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));
        UiFx.brackets(g, x, y, w, h, c(ManiacUiTheme.CORNER_ACCENT, 0.9f), Math.round(10 * k), 0f, 0);

        drawManiacControls(g, x, y, w, bannerTop, mx, my, dt, accent);
        fa = 1f;
    }

    private static float hash(long n) {
        long z = n * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }

    /** Банер-полотнище на жердині: закриває низ моделі, ледь гойдається, має «ластівчиний хвіст». */
    private void drawBanner(GuiGraphics g, int bx, int top, int bw, int bottom, int notch, int cx,
                            PlayerProfile prof, int accent, long now) {
        int bh = bottom - top;
        float amp = 1.8f * k;
        int gapHalf = Math.round(bw * 0.26f);

        for (int yy = top; yy < bottom; yy++) {
            float frac = (yy - top) / (float) bh;
            int off = Math.round(sway(yy, top, bh, amp, now));
            int body = GeneratorFuelUiTheme.mix(0xFF191E23, 0xFF0A0C0E, frac);
            if (yy < top + Math.round(6 * k)) body = GeneratorFuelUiTheme.mix(body, accent, 0.16f);
            int x0 = bx + off, x1 = bx + bw + off;
            int notchStart = bottom - notch;
            if (yy < notchStart) {
                g.fill(x0, yy, x1, yy + 1, body | 0xFF000000);
                g.fill(x0, yy, x0 + 1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
                g.fill(x1 - 1, yy, x1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
                int in = Math.round(5 * k);
                if (yy > top + in && yy < notchStart - 2) {
                    g.fill(x0 + in, yy, x0 + in + 1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.2f * fa));
                    g.fill(x1 - in - 1, yy, x1 - in, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.2f * fa));
                }
            } else {
                float v = (yy - notchStart) / (float) notch;
                int gh = Math.round(gapHalf * v);
                g.fill(x0, yy, cx + off - gh, yy + 1, body | 0xFF000000);
                g.fill(cx + off + gh, yy, x1, yy + 1, body | 0xFF000000);
                g.fill(x0, yy, x0 + 1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
                g.fill(x1 - 1, yy, x1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
                g.fill(cx + off - gh - 1, yy, cx + off - gh, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
                g.fill(cx + off + gh, yy, cx + off + gh + 1, yy + 1, GeneratorFuelUiTheme.alpha(accent, 0.65f * fa));
            }
        }
        // Внутрішня горизонтальна лінія під «підгином».
        int hemY = top + Math.round(6 * k);
        for (int xx = 0; xx < bw; xx++) {
            int off = Math.round(sway(hemY, top, bh, amp, now));
            g.fill(bx + xx + off, hemY, bx + xx + off + 1, hemY + 1, GeneratorFuelUiTheme.alpha(accent, 0.28f * fa));
        }

        // Жердина.
        int rodH = Math.round(7 * k);
        int rx0 = bx - Math.round(9 * k), rx1 = bx + bw + Math.round(9 * k), ry = top - rodH;
        g.fill(rx0, ry, rx1, top, c(ManiacUiTheme.MENU_ACCENT_BROWN, 1f));
        g.fill(rx0, ry, rx1, ry + 1, c(ManiacUiTheme.BORDER_BRIGHT, 0.8f));
        ManiacUiTheme.border1px(g, rx0, ry, rx1 - rx0, rodH, c(ManiacUiTheme.BORDER, 1f));
        int kn = Math.round(5 * k);
        g.fill(rx0 - kn, ry - 1, rx0, top + 1, c(accent, 0.9f));
        g.fill(rx1, ry - 1, rx1 + kn, top + 1, c(accent, 0.9f));

        // Вміст банера: нік / назва, ранг, прогрес — каруселлю разом із моделями.
        int cw = bw - 2;
        int textTop = top + Math.round(12 * k);
        long ct = now - statsAt;
        g.enableScissor(bx + 2, top + 1, bx + bw - 2, bottom - notch + Math.round(6 * k));
        float unitOff = bw * 1.05f;

        if (sectionAnim < 0.999f) {
            String name = displayName();
            long xp = prof == null ? 0L : prof.totalXp();     // прогрес спільний для обох ролей
            drawBannerText(g, cx - sectionAnim * unitOff, textTop, cw, name, xp, Ranks.Role.SURVIVOR, accent, ct, now,
                top, bh, amp);
        }
        int n = maniacs.size();
        if (sectionAnim > 0.001f) {
            for (int j = 0; j < n; j++) {
                float d = j - mPos;
                d -= n * Math.round(d / (float) n);
                float off = (1f - sectionAnim) * unitOff + d * unitOff;
                if (Math.abs(off) >= bw) continue;
                ManiacArchetype a = maniacs.get(j);
                drawBannerText(g, cx + off, textTop, cw, a.displayName(), prof == null ? 0L : prof.totalXp(),
                    Ranks.Role.MANIAC, accent, ct, now, top, bh, amp);
            }
        }
        g.disableScissor();
    }

    private float sway(int y, int top, int bh, float amp, long now) {
        float frac = UiAnim.clamp01((y - top) / (float) bh);
        return (float) Math.sin(now / 900.0 + y * 0.018) * amp * (float) Math.pow(frac, 1.3);
    }

    private void drawBannerText(GuiGraphics g, float cx, int y0, int cw, String name, long xp, Ranks.Role role,
                                int accent, long ct, long now, int top, int bh, float amp) {
        XpInfo xi = XpInfo.of(xp, role);
        float nameScale = 1.45f * k;
        float tw = font.width(name) * nameScale;
        if (tw > cw - 24 * k) nameScale *= (cw - 24 * k) / tw;

        // Нік.
        float ny = y0;
        g.pose().pushPose();
        g.pose().translate(sway(Math.round(ny), top, bh, amp, now), 0f, 0f);
        text(g, name, cx, ny, nameScale, c(ManiacUiTheme.TEXT_TITLE, 1f), 1);
        int ly = Math.round(ny + 8 * nameScale + 5 * k);
        int lw = Math.round(36 * k * ease(ct, 100, 600));
        g.fill(Math.round(cx) - lw, ly, Math.round(cx) + lw, ly + 1, c(accent, 0.9f));
        g.pose().popPose();

        // Емблема ранга + назва + рівень.
        int er = Math.round(17 * k);
        int ey = ly + Math.round(9 * k) + er;
        float em = ease(ct, 150, 700);
        g.pose().pushPose();
        g.pose().translate(sway(ey, top, bh, amp, now), 0f, 0f);
        RankFx.draw(g, Math.round(cx), ey, Math.max(4, Math.round(er * UiAnim.easeOutBack(UiAnim.clamp01((ct - 150) / 700f), 0.7f))),
            xi.rank, fa * em);
        int ty = ey + er + Math.round(14 * k);
        String rankName = (tr(xi.rank.langKey()) + (xi.rank.top() ? "" : " " + xi.rank.divisionRoman())).toUpperCase(Locale.ROOT);
        text(g, rankName, cx, ty, 1.1f * k, c(ManiacUiTheme.TEXT_ACCENT, em), 1);
        text(g, tr("maniacmod.result.level") + " " + xi.level, cx, ty + 11 * k, 0.9f * k, c(ManiacUiTheme.TEXT_BODY, em), 1);
        g.pose().popPose();

        // Прогрес.
        int by = ty + Math.round(24 * k);
        int barW = cw - Math.round(46 * k);
        g.pose().pushPose();
        g.pose().translate(sway(by, top, bh, amp, now), 0f, 0f);
        bar(g, Math.round(cx) - barW / 2, by, barW, Math.round(7 * k), xi.frac * ease(ct, 300, 1100), accent);
        text(g, xi.into + " / " + xi.need + " XP", cx, by + 10 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 1);
        String nk = Ranks.nextTierKey(role, xi.level);
        if (nk != null) {
            text(g, Component.translatable("maniacmod.summary.next_rank", Component.translatable(nk),
                    Ranks.nextTierLevel(xi.level) - xi.level).getString(),
                cx, by + 20 * k, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, 0.8f), 1);
        }
        g.pose().popPose();
    }

    /** Рівень/ранг/прогрес із XP доріжки. */
    private record XpInfo(Ranks.Rank rank, int level, int into, int need, float frac) {
        static XpInfo of(long xp, Ranks.Role role) {
            int level = XpCurve.levelOf(xp);
            int into = XpCurve.intoLevel(xp);
            int need = XpCurve.needed(level);
            return new XpInfo(Ranks.ofLevel(role, level), level, into, need, into / (float) need);
        }
    }

    // ── Стрілки й індикатор маніяків ─────────────────────────────────────

    private void drawManiacControls(GuiGraphics g, int x, int y, int w, int bannerTop, int mx, int my, float dt, int accent) {
        float vis = UiAnim.clamp01((sectionAnim - 0.3f) / 0.7f);
        if (vis <= 0.01f) {
            arrowRect[0][2] = arrowRect[1][2] = 0;
            return;
        }
        float save = fa;
        fa = save * vis;
        int n = maniacs.size();
        boolean can = n > 1;
        int aw = Math.round(24 * k), ah = Math.round(48 * k);
        int ay = y + (bannerTop - y) / 2 - ah / 2;
        for (int i = 0; i < 2; i++) {
            int ax = i == 0 ? x + Math.round(8 * k) : x + w - aw - Math.round(8 * k);
            arrowRect[i][0] = ax; arrowRect[i][1] = ay; arrowRect[i][2] = aw; arrowRect[i][3] = ah;
            boolean hover = can && inside(mx, my, arrowRect[i]);
            arrowHover[i] = UiAnim.approach(arrowHover[i], hover ? 1f : 0f, 14f, dt);
            float hv = arrowHover[i];
            float dim = can ? 1f : 0.28f;
            int shift = Math.round((i == 0 ? -1 : 1) * hv * 3 * k);
            g.fill(ax, ay, ax + aw, ay + ah, c(ManiacUiTheme.SLOT_FILL, 0.8f * dim));
            ManiacUiTheme.border1px(g, ax, ay, aw, ah, c(GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, accent, hv), dim));
            if (hv > 0.02f) g.fill(ax, ay, ax + aw, ay + ah, c(accent, 0.12f * hv));
            chevron(g, ax + aw / 2 + shift, ay + ah / 2, Math.round(7 * k), i == 0 ? -1 : 1,
                c(GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, hv), dim));
        }

        // Індикатор: крапки, лічильник, назва.
        int pipY = y + Math.round(14 * k);
        int cx = x + w / 2;
        ManiacArchetype sel = selectedManiac();
        if (sel != null) {
            text(g, (selectedIndex() + 1) + " / " + n, cx, pipY, 0.85f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 1);
            int pip = Math.max(3, Math.round(4 * k)), pg = Math.round(4 * k);
            int total = n * pip + (n - 1) * pg;
            int px = cx - total / 2;
            int py = pipY + Math.round(13 * k);
            for (int i = 0; i < Math.min(n, 12); i++) {
                float on = 1f - UiAnim.clamp01(Math.abs(i - mPos - n * Math.round((i - mPos) / (float) n)));
                g.fill(px + i * (pip + pg), py, px + i * (pip + pg) + pip, py + pip,
                    c(GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, accent, on), 1f));
            }
            if (!can) {
                text(g, tr("maniacmod.profile.more_soon"), cx, py + pip + 6 * k, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 0.9f), 1);
            }
        } else {
            text(g, tr("maniacmod.profile.no_maniacs"), cx, pipY, 0.9f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 1);
        }
        fa = save;
    }

    private void chevron(GuiGraphics g, int cx, int cy, int size, int dir, int argb) {
        int th = Math.max(2, Math.round(2.2f * k));
        int tipX = dir < 0 ? cx - size / 2 : cx + size / 2 - th;
        for (int i = 0; i <= size; i++) {
            int xx = dir < 0 ? tipX + i : tipX - i;
            g.fill(xx, cy - i, xx + th, cy - i + th, argb);
            g.fill(xx, cy + i, xx + th, cy + i + th, argb);
        }
    }

    // ── Права панель: статистика ─────────────────────────────────────────

    private record Tile(Icon icon, String labelKey, long value, boolean time, String sub, int color, String tipKey) {}

    private void drawStatsPanel(GuiGraphics g, int x, int y, int w, int h, PlayerProfile prof, int accent,
                                long now, int mx, int my, float dt, float open) {
        fa = open;
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.PANEL_BG, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));
        UiFx.brackets(g, x, y, w, h, c(ManiacUiTheme.CORNER_ACCENT, 0.9f), Math.round(10 * k), 0f, 0);

        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        float unitOff = w;
        if (sectionAnim < 0.999f) {
            g.pose().pushPose();
            g.pose().translate(-sectionAnim * unitOff, 0f, 0f);
            drawStatsContent(g, x, y, w, h, prof, Section.SURVIVOR, null, accent, now, mx, my, dt, 0f);
            g.pose().popPose();
        }
        if (sectionAnim > 0.001f) {
            g.pose().pushPose();
            g.pose().translate((1f - sectionAnim) * unitOff, 0f, 0f);
            // Усередині розділу маніяка при перемиканні — власний короткий зсув за напрямком.
            float swap = 1f - ease(now - statsAt, 0, 380);
            drawStatsContent(g, x, y, w, h, prof, Section.MANIAC, selectedManiac(), accent, now, mx, my, dt,
                swap * lastDir * 36 * k);
            g.pose().popPose();
        }
        g.disableScissor();
        fa = 1f;
    }

    private void drawStatsContent(GuiGraphics g, int x, int y, int w, int h, PlayerProfile prof, Section sec,
                                  ManiacArchetype arch, int accent, long now, int mx, int my, float dt, float slideX) {
        long ct = now - statsAt;
        float in = ease(ct, 0, 380);
        float save = fa;
        fa = save * (sec == Section.MANIAC ? in : 1f);
        g.pose().pushPose();
        g.pose().translate(slideX, 0f, 0f);

        int pad = Math.round(16 * k);
        int ix = x + pad, iw = w - 2 * pad;

        PlayerProfile.Survivor sv = prof == null ? new PlayerProfile.Survivor() : prof.survivor;
        PlayerProfile.Maniac mp = prof == null || arch == null ? new PlayerProfile.Maniac()
            : prof.maniacs.getOrDefault(arch.id(), new PlayerProfile.Maniac());
        boolean surv = sec == Section.SURVIVOR;
        int matches = surv ? sv.matches : mp.matches;
        int wins = surv ? sv.wins : mp.wins;
        int secAccent = surv ? ManiacUiTheme.BAR_FILL_NEUTRAL : ManiacUiTheme.BAR_FILL_DANGER;

        // Заголовок розділу.
        String who = surv ? tr("maniacmod.profile.survivor") : (arch == null ? "—" : arch.displayName());
        text(g, who.toUpperCase(Locale.ROOT), ix, y + pad, 0.8f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        text(g, tr("maniacmod.profile.stats").toUpperCase(Locale.ROOT), ix, y + pad + 11 * k, 1.4f * k,
            c(ManiacUiTheme.TEXT_TITLE, 1f), 0);
        String chip = Component.translatable("maniacmod.profile.matches_played", matches).getString().toUpperCase(Locale.ROOT);
        text(g, chip, ix + iw, y + pad + 4 * k, 0.9f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 2);
        int divY = y + pad + Math.round(31 * k);
        ManiacUiTheme.divider(g, ix, divY, iw);

        // Герой-блок.
        int heroY = divY + Math.round(12 * k);
        int heroH = Math.round(Math.min(104 * k, h * 0.26f));
        drawHero(g, ix, heroY, iw, heroH, surv, sv, mp, arch, matches, wins, secAccent, ct);

        // Плитки 4×2.
        List<Tile> tiles = surv ? survivorTiles(sv) : maniacTiles(mp);
        int stripTop = heroY + heroH + Math.round(10 * k);
        int stripH = surv || arch == null ? 0 : Math.round(44 * k);
        if (stripH > 0) drawTrapStrip(g, ix, stripTop, iw, stripH, arch, mp, ct, mx, my, secAccent,
            sectionAnim > 0.97f && Math.abs(slideX) < 2f && ct > 400L);
        int gridTop = stripTop + (stripH > 0 ? stripH + Math.round(10 * k) : Math.round(2 * k));
        int gridH = y + h - pad - gridTop;
        int cols = iw >= 460 * k ? 4 : 2;
        int rows = (tiles.size() + cols - 1) / cols;
        int tg = Math.round(8 * k);
        int tw = (iw - (cols - 1) * tg) / cols;
        int th = Math.min(Math.round(104 * k), (gridH - (rows - 1) * tg) / rows);
        boolean loading = prof == null;
        for (int i = 0; i < tiles.size(); i++) {
            int col = i % cols, row = i / cols;
            int tx = ix + col * (tw + tg);
            int ty = gridTop + row * (th + tg);
            boolean settled = surv ? sectionAnim < 0.03f : sectionAnim > 0.97f;
            settled &= Math.abs(slideX) < 2f && ct > 400L;
            tileRect[i][0] = tx; tileRect[i][1] = ty; tileRect[i][2] = settled ? tw : 0; tileRect[i][3] = th;
            boolean hover = settled && inside(mx, my, tileRect[i]);
            tileHover[i] = UiAnim.approach(tileHover[i], hover ? 1f : 0f, 14f, dt);
            if (hover) setTip(tiles.get(i));
            drawTile(g, tx, ty, tw, th, tiles.get(i), i, ct, tileHover[i], loading, now);
        }

        if (!loading && matches == 0) {
            text(g, tr(surv ? "maniacmod.profile.empty_survivor" : "maniacmod.profile.empty_maniac"),
                ix + iw / 2f, gridTop + gridH + 1, 0.85f * k, c(ManiacUiTheme.TEXT_DIM, 0.9f), 1);
        }
        g.pose().popPose();
        fa = save;
    }

    private List<Tile> survivorTiles(PlayerProfile.Survivor s) {
        List<Tile> t = new ArrayList<>();
        String tp = "maniacmod.profile.tip.s.";
        t.add(new Tile(Icon.PLAY, "matches", s.matches, false,
            Component.translatable("maniacmod.profile.sub.wins", s.wins).getString(), ManiacUiTheme.TEXT_ACCENT, tp + "matches"));
        t.add(new Tile(Icon.DOOR, "escapes", s.escapes, false,
            Component.translatable("maniacmod.profile.sub.percent", pct(s.escapes, s.matches)).getString(), ManiacTabTheme.OK, tp + "escapes"));
        t.add(new Tile(Icon.GEAR, "repair", s.repairSeconds, true,
            Component.translatable("maniacmod.profile.sub.avg", fmtTime(s.matches == 0 ? 0 : s.repairSeconds / s.matches)).getString(),
            ManiacTabTheme.CYAN, tp + "repair"));
        t.add(new Tile(Icon.HEART, "rescues", s.rescues, false,
            Component.translatable("maniacmod.profile.sub.teammates").getString(), ManiacTabTheme.BLOOD_HOT, tp + "rescues"));
        t.add(new Tile(Icon.HOOK, "hooked", s.hooked, false,
            Component.translatable("maniacmod.profile.sub.times").getString(), ManiacUiTheme.BORDER_BRIGHT, tp + "hooked"));
        t.add(new Tile(Icon.SHIELD, "hits_taken", s.hitsTaken, false,
            Component.translatable("maniacmod.profile.sub.endured").getString(), ManiacTabTheme.SILVER, tp + "hits_taken"));
        t.add(new Tile(Icon.CLOCK, "best_survival", s.bestSurvivedSeconds, true,
            Component.translatable("maniacmod.profile.sub.total", fmtTime(s.survivedSeconds)).getString(), ManiacTabTheme.SILVER_HI, tp + "best_survival"));
        t.add(new Tile(Icon.FLAME, "streak", s.bestWinStreak, false,
            Component.translatable("maniacmod.profile.sub.now", s.winStreak).getString(), ManiacTabTheme.WARN, tp + "streak"));
        return t;
    }

    private List<Tile> maniacTiles(PlayerProfile.Maniac m) {
        List<Tile> t = new ArrayList<>();
        String tp = "maniacmod.profile.tip.m.";
        t.add(new Tile(Icon.PLAY, "matches", m.matches, false,
            Component.translatable("maniacmod.profile.sub.wins", m.wins).getString(), ManiacUiTheme.TEXT_ACCENT, tp + "matches"));
        t.add(new Tile(Icon.TROPHY, "wins", m.wins, false,
            Component.translatable("maniacmod.profile.sub.percent", pct(m.wins, m.matches)).getString(), ManiacTabTheme.WARN, tp + "wins"));
        t.add(new Tile(Icon.BLADE, "downs", m.downs, false,
            Component.translatable("maniacmod.profile.sub.victims").getString(), ManiacTabTheme.BLOOD_HOT, tp + "downs"));
        t.add(new Tile(Icon.HOOK, "hooks", m.hooks, false,
            Component.translatable("maniacmod.profile.sub.hung").getString(), ManiacUiTheme.BORDER_BRIGHT, tp + "hooks"));
        t.add(new Tile(Icon.FIST, "hits_dealt", m.hitsDealt, false,
            Component.translatable("maniacmod.profile.sub.landed").getString(), ManiacTabTheme.CYAN, tp + "hits_dealt"));
        t.add(new Tile(Icon.CROWN, "best_downs", m.bestDowns, false,
            Component.translatable("maniacmod.profile.sub.record").getString(), ManiacTabTheme.WARN, tp + "best_downs"));
        t.add(new Tile(Icon.CLOCK, "time_played", m.playedSeconds, true,
            Component.translatable("maniacmod.profile.sub.avg", fmtTime(m.matches == 0 ? 0 : m.playedSeconds / m.matches)).getString(),
            ManiacTabTheme.SILVER_HI, tp + "time_played"));
        t.add(new Tile(Icon.FLAME, "streak", m.bestWinStreak, false,
            Component.translatable("maniacmod.profile.sub.now", m.winStreak).getString(), ManiacTabTheme.OK, tp + "streak"));
        return t;
    }

    /** Підказка до плитки: назва, що саме рахує лічильник, і поточне значення. */
    private void setTip(Tile tile) {
        hoverTip.clear();
        hoverTip.add(Component.literal(tr("maniacmod.profile.tile." + tile.labelKey()))
            .withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD));
        for (String line : tr(tile.tipKey()).split("\\n")) {
            hoverTip.add(Component.literal(line).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }

    private static int pct(long a, long b) {
        return b <= 0 ? 0 : Math.round(100f * a / b);
    }

    private void drawTile(GuiGraphics g, int x, int y, int w, int h, Tile tile, int index, long ct, float hv,
                          boolean loading, long now) {
        float in = ease(ct, 120 + index * 70L, 450);
        float save = fa;
        fa = save * in;
        int lift = Math.round(-2 * k * hv) + Math.round((1f - in) * 12 * k);
        int ty = y + lift;

        g.fill(x, ty, x + w, ty + h, c(GeneratorFuelUiTheme.mix(ManiacUiTheme.MENU_PANEL_DARK, tile.color(), 0.06f * hv), 1f));
        ManiacUiTheme.border1px(g, x, ty, w, h, c(GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, tile.color(), 0.2f + 0.7f * hv), 1f));
        g.fill(x, ty, x + 2, ty + Math.round(h * UiAnim.easeOutCubic(in)), c(tile.color(), 1f));

        int pad = Math.round(9 * k);
        int unit = Math.max(1, Math.round(1.5f * k));
        int box = 16 * unit + Math.round(10 * k);
        int bx = x + pad, by = ty + pad;
        g.fill(bx, by, bx + box, by + box, c(tile.color(), 0.10f + 0.10f * hv));
        ManiacUiTheme.border1px(g, bx, by, box, box, c(tile.color(), 0.35f));
        ProfileIcons.draw(g, tile.icon(), bx + (box - 16 * unit) / 2, by + (box - 16 * unit) / 2, unit,
            GeneratorFuelUiTheme.alpha(tile.color(), fa), hv);

        String label = tr("maniacmod.profile.tile." + tile.labelKey()).toUpperCase(Locale.ROOT);
        float lx = bx + box + 8 * k;
        float lscale = 0.78f * k;
        float maxW = x + w - pad - lx;
        if (font.width(label) * lscale > maxW) lscale *= maxW / (font.width(label) * lscale);
        text(g, label, lx, by + 1 * k, lscale, c(ManiacUiTheme.TEXT_DIM, 1f), 0);

        String value;
        if (loading) {
            value = "—";
        } else {
            long shown = Math.round(tile.value() * ease(ct, 200 + index * 70L, 900));
            value = tile.time() ? fmtTime(shown) : String.valueOf(shown);
        }
        float vs = (tile.time() ? 1.15f : 1.7f) * k;
        float vw = font.width(value) * vs;
        if (vw > maxW) vs *= maxW / vw;
        text(g, value, lx, by + 12 * k, vs, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);

        text(g, tile.sub(), x + pad, ty + h - pad - 7 * k, 0.8f * k, c(ManiacUiTheme.TEXT_BODY, 0.85f), 0);

        if (loading) {
            float sh = ((now % 1400L) / 1400f) * 1.6f - 0.3f;
            int sx = x + Math.round(sh * w);
            g.fill(Math.max(x + 1, sx - 10), ty + 1, Math.min(x + w - 1, sx + 10), ty + h - 1, c(0x20FFFFFF, 1f));
        }
        fa = save;
    }

    // ── Смуга пасток ─────────────────────────────────────────────────────

    /**
     * Статистика пасток вибраного маніяка: по клітинці на кожну його пастку — «поставлено / жертв».
     * Дані — {@code PlayerProfile.Maniac.traps}; які пастки показувати, вирішує {@code arch.traps()}.
     */
    private void drawTrapStrip(GuiGraphics g, int x, int y, int w, int h, ManiacArchetype arch,
                               PlayerProfile.Maniac mp, long ct, int mx, int my, int accent, boolean settled) {
        float in = ease(ct, 100, 450);
        float save = fa;
        fa = save * in;

        List<TrapArchetype> list = arch.traps();
        int n = Math.min(list.size(), trapRect.length);
        for (int[] r : trapRect) r[2] = 0;

        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));

        int pad = Math.round(8 * k);
        text(g, tr("maniacmod.profile.traps").toUpperCase(Locale.ROOT), x + pad, y + 5 * k, 0.75f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        String totals = Component.translatable("maniacmod.profile.traps.totals", mp.trapsPlaced(), mp.trapsCaught()).getString();
        text(g, totals, x + w - pad, y + 5 * k, 0.75f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 2);

        if (n == 0) { fa = save; return; }
        int top = y + Math.round(16 * k);
        int gap = Math.round(6 * k);
        int cw = (w - 2 * pad - (n - 1) * gap) / n;
        int ch = h - Math.round(16 * k) - Math.round(5 * k);
        for (int i = 0; i < n; i++) {
            TrapArchetype ta = list.get(i);
            PlayerProfile.Trap st = mp.traps.get(ta.id());
            long placed = st == null ? 0 : st.placed, caught = st == null ? 0 : st.caught;
            int cx = x + pad + i * (cw + gap);
            trapRect[i][0] = cx; trapRect[i][1] = top; trapRect[i][2] = settled ? cw : 0; trapRect[i][3] = ch;
            boolean hover = settled && inside(mx, my, trapRect[i]);

            g.fill(cx, top, cx + cw, top + ch, c(GeneratorFuelUiTheme.mix(ManiacUiTheme.SLOT_FILL, accent, hover ? 0.12f : 0f), 1f));
            ManiacUiTheme.border1px(g, cx, top, cw, ch, c(GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, accent, hover ? 0.8f : 0.2f), 1f));
            g.fill(cx, top, cx + 2, top + ch, c(accent, 1f));

            String name = tr("maniacmod.trap.name." + ta.id());
            float ns = 0.75f * k;
            float nw = font.width(name) * ns, maxW = cw - 8 * k;
            if (nw > maxW) ns *= maxW / nw;
            text(g, name, cx + 6 * k, top + 3 * k, ns, c(ManiacUiTheme.TEXT_BODY, 1f), 0);

            String val = placed + " / " + caught;
            float vs = 1.0f * k;
            float vw = font.width(val) * vs;
            if (vw > maxW) vs *= maxW / vw;
            text(g, val, cx + 6 * k, top + 3 * k + 10 * k, vs, c(ManiacUiTheme.TEXT_TITLE, 1f), 0);

            if (hover) {
                hoverTip.clear();
                hoverTip.add(Component.literal(name).withStyle(net.minecraft.ChatFormatting.WHITE, net.minecraft.ChatFormatting.BOLD));
                hoverTip.add(Component.translatable("maniacmod.profile.traps.tip.placed", placed).withStyle(net.minecraft.ChatFormatting.GRAY));
                hoverTip.add(Component.translatable("maniacmod.profile.traps.tip.caught", caught).withStyle(net.minecraft.ChatFormatting.GRAY));
                hoverTip.add(Component.translatable("maniacmod.profile.traps.tip.rate",
                    placed == 0 ? 0 : Math.round(100f * caught / placed)).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
        }
        fa = save;
    }

    // ── Герой-блок ───────────────────────────────────────────────────────

    private void drawHero(GuiGraphics g, int x, int y, int w, int h, boolean surv, PlayerProfile.Survivor sv,
                          PlayerProfile.Maniac mp, ManiacArchetype arch, int matches, int wins, int secAccent, long ct) {
        float in = ease(ct, 60, 500);
        float save = fa;
        fa = save * in;
        g.fill(x, y, x + w, y + h, c(ManiacUiTheme.MENU_PANEL_DARK, 1f));
        ManiacUiTheme.border1px(g, x, y, w, h, c(ManiacUiTheme.BORDER, 1f));

        // Кільце відсотка перемог.
        int R = Math.round(Math.min(h / 2f - 8 * k, 38 * k));
        int rcx = x + Math.round(14 * k) + R, rcy = y + h / 2;
        float frac = matches == 0 ? 0f : wins / (float) matches;
        float sweep = frac * ease(ct, 200, 1300);
        drawRing(g, rcx, rcy, R, Math.max(3, Math.round(4.5f * k)), sweep, secAccent);
        String p = pct(wins, matches) + "%";
        text(g, p, rcx, rcy - 7 * k, 1.35f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 1);
        text(g, tr("maniacmod.profile.winrate").toUpperCase(Locale.ROOT), rcx, rcy + 7 * k, 0.7f * k,
            c(ManiacUiTheme.TEXT_DIM, 1f), 1);

        int rx = rcx + R + Math.round(22 * k);
        int rw = x + w - rx - Math.round(16 * k);
        if (surv) {
            drawOutcomeBar(g, rx, y + Math.round(14 * k), rw, h, sv, ct);
        } else if (arch != null) {
            drawTraits(g, rx, y + Math.round(10 * k), rw, h, arch, ct);
        }
        fa = save;
    }

    /** Кільце зі «стартом» зверху; сегменти — квадрати вздовж кола. */
    private void drawRing(GuiGraphics g, int cx, int cy, int R, int thick, float frac, int color) {
        int steps = Math.max(60, Math.round(R * 6.28f * 1.4f));
        for (int i = 0; i < steps; i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / steps;
            int px = cx + (int) Math.round(Math.cos(a) * R) - thick / 2;
            int py = cy + (int) Math.round(Math.sin(a) * R) - thick / 2;
            boolean on = i / (float) steps <= frac;
            g.fill(px, py, px + thick, py + thick, c(on ? color : ManiacUiTheme.BORDER, on ? 1f : 0.8f));
        }
        if (frac > 0.005f) {
            double a = -Math.PI / 2 + 2 * Math.PI * frac;
            int px = cx + (int) Math.round(Math.cos(a) * R) - thick / 2;
            int py = cy + (int) Math.round(Math.sin(a) * R) - thick / 2;
            g.fill(px - 1, py - 1, px + thick + 1, py + thick + 1, c(0xFFFFFFFF, 0.9f));
        }
    }

    private void drawOutcomeBar(GuiGraphics g, int x, int y, int w, int h, PlayerProfile.Survivor s, long ct) {
        text(g, tr("maniacmod.profile.outcomes").toUpperCase(Locale.ROOT), x, y, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        int total = Math.max(1, s.escapes + s.eliminated);
        float grow = ease(ct, 300, 1100);
        int bh = Math.round(10 * k);
        int by = y + Math.round(13 * k);
        g.fill(x, by, x + w, by + bh, c(ManiacUiTheme.BAR_BG, 1f));
        ManiacUiTheme.border1px(g, x, by, w, bh, c(ManiacUiTheme.BAR_FRAME, 0.5f));
        int iw = w - 2;
        int ew = Math.round(iw * (s.escapes / (float) total) * grow);
        int dw = Math.round(iw * (s.eliminated / (float) total) * grow);
        if (s.escapes + s.eliminated > 0) {
            g.fill(x + 1, by + 1, x + 1 + ew, by + bh - 1, c(ManiacTabTheme.OK, 1f));
            g.fill(x + 1 + ew, by + 1, x + 1 + ew + dw, by + bh - 1, c(ManiacUiTheme.BAR_FILL_DANGER, 1f));
            g.fill(x + 1, by + 1, x + 1 + ew + dw, by + 2, c(0x30FFFFFF, 1f));
        }
        int ly = by + bh + Math.round(8 * k);
        legend(g, x, ly, ManiacTabTheme.OK, tr("maniacmod.profile.escaped"), s.escapes, pct(s.escapes, s.escapes + s.eliminated));
        legend(g, x + w / 2, ly, ManiacUiTheme.BAR_FILL_DANGER, tr("maniacmod.profile.died"), s.eliminated,
            pct(s.eliminated, s.escapes + s.eliminated));
        long avg = s.matches == 0 ? 0 : s.survivedSeconds / s.matches;
        text(g, Component.translatable("maniacmod.profile.avg_survival", fmtTime(avg)).getString(), x,
            ly + 14 * k, 0.85f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
    }

    private void legend(GuiGraphics g, int x, int y, int color, String label, int value, int pct) {
        int sq = Math.max(4, Math.round(5 * k));
        g.fill(x, y + 1, x + sq, y + 1 + sq, c(color, 1f));
        text(g, label + " " + value + " (" + pct + "%)", x + sq + 5 * k, y, 0.85f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
    }

    private void drawTraits(GuiGraphics g, int x, int y, int w, int h, ManiacArchetype a, long ct) {
        text(g, tr("maniacmod.profile.traits").toUpperCase(Locale.ROOT), x, y, 0.75f * k, c(ManiacUiTheme.TEXT_DIM, 1f), 0);
        double speed = a.declaredSpeedMultiplier();
        int dmg = a.declaredAttackDamage();
        double range = a.declaredAttackRangeBlocks();
        int cd = a.declaredAttackCooldownTicks();
        String[] keys = {"speed", "damage", "range", "tempo"};
        String[] vals = {String.format(Locale.ROOT, "×%.2f", speed), String.valueOf(dmg),
            String.format(Locale.ROOT, "%.1f %s", range, tr("maniacmod.profile.unit.blocks")),
            String.format(Locale.ROOT, "%.1f %s", cd / 20.0, tr("maniacmod.profile.unit.s"))};
        float[] fr = {
            (float) ((speed - 0.8) / 0.8), dmg / 100f, (float) (range / 6.0), 1f - (cd - 20) / 260f};
        int rowH = Math.round(Math.min(20 * k, (h - 20 * k) / 4f));
        for (int i = 0; i < 4; i++) {
            int ry = y + Math.round(13 * k) + i * rowH;
            text(g, tr("maniacmod.profile.trait." + keys[i]), x, ry, 0.8f * k, c(ManiacUiTheme.TEXT_BODY, 1f), 0);
            text(g, vals[i], x + w, ry, 0.8f * k, c(ManiacUiTheme.TEXT_TITLE, 1f), 2);
            bar(g, x, ry + Math.round(8 * k), w, Math.max(3, Math.round(4 * k)),
                UiAnim.clamp01(fr[i]) * ease(ct, 250 + i * 90L, 900), ManiacUiTheme.BAR_FILL_DANGER);
        }
    }

    // ── Ввід ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (ranksOpen) {                 // клік будь-де (і по кнопці «Ранги») закриває панель
            ranksOpen = false;
            UiAnim.playClick();
            return true;
        }
        if (button == 0) {
            if (inside(mx, my, ranksBtnRect)) { toggleRanks(); return true; }
            if (inside(mx, my, historyBtnRect)) { openHistory(); return true; }
            if (inside(mx, my, backRect)) { UiAnim.playClick(); onClose(); return true; }
            for (int i = 0; i < 2; i++) {
                if (inside(mx, my, sectionRect[i])) { setSection(i == 1 ? Section.MANIAC : Section.SURVIVOR); return true; }
            }
            if (section == Section.MANIAC && maniacs.size() > 1) {
                if (arrowRect[0][2] > 0 && inside(mx, my, arrowRect[0])) { shift(-1); return true; }
                if (arrowRect[1][2] > 0 && inside(mx, my, arrowRect[1])) { shift(1); return true; }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Та сама клавіша, що відкрила профіль, його й закриває (як ванільний інвентар).
        if (ranksOpen) {                 // панель «Ранги»: Esc / R / клавіша профілю закривають лише її
            if (key == GLFW.GLFW_KEY_1) { setSection(Section.SURVIVOR); return true; }   // 1/2/Tab — драбина іншої ролі
            if (key == GLFW.GLFW_KEY_2) { setSection(Section.MANIAC); return true; }
            if (key == GLFW.GLFW_KEY_TAB) { setSection(section == Section.SURVIVOR ? Section.MANIAC : Section.SURVIVOR); return true; }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_R
                || com.log_to_kot.maniacmod.client.ManiacKeybinds.PROFILE.matches(key, scan)) {
                ranksOpen = false;
                UiAnim.playClick();
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) { toggleRanks(); return true; }
        if (key == GLFW.GLFW_KEY_H) { openHistory(); return true; }
        if (com.log_to_kot.maniacmod.client.ManiacKeybinds.PROFILE.matches(key, scan)) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_1) { setSection(Section.SURVIVOR); return true; }
        if (key == GLFW.GLFW_KEY_2) { setSection(Section.MANIAC); return true; }
        if (key == GLFW.GLFW_KEY_TAB) { setSection(section == Section.SURVIVOR ? Section.MANIAC : Section.SURVIVOR); return true; }
        if (section == Section.MANIAC && maniacs.size() > 1) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) { shift(-1); return true; }
            if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) { shift(1); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    private void setSection(Section s) {
        if (s == section) return;
        section = s;
        statsAt = System.currentTimeMillis();
        UiAnim.playClick();
    }

    private void shift(int dir) {
        mTarget += dir;
        lastDir = dir;
        statsAt = System.currentTimeMillis();
        UiAnim.playClick();
    }
}

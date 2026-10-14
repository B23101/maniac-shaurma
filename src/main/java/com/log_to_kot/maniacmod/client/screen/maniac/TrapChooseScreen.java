package com.log_to_kot.maniacmod.client.screen.maniac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ManiacKeybinds;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.traps.TrapChoosePacket;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import com.log_to_kot.maniacmod.traps.TrapRegistry;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Екран вибору пасток на початку матчу — БЕЗ панелі й фону: лише
 * затемнення гри, а поверх нього іконки пасток, що «висять» у повітрі.
 *
 * ── Композиція (зверху вниз, усе по центру) ──────────────────────────
 *   заголовок капсом + тонка лінія, що розростається з центру;
 *   лічильник — {@code limit} квадратиків (заповнюється при виборі);
 *   ряд пасток: іконка 64×64 (рівно ×2 від текстури 32×32 — без мильних
 *   пікселів), під нею назва, під назвою — клавіша слота (5/6/7);
 *   опис пастки під курсором; кнопка «Підтвердити» (лише рамка й текст).
 *
 * ── Стан кожної картки — одне число ──────────────────────────────────
 * {@code hover[i]} і {@code pick[i]} (0..1) наближаються до цілі
 * експонентою ({@link UiAnim#approach}) за РЕАЛЬНИМ часом кадру, тому
 * рух однаковий на 30 і на 240 FPS і не смикається при швидкому русі
 * миші. З цих двох чисел виводиться ВСЕ: підйом іконки, яскравість,
 * розкриття скоб, колір назви. Жодних ступінчастих if-ів по кадрах.
 *
 * ── Підтвердження ────────────────────────────────────────────────────
 *   1) обрані картки пливуть до центру в порядку клавіш, решта гаснуть;
 *   2) коротка пауза — гравець бачить підсумок;
 *   3) весь екран плавно гасне;
 *   4) лише ПІСЛЯ цього шлеться {@link TrapChoosePacket}. Екран закриє
 *      {@code ClientPacketHandler#onTrapLoadout}, коли сервер відповість
 *      застосованим набором.
 *
 * ── Випадковий вибір ─────────────────────────────────────────────────
 * Кнопка ліворуч від «Підтвердити» (і клавіша R) замість гравця добирає
 * випадковий набір рівно на {@code limit} пасток. Це лише зручність КЛІЄНТА:
 * вона так само заповнює {@link #selected}, як клік по картках, і далі все
 * йде тим самим шляхом підтвердження — серверу «випадковість» невідома,
 * він отримує звичайний {@link TrapChoosePacket}, тож жодного нового
 * пакета чи серверної перевірки не потрібно. Повторне натискання кидає
 * жереб заново. При наведенні на кнопку внизу показується її опис.
 *
 * ── Esc ──────────────────────────────────────────────────────────────
 * Дозволено, але пасток тоді НЕ буде, доки вибір не підтверджено: автовибору
 * немає. Меню відкривається знову клавішею пастки (5/6/7). Гра не чекає на вибір.
 */
public final class TrapChooseScreen extends Screen {

    // ── Розміри ──────────────────────────────────────────────────────
    private static final int ICON = 64;
    private static final int BOX = ICON + 16;          // область під іконку + скоби
    private static final int CELL_MAX = 130;
    private static final int CELL_MIN = 84;
    private static final int LIFT = 6;                 // на скільки піднімається картка при виборі/наведенні
    private static final int BADGE = 16;               // квадрат-бейдж із клавішею слота
    private static final float SELECT_ANIM_S = 0.55f;  // тривалість «попу» вибору, с

    private static final int BTN_W = 160;
    private static final int BTN_H = 22;

    // ── Таймінги, мс ─────────────────────────────────────────────────
    private static final float OPEN_MS = 420f;
    private static final float CARD_STAGGER_MS = 70f;
    private static final float CARD_APPEAR_MS = 320f;
    private static final float GATHER_MS = 520f;       // обрані пливуть до центру
    private static final float HOLD_MS = 260f;         // пауза на підсумок
    private static final float FADE_OUT_MS = 260f;

    // ── Дані ─────────────────────────────────────────────────────────
    private List<String> trapIds;
    private int limit;
    /** Порядок вибору = порядок клавіш 5/6/7. */
    private final Set<String> selected = new LinkedHashSet<>();

    // ── Анімаційний стан ─────────────────────────────────────────────
    private float[] hover = new float[0];
    private float[] pick = new float[0];
    private float[] pulse = new float[0];
    /** 0..1 — час від моменту вибору (штамп номера, «поп» іконки). 1 = анімація завершена. */
    private float[] selT = new float[0];
    /** 1→0 — білий спалах по картці в момент вибору. */
    private float[] flash = new float[0];
    /** Іскри від вибору/підтвердження (екранні координати). */
    private final UiFx.Sparks sparks = new UiFx.Sparks(220);
    /** 1→0 — трусіння екрана від вибору (дрібне, щоб не нудило). */
    private float shake;
    private float btnHover;
    private float btnActive;
    private float randHover;
    private float randActive;
    /** 1→0 — «кубик» на кнопці випадку труситься після натискання. */
    private float diceT;
    private final long[] frameClock = new long[1];

    private long openedAt;
    private long confirmedAt = -1;
    private List<String> confirmedOrder = List.of();
    private boolean packetSent;

    private int hoveredCard = -1;
    private boolean hoverBtn;
    private boolean hoverRand;

    // Геометрія, перерахована на кожен кадр (залежить лише від розміру вікна).
    private int rowY;
    private int cell;
    private int btnX, btnY;
    private int randX;
    private int btnW = BTN_W;

    public TrapChooseScreen(List<String> trapIds, int limit) {
        super(Component.translatable("maniacmod.trapchoose.title"));
        setCatalog(trapIds, limit);
    }

    /** Новий каталог прийшов, поки екран відкритий (реконект). */
    public void onCatalogUpdated(List<String> trapIds, int limit) {
        setCatalog(trapIds, limit);
        selected.retainAll(this.trapIds);
    }

    private void setCatalog(List<String> ids, int limit) {
        this.trapIds = List.copyOf(ids);
        this.limit = Math.max(1, limit);
        int n = trapIds.size();
        hover = new float[n];
        pick = new float[n];
        pulse = new float[n];
        selT = new float[n];
        java.util.Arrays.fill(selT, 1f);
        flash = new float[n];
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = Util.getMillis();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true; // меню можна відкрити знову клавішею 5/6/7, поки вибір не підтверджено
    }

    /** Гра НЕ на паузі: в одиночному світі пауза завісила б сервер, і відповідь на вибір не прийшла б. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ═════════════════════════════════════════════════════════════════
    //  Рендер
    // ═════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = UiAnim.frameDt(frameClock);

        float openT = UiAnim.clamp01((now - openedAt) / OPEN_MS);
        float open = UiAnim.easeOutCubic(openT);

        // Фази після підтвердження.
        float gather = 0f;   // 0..1 — картки їдуть до центру
        float fadeOut = 0f;  // 0..1 — весь екран гасне
        if (confirmedAt >= 0) {
            float since = now - confirmedAt;
            gather = UiAnim.easeInOutCubic(since / GATHER_MS);
            fadeOut = UiAnim.easeInOutCubic((since - GATHER_MS - HOLD_MS) / FADE_OUT_MS);
            if (since >= GATHER_MS + HOLD_MS + FADE_OUT_MS && !packetSent) {
                packetSent = true;
                sendChoice();
            }
        }
        float life = open * (1f - fadeOut);
        // Ті елементи, що зникають при підтвердженні (кнопка, опис, лічильник).
        float ui = life * (1f - UiAnim.clamp01(gather * 2.2f));

        layout();
        updateHover(mouseX, mouseY);
        stepAnimation(dt);

        drawBackdrop(g, life);

        // Дрібне трусіння всього вмісту при виборі: згасає за ~0.25 с.
        g.pose().pushPose();
        if (shake > 0.01f) {
            float k = shake * shake;
            g.pose().translate(
                (float) (Math.random() - 0.5) * 3.2f * k,
                (float) (Math.random() - 0.5) * 3.2f * k, 0f);
        }

        drawTitle(g, life, open);
        drawPips(g, ui);

        for (int i = 0; i < trapIds.size(); i++) drawCard(g, i, now, life, gather);

        sparks.tickAndRender(g, dt, 160f);

        drawDescription(g, ui);
        drawButton(g, ui, dt);
        g.pose().popPose();
    }

    /** Лише затемнення: рівна темрява + м'яке потемніння до країв. Жодних панелей і кольорових плям. */
    private void drawBackdrop(GuiGraphics g, float life) {
        g.fill(0, 0, width, height, GeneratorFuelUiTheme.alpha(0xFF04060A, 0.72f * life));
        int edge = Math.max(40, height / 4);
        int c = GeneratorFuelUiTheme.alpha(0xFF000000, 0.40f * life);
        g.fillGradient(0, 0, width, edge, c, 0x00000000);
        g.fillGradient(0, height - edge, width, height, 0x00000000, c);
    }

    private void drawTitle(GuiGraphics g, float life, float open) {
        String text = getTitle().getString().toUpperCase(Locale.ROOT);
        int cx = width / 2;
        int ty = titleY();

        // Розріджений капс: літери окремо, щоб заголовок читався «технічним» написом.
        int spacing = 2;
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += font.width(String.valueOf(text.charAt(i))) + spacing;
        total -= spacing;
        int x = cx - total / 2;
        int col = GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_TITLE, life);
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            if (visible(col)) g.drawString(font, ch, x, ty, col, true);
            x += font.width(ch) + spacing;
        }

        // Лінія росте з центру в обидва боки.
        int half = Math.round((total / 2f + 18f) * open);
        int ly = ty + font.lineHeight + 6;
        int lc = GeneratorFuelUiTheme.alpha(ManiacUiTheme.BORDER_BRIGHT, 0.55f * life);
        g.fill(cx - half, ly, cx + half, ly + 1, lc);
        g.fill(cx - 3, ly, cx + 3, ly + 1, GeneratorFuelUiTheme.alpha(GeneratorFuelUiTheme.HIGHLIGHT, 0.9f * life));
    }

    /** limit квадратиків: порожній контур → заповнений, коли відповідна пастка обрана. */
    private void drawPips(GuiGraphics g, float ui) {
        if (ui <= 0.01f) return;
        int size = 8, gap = 6;
        int total = limit * size + (limit - 1) * gap;
        int x = width / 2 - total / 2;
        int y = pipsY();
        int filled = selected.size();
        for (int i = 0; i < limit; i++) {
            int px = x + i * (size + gap);
            boolean on = i < filled;
            ManiacUiTheme.border1px(g, px, y, size, size,
                GeneratorFuelUiTheme.alpha(on ? ManiacUiTheme.MENU_ACCENT_GOLD : ManiacUiTheme.BORDER_BRIGHT, (on ? 1f : 0.55f) * ui));
            if (on) g.fill(px + 2, y + 2, px + size - 2, y + size - 2,
                GeneratorFuelUiTheme.alpha(ManiacUiTheme.MENU_ACCENT_GOLD, ui));
        }
    }

    private void drawCard(GuiGraphics g, int i, long now, float life, float gather) {
        String id = trapIds.get(i);
        boolean chosen = selected.contains(id);
        boolean full = !chosen && selected.size() >= limit && confirmedAt < 0;
        float h = hover[i], p = pick[i];

        // Поява: підйом знизу + проявлення, каскадом зліва направо.
        float appear = UiAnim.easeOutCubic((now - openedAt - i * CARD_STAGGER_MS) / CARD_APPEAR_MS);
        float alpha = appear * life;
        if (full) alpha *= 0.55f; // ліміт вичерпано — решта картки тьмяніють

        // Позиція: у сітці або (після підтвердження) у центрованому ряду обраних.
        float cx = cellCenterX(i);
        float dropY = (1f - appear) * 18f;
        if (confirmedAt >= 0) {
            if (chosen) {
                int slot = confirmedOrder.indexOf(id);
                cx = Mth.lerp(gather, cx, gatherCenterX(Math.max(0, slot)));
            } else {
                alpha *= 1f - UiAnim.clamp01(gather * 1.6f);
                dropY += gather * 14f;
            }
        }
        if (alpha <= 0.01f) return;

        int lift = Math.round(Mth.clamp(h * 0.5f + p * 0.5f, 0f, 1f) * LIFT);
        int icx = Math.round(cx);
        int top = rowY - 6 + Math.round(dropY) - lift;
        int panelW = Math.min(BOX + 16, cell - 8);
        int panelH = (BOX + 8 + font.lineHeight + 3 + font.lineHeight) + 12;
        int left = icx - panelW / 2;

        // ── Панель картки ────────────────────────────────────────
        int bg = GeneratorFuelUiTheme.mix(0xFF080A0D, 0xFF12161C, Math.max(h, p));
        g.fill(left, top, left + panelW, top + panelH, GeneratorFuelUiTheme.alpha(bg, 0.86f * alpha));
        if (p > 0.02f) {
            UiFx.innerGlow(g, left, top, panelW, panelH, 0xC2C9D6, 0.55f * p * alpha, 6);
        }
        int border = GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER,
            chosen ? ManiacUiTheme.MENU_ACCENT_GOLD : ManiacUiTheme.BORDER_BRIGHT, Math.max(h * 0.8f, p));
        ManiacUiTheme.border1px(g, left, top, panelW, panelH, GeneratorFuelUiTheme.alpha(border, alpha));
        // Верхня акцентна лінія: росте з центру при наведенні/виборі.
        float lineK = Math.max(h * 0.6f, p);
        int halfLine = Math.round(panelW / 2f * lineK);
        if (halfLine > 0) {
            g.fill(icx - halfLine, top, icx + halfLine, top + 2,
                GeneratorFuelUiTheme.alpha(chosen ? 0xFFFFFFFF : ManiacUiTheme.MENU_ACCENT_GOLD, alpha));
        }

        // ── Іконка: яскравість + «поп» при виборі ────────────────
        float bright = Mth.lerp(Math.max(h, p), 0.55f, 1.0f);
        float st = selT[i];
        float pop = (float) Math.sin(Math.min(1f, st) * Math.PI) * 0.20f * (1f - st * 0.35f);
        float scale = 1f + 0.05f * h + pop;
        int ix = icx - ICON / 2;
        int iy = top + 8 + 6;
        ResourceLocation tex = new ResourceLocation(ManiacMod.MOD_ID, "textures/gui/traps/" + id + ".png");
        g.pose().pushPose();
        g.pose().translate(icx, iy + ICON / 2f, 0f);
        g.pose().scale(scale, scale, 1f);
        g.pose().translate(-icx, -(iy + ICON / 2f), 0f);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(bright, bright, bright, alpha);
        g.blit(tex, ix, iy, ICON, ICON, 0f, 0f, 32, 32, 32, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        g.pose().popPose();

        // ── Ефекти вибору ────────────────────────────────────────
        // Два кільця-ударні хвилі зі зсувом у часі.
        if (pulse[i] > 0.02f) {
            float t1 = 1f - pulse[i];
            float t2 = Mth.clamp(t1 - 0.18f, 0f, 1f) / 0.82f;
            GeneratorFuelUiTheme.pulseRing(g, left, top, panelW, panelH, 0xFFFFFFFF, t1, 22);
            GeneratorFuelUiTheme.pulseRing(g, left, top, panelW, panelH, ManiacUiTheme.MENU_ACCENT_GOLD, t2, 40);
        }
        // Біле «пекле» спалаху по панелі + промінь, що пробігає зліва направо.
        if (flash[i] > 0.02f) {
            g.fill(left + 1, top + 1, left + panelW - 1, top + panelH - 1,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.38f * flash[i] * flash[i] * alpha));
            UiFx.scanBeam(g, left, top, panelW, panelH, 1f - flash[i], 8, 0xFFFFFF, 150);
        }
        // Скоби: при наведенні розведені, при виборі стягнуті.
        float open = Mth.clamp(h * (1f - p), 0f, 1f);
        float bracketA = Math.max(h * 0.55f, p) * alpha;
        if (bracketA > 0.02f) {
            int col = GeneratorFuelUiTheme.alpha(chosen ? 0xFFFFFFFF : ManiacUiTheme.BORDER_BRIGHT, bracketA);
            UiFx.brackets(g, left, top, panelW, panelH, col, 8, open, 5);
        }
        if (p > 0.02f) {
            UiFx.glow(g, left, top, panelW, panelH, 0xC2C9D6, 0.34f * p * alpha, 5);
        }

        // ── Назва ────────────────────────────────────────────────
        int nameY = top + 8 + 6 + ICON + 8;
        String name = I18n.get("maniacmod.trap.name." + id);
        int nameCol = GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_DIM, ManiacUiTheme.TEXT_TITLE, Math.max(h, p));
        int nc = GeneratorFuelUiTheme.alpha(nameCol, alpha);
        if (visible(nc)) g.drawCenteredString(font, name, icx, nameY, nc);

        // ── Статус під назвою: «ОБРАНО» ──────────────────────────
        if (chosen && p > 0.05f) {
            int kc = GeneratorFuelUiTheme.alpha(ManiacUiTheme.MENU_ACCENT_GOLD, alpha * p);
            if (visible(kc)) g.drawCenteredString(font,
                I18n.get("maniacmod.trapchoose.selected").toUpperCase(Locale.ROOT),
                icx, nameY + font.lineHeight + 3, kc);
        }

        // ── Бейдж-«штамп» із клавішею слота (правий верхній кут) ─
        if (chosen) {
            float stampT = UiAnim.clamp01(st / 0.5f);
            float stampS = 1f + (1f - UiAnim.easeOutBack(stampT, 1.2f)) * 1.6f;
            int order = confirmedAt >= 0 ? confirmedOrder.indexOf(id) : indexInSelection(id);
            float bxc = left + panelW - BADGE / 2f - 4;
            float byc = top + BADGE / 2f + 4;
            float ba = alpha * UiAnim.clamp01(stampT * 3f);
            g.pose().pushPose();
            g.pose().translate(bxc, byc, 0f);
            g.pose().scale(stampS, stampS, 1f);
            int half = BADGE / 2;
            g.fill(-half, -half, half, half, GeneratorFuelUiTheme.alpha(0xFFE8ECF4, ba));
            ManiacUiTheme.border1px(g, -half, -half, BADGE, BADGE, GeneratorFuelUiTheme.alpha(0xFFFFFFFF, ba));
            String label = keyLabel(order);
            int lc = GeneratorFuelUiTheme.alpha(0xFF0A0C10, ba);
            if (visible(lc)) g.drawString(font, label, -font.width(label) / 2, -font.lineHeight / 2 + 1, lc, false);
            g.pose().popPose();
        }
    }

    private void drawDescription(GuiGraphics g, float ui) {
        if (ui <= 0.01f) return;
        String text;
        boolean info = true;
        if (hoveredCard >= 0) {
            String id = trapIds.get(hoveredCard);
            String key = "maniacmod.trap.desc." + id;
            text = I18n.exists(key) ? I18n.get(key) : I18n.get("maniacmod.trap.name." + id);
            String shape = shapeLine(id);
            if (shape != null) text += "\n" + shape;
        } else if (hoverRand) {
            text = I18n.get("maniacmod.trapchoose.random.desc");
        } else {
            info = false;
            text = selected.isEmpty()
                ? I18n.get("maniacmod.trapchoose.hint")
                : I18n.get("maniacmod.trapchoose.hint.keys");
        }
        int maxW = Math.min(width - 60, 420);
        int y = descY();
        int col = GeneratorFuelUiTheme.alpha(info ? ManiacUiTheme.TEXT_BODY : ManiacUiTheme.TEXT_DIM, ui);
        int dim = GeneratorFuelUiTheme.alpha(ManiacUiTheme.TEXT_DIM, ui);
        String[] paragraphs = text.split("\n");
        for (int p = 0; p < paragraphs.length; p++) {
            // Другий абзац (спосіб розміщення) — тьмяніший, щоб не змішувався з описом.
            int c = p == 0 ? col : dim;
            for (var line : font.split(Component.literal(paragraphs[p]), maxW)) {
                if (visible(c)) g.drawCenteredString(font, line, width / 2, y, c);
                y += font.lineHeight + 2;
            }
        }
    }

    /** Один рядок про те, як ставиться пастка (одна точка чи дві); {@code null}, якщо невідомо. */
    private static String shapeLine(String trapId) {
        try {
            TrapArchetype.PlacementShape shape = TrapRegistry.get(trapId).placementShape();
            return I18n.get(shape == TrapArchetype.PlacementShape.TWO_POINTS
                ? "maniacmod.trapchoose.shape.two"
                : "maniacmod.trapchoose.shape.single");
        } catch (RuntimeException unknown) {
            return null; // пастки немає в клієнтському реєстрі — просто без рядка
        }
    }

    /** Кнопки — без заливки: рамка, текст і скоби. «Підтвердити» неактивна, поки нічого не обрано. */
    private void drawButton(GuiGraphics g, float ui, float dt) {
        if (ui <= 0.01f) return;
        drawOneButton(g, ui, btnX, btnY, btnW, I18n.get("maniacmod.trapchoose.confirm"),
            btnHover, btnActive, canConfirm(), false);
        drawOneButton(g, ui, randX, btnY, btnW, I18n.get("maniacmod.trapchoose.random"),
            randHover, randActive, canRandomize(), true);
    }

    private void drawOneButton(GuiGraphics g, float ui, int x, int y, int w, String text,
                               float hov, float active, boolean enabled, boolean dice) {
        float a = ui * (enabled ? 1f : 0.35f);
        int h = BTN_H;

        int border = GeneratorFuelUiTheme.mix(ManiacUiTheme.BORDER, ManiacUiTheme.BORDER_BRIGHT, hov);
        ManiacUiTheme.border1px(g, x, y, w, h, GeneratorFuelUiTheme.alpha(border, a));
        if (hov > 0.02f && enabled) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                GeneratorFuelUiTheme.alpha(0xFFC2C9D6, 0.08f * hov * ui));
            UiFx.brackets(g, x, y, w, h, GeneratorFuelUiTheme.alpha(ManiacUiTheme.MENU_ACCENT_GOLD, hov * ui),
                6, hov, 4);
        }
        if (active > 0.02f) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1,
                GeneratorFuelUiTheme.alpha(0xFFFFFFFF, 0.18f * active * ui));
        }
        String label = text.toUpperCase(Locale.ROOT);
        int tc = GeneratorFuelUiTheme.mix(ManiacUiTheme.TEXT_BODY, ManiacUiTheme.TEXT_TITLE, enabled ? hov : 0f);
        int c = GeneratorFuelUiTheme.alpha(tc, a);
        int textW = font.width(label);
        int ty = y + (h - font.lineHeight) / 2;
        if (!dice) {
            if (visible(c)) g.drawCenteredString(font, label, x + w / 2, ty, c);
            return;
        }
        // «Кубик» перед написом: квадрат з крапками, після натискання підстрибує й крутиться.
        int box = 9;
        int bx = x + w / 2 - (textW + box + 5) / 2;
        int by = y + (h - box) / 2;
        float shake = diceT * diceT;
        int jx = Math.round((float) Math.sin(diceT * 40f) * 2f * shake);
        int jy = -Math.round(Math.abs((float) Math.sin(diceT * 14f)) * 3f * shake);
        ManiacUiTheme.border1px(g, bx + jx, by + jy, box, box, c);
        int pip = GeneratorFuelUiTheme.alpha(0xFFFFFFFF, a);
        int[][] pips = diceT > 0.02f && ((int) (diceT * 18f) & 1) == 0
            ? new int[][]{{2, 2}, {6, 6}}                      // «двійка»
            : new int[][]{{2, 2}, {6, 2}, {4, 4}, {2, 6}, {6, 6}}; // «п'ятірка»
        for (int[] pp : pips) g.fill(bx + jx + pp[0], by + jy + pp[1], bx + jx + pp[0] + 1, by + jy + pp[1] + 1, pip);
        if (visible(c)) g.drawString(font, label, bx + box + 5, ty, c, false);
    }

    // ═════════════════════════════════════════════════════════════════
    //  Анімація й геометрія
    // ═════════════════════════════════════════════════════════════════

    private void stepAnimation(float dt) {
        boolean locked = confirmedAt >= 0;
        for (int i = 0; i < trapIds.size(); i++) {
            float hTarget = (!locked && i == hoveredCard) ? 1f : 0f;
            float pTarget = selected.contains(trapIds.get(i)) ? 1f : 0f;
            hover[i] = UiAnim.approach(hover[i], hTarget, 16f, dt);
            pick[i] = UiAnim.approach(pick[i], pTarget, 12f, dt);
            // Кільце-спалах згасає лінійно за ~0.45 с.
            pulse[i] = Math.max(0f, pulse[i] - dt * 2.2f);
            selT[i] = Math.min(1f, selT[i] + dt / SELECT_ANIM_S);
            flash[i] = Math.max(0f, flash[i] - dt * 3.4f);
        }
        shake = Math.max(0f, shake - dt * 4.5f);
        btnHover = UiAnim.approach(btnHover, (!locked && hoverBtn && canConfirm()) ? 1f : 0f, 16f, dt);
        btnActive = UiAnim.approach(btnActive, 0f, 14f, dt);
        randHover = UiAnim.approach(randHover, (!locked && hoverRand) ? 1f : 0f, 16f, dt);
        randActive = UiAnim.approach(randActive, 0f, 14f, dt);
        diceT = Math.max(0f, diceT - dt * 2.2f);
    }

    private void updateHover(int mx, int my) {
        int prevCard = hoveredCard;
        boolean prevHoverRand = hoverRand;
        boolean prevHoverBtn = hoverBtn;
        hoveredCard = -1;
        hoverBtn = false;
        hoverRand = false;
        if (confirmedAt >= 0) return;
        for (int i = 0; i < trapIds.size(); i++) {
            int cx = Math.round(cellCenterX(i));
            if (mx >= cx - cell / 2 && mx < cx + cell / 2 && my >= rowY && my < rowY + BOX + 40) {
                hoveredCard = i;
                break;
            }
        }
        hoverBtn = mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + BTN_H;
        hoverRand = mx >= randX && mx < randX + btnW && my >= btnY && my < btnY + BTN_H;
        boolean newHover = (hoveredCard != prevCard && hoveredCard >= 0)
            || (hoverRand && !prevHoverRand) || (hoverBtn && !prevHoverBtn);
        if (newHover) UiAnim.playMenuHover();
    }

    /** Усе вертикальне компонування рахується від центру екрана й розміру ряду. */
    private void layout() {
        int n = Math.max(1, trapIds.size());
        cell = Mth.clamp((width - 80) / n, CELL_MIN, CELL_MAX);

        int titleH = font.lineHeight + 6 + 1;
        int pipsH = 8;
        int cardH = BOX + 8 + font.lineHeight + 3 + font.lineHeight;
        int descH = (font.lineHeight + 2) * 4;   // 4 рядки: довгий опис + рядок про спосіб розміщення
        int total = titleH + 16 + pipsH + 30 + cardH + 22 + descH + 22 + BTN_H;
        int top = Math.max(12, (height - total) / 2);

        titleTop = top;
        pipsTop = top + titleH + 16;
        rowY = pipsTop + pipsH + 30;
        descTop = rowY + cardH + 22;
        btnY = descTop + descH + 22;
        btnW = Mth.clamp((width - 60) / 2, 90, BTN_W);
        int gap = 12;
        randX = width / 2 - gap / 2 - btnW;
        btnX = width / 2 + gap / 2;
    }

    private int titleTop, pipsTop, descTop;
    private int titleY() { return titleTop; }
    private int pipsY() { return pipsTop; }
    private int descY() { return descTop; }

    private float cellCenterX(int i) {
        int n = trapIds.size();
        return width / 2f + (i - (n - 1) / 2f) * cell;
    }

    /** Центр слота в зібраному ряду обраних (вузший крок, ніж у сітці). */
    private float gatherCenterX(int slot) {
        int k = Math.max(1, confirmedOrder.size());
        int step = Math.min(cell, 108);
        return width / 2f + (slot - (k - 1) / 2f) * step;
    }

    private int indexInSelection(String id) {
        int idx = 0;
        for (String s : selected) {
            if (s.equals(id)) return idx;
            idx++;
        }
        return 0;
    }

    /** Ванільний Font вважає колір з майже нульовою альфою НЕпрозорим — тож майже невидимий текст просто не малюємо. */
    private static boolean visible(int argb) {
        return ((argb >>> 24) & 0xFF) >= 8;
    }

    private static String keyLabel(int order) {
        if (order < 0 || order >= ManiacKeybinds.TRAPS.length) return String.valueOf(order + 5);
        return ManiacKeybinds.TRAPS[order].getTranslatedKeyMessage().getString();
    }

    // ═════════════════════════════════════════════════════════════════
    //  Ввід
    // ═════════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && confirmedAt < 0) {
            if (hoveredCard >= 0) {
                toggle(hoveredCard);
                return true;
            }
            if (hoverBtn && canConfirm()) {
                btnActive = 1f;
                confirm();
                return true;
            }
            if (hoverRand && canRandomize()) {
                randActive = 1f;
                randomize();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R && canRandomize()) {
            randActive = 1f;
            randomize();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && confirmedAt < 0 && canConfirm()) {
            btnActive = 1f;
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void toggle(int index) {
        String id = trapIds.get(index);
        if (selected.remove(id)) {
            UiAnim.playClick();
            return;
        }
        if (selected.size() >= limit) {
            UiAnim.playMenuWarn(); // повно — нову не беремо, старі лишаються
            return;
        }
        selected.add(id);
        pulse[index] = 1f;
        selT[index] = 0f;
        flash[index] = 1f;
        shake = 1f;
        burstAt(Math.round(cellCenterX(index)), rowY + (BOX + 8) / 2 + 6, 26, 150f);
        UiAnim.playClick();
        UiAnim.playAppear();
    }

    /** Віяло іскор від точки екрана: яскраво-білі й срібні, летять навсібіч і падають. */
    private void burstAt(float x, float y, int n, float speed) {
        sparks.burst(x, y, n, speed, 0f, -1f, 0.55f, 0xFFFFFF);
        sparks.burst(x, y, n / 2, speed * 0.7f, 0f, -1f, 0.7f, 0xC2C9D6);
    }

    private boolean canRandomize() {
        return confirmedAt < 0 && !trapIds.isEmpty();
    }

    /**
     * Добирає випадковий набір на {@code limit} пасток. Попередній вибір
     * скидається (повторне натискання = новий жереб), а кожна обрана картка
     * отримує ті самі ефекти, що й при ручному кліку, але зі зсувом у часі —
     * щоб вибір «викотився» зліва направо, а не блимнув одразу.
     */
    private void randomize() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < trapIds.size(); i++) order.add(i);
        Collections.shuffle(order);

        selected.clear();
        int take = Math.min(limit, order.size());
        // Порядок слотів 5/6/7 = порядок у списку; тримаємо його за порядком карток, не жеребу,
        // щоб підсумок не плутав гравця.
        List<Integer> picked = new ArrayList<>(order.subList(0, take));
        Collections.sort(picked);
        for (int idx : picked) {
            selected.add(trapIds.get(idx));
            pulse[idx] = 1f;
            selT[idx] = 0f;
            flash[idx] = 1f;
            burstAt(Math.round(cellCenterX(idx)), rowY + (BOX + 8) / 2 + 6, 22, 140f);
        }
        shake = 1f;
        diceT = 1f;
        UiAnim.playClick();
        UiAnim.playAppear();
    }

    private boolean canConfirm() {
        return confirmedAt < 0 && !selected.isEmpty() && selected.size() <= limit;
    }

    private void confirm() {
        confirmedOrder = List.copyOf(selected);
        confirmedAt = Util.getMillis();
        packetSent = false;
        shake = 1f;
        for (String id : confirmedOrder) {
            int idx = trapIds.indexOf(id);
            if (idx < 0) continue;
            pulse[idx] = 1f;
            flash[idx] = 1f;
            burstAt(Math.round(cellCenterX(idx)), rowY + (BOX + 8) / 2 + 6, 18, 120f);
        }
        UiAnim.playReady();
    }

    private void sendChoice() {
        List<Integer> indices = new ArrayList<>();
        for (String id : confirmedOrder) indices.add(trapIds.indexOf(id));
        ModNetwork.toServer(new TrapChoosePacket(indices));
        // Екран закриє ClientPacketHandler#onTrapLoadout, коли прийде відповідь сервера.
    }
}

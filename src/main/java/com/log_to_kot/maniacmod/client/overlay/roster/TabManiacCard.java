package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Картка МАНЬЯКА — «обличчя» матчу: велика 3D-модель у кривавому серпанку.
 *
 * ── Чому окремо від списку виживих ───────────────────────────────────
 * Маньяк — не «ще один рядок». Це єдиний персонаж, на якого дивляться всі
 * інші, тому він отримує власне місце з власною атмосферою: діагональна
 * штриховка-небезпека, дихаюче світіння, справжня geo-модель, що
 * обертається на вступі й далі похитується.
 *
 * ── Модель ───────────────────────────────────────────────────────────
 * Малюється {@link TabGeoView} напряму з ассетів архетипу — без реальної
 * сутності у світі, тож дистанція/невидимість маньяка не впливають. Якщо
 * 3D вимкнено після збою — фолбек: збільшена голова зі скіна.
 * Кілька маньяків: картка плавно перемикається між ними (затемнення +
 * зсув моделі), внизу — крапки-індикатор. Порожня картка ({@code maniac
 * == null}) — архетип іще не обрано: пульсуюче «?».
 */
final class TabManiacCard {

    private static final int STRIP_H = 9;
    private static final int PLATE_H = 24;

    /** Скільки кожен маньяк стоїть у кадрі, перш ніж картка перейде до наступного. */
    private static final long HOLD_MS = 4200L;
    /** Тривалість плавного переходу між двома маньяками. */
    private static final long FADE_MS = 650L;

    private long cycleStartMs = 0L;

    /** Показ починається з першого маньяка щоразу, як відкрито Tab. */
    void onOpen() {
        cycleStartMs = System.currentTimeMillis();
    }

    /**
     * Який маньяк у кадрі та наскільки зараз «затемнено» перехід: 0 — модель
     * видно повністю, 1 — повна темрява (саме в цей момент підміняється індекс,
     * тож стрибка не видно). Перша половина переходу гасить поточного,
     * друга — проявляє наступного.
     */
    private record Slot(int index, float dark, float slide) {}

    private Slot slot(TabContext c, int count) {
        if (count <= 1) return new Slot(0, 0f, 0f);
        long period = HOLD_MS + FADE_MS;
        long elapsed = Math.max(0L, c.nowMs - cycleStartMs);
        long cycle = elapsed / period;
        long inCycle = elapsed % period;

        int index = (int) (cycle % count);
        float dark = 0f;
        float slide = 0f;
        long half = FADE_MS / 2;
        if (inCycle >= HOLD_MS) {
            // Кінець показу: гасимо поточного, модель їде ліворуч.
            float p = UiAnim.clamp01((inCycle - HOLD_MS) / (float) half);
            dark = UiAnim.easeInOutCubic(p);
            slide = -p;
        } else if (inCycle < half && cycle > 0) {
            // Початок показу: наступний виїжджає з темряви праворуч.
            float p = 1f - UiAnim.clamp01(inCycle / (float) half);
            dark = UiAnim.easeInOutCubic(p);
            slide = p;
        }
        // Під час гасіння вже показуємо «наступного» тільки після перетину темряви.
        if (inCycle >= HOLD_MS + half) index = (int) ((cycle + 1) % count);
        return new Slot(index, dark, slide);
    }

    void draw(TabContext c, int x, int y, int w, int h) {
        GuiGraphics g = c.g;
        Slot slot = slot(c, c.maniacs.size());
        RosterEntry m = c.maniacs.isEmpty() ? null : c.maniacs.get(slot.index());
        float breathe = TabFx.pulse(c.nowMs, 1400f);

        // ── Тло ─────────────────────────────────────────────────────
        TabFx.card(g, x, y, w, h, ManiacTabTheme.CARD, TabFx.a(ManiacTabTheme.BLOOD, 0.4f + 0.4f * breathe));
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1,
            0x00000000, TabFx.a(ManiacTabTheme.BLOOD_DEEP, 0.95f));
        UiFx.glow(g, x, y, w, h, 0xC8232F, 0.10f + 0.18f * breathe, 4);

        // ── Смуга-небезпека + мітка ролі ─────────────────────────────
        g.fill(x + 1, y + 1, x + w - 1, y + 1 + STRIP_H, 0xFF120507);
        TabFx.hazard(g, x + 1, y + 1, w - 2, STRIP_H, TabFx.a(ManiacTabTheme.BLOOD, 0.55f), 6,
            (int) (c.nowMs / 70L));
        String tag = Component.translatable("maniacmod.tab.card.maniac").getString().toUpperCase(Locale.ROOT);
        int tagW = TabFx.width(c.font, tag, 0.7f) + 8;
        g.fill(x + 4, y + 1, x + 4 + tagW, y + 1 + STRIP_H, 0xFF120507);
        TabFx.text(c.g, c.font, tag, x + 8, y + 3, 0.7f, TabFx.a(ManiacTabTheme.BLOOD_HOT, 1f));

        if (m != null && m.playerId().equals(c.me)) {
            String you = Component.translatable("maniacmod.tab.tag.you").getString().toUpperCase(Locale.ROOT);
            int yw = TabFx.width(c.font, you, 0.7f) + 8;
            g.fill(x + w - 4 - yw, y + 1, x + w - 4, y + 1 + STRIP_H, ManiacTabTheme.SILVER);
            TabFx.text(c.g, c.font, you, x + w - yw, y + 3, 0.7f, 0xFF0A0C0B);
        }

        // ── Модель / фолбек ───────────────────────────────────────────
        int modelTop = y + STRIP_H + 2;
        int modelBottom = y + h - PLATE_H;
        float cx = x + w / 2f;

        // Модель — власний geo-рендер оверлея за архетипом з ростера: реальний
        // гравець-маньяк у світі для цього не потрібен (він може бути далеко,
        // невидимий чи взагалі не завантажений).
        boolean drawn = false;
        ManiacArchetype archetype = m == null ? null : archetypeOf(m.archetypeId());
        if (archetype != null && c.model3d && TabGeoView.available()) {
            float bodyH = m.maniacBody() != null ? m.maniacBody().height() : archetype.hitboxHeight();
            float scale = (modelBottom - modelTop - 6) / Math.max(1.6f, bodyH);
            // Вступ: 1.5 оберти, що гальмують у позі «обличчям до глядача».
            float intro = 1f - TabFx.stageOut(c.t, 0.5f, 1.1f);
            float sway = (float) Math.sin(c.nowMs / 950.0) * 22f * (1f - intro);
            float drift = slot.slide() * 18f;
            drawn = TabGeoView.drawManiac(g, archetype, cx + drift, modelBottom - 2, scale,
                180f + intro * 540f + sway);
        }

        if (!drawn) {
            if (m != null) {
                c.style.drawHead(g, m.playerId(), (int) cx - 24, modelTop + 6, 48, 1f);
            } else {
                float p = TabFx.pulse(c.nowMs, 900f);
                TabFx.textCentered(g, c.font, "?", cx, modelTop + 14, 4.5f,
                    TabFx.a(ManiacTabTheme.BLOOD_HOT, 0.4f + 0.5f * p));
            }
        }

        // ── Перехід між маньяками: затемнення поверх моделі (глибина вже очищена) ──
        if (slot.dark() > 0.01f) {
            g.fill(x + 1, modelTop - 1, x + w - 1, modelBottom, TabFx.a(0xFF0A0506, slot.dark()));
        }

        // ── Серпанок і скан-лінії поверх моделі ─────────────────────────
        g.fillGradient(x + 1, modelBottom - 26, x + w - 1, modelBottom,
            0x00000000, TabFx.a(ManiacTabTheme.BLOOD_DEEP, 0.85f));
        TabFx.scanlines(g, x + 1, modelTop, w - 2, modelBottom - modelTop, 0x18000000, 3);

        // ── Плашка з іменем ─────────────────────────────────────────
        int py = y + h - PLATE_H;
        g.fill(x + 1, py, x + w - 1, y + h - 1, 0xD1050607);
        g.fill(x + 1, py, x + w - 1, py + 1, TabFx.a(ManiacTabTheme.BLOOD, 0.75f));

        if (m != null) {
            float decode = TabFx.stage(c.t, 0.8f, 0.5f);
            float textA = 1f - slot.dark();
            String name = TabFx.scramble(TabFx.clip(c.font, m.displayName(), w - 12, 1f), decode, 11, c.nowMs);
            TabFx.text(g, c.font, name, x + 6, py + 4, 1f, TabFx.a(0xFFF2F4F8, textA));
            TabFx.text(g, c.font, archetypeName(m.archetypeId()).toUpperCase(Locale.ROOT),
                x + 6, py + 14, 0.75f, TabFx.a(ManiacTabTheme.BLOOD_HOT, textA));
            drawDots(c, x + w - 6, py + 6, slot.index());
        } else {
            float p = TabFx.pulse(c.nowMs, 1000f);
            String choosing = Component.translatable("maniacmod.tab.maniac.pending").getString();
            TabFx.text(g, c.font, choosing, x + 6, py + 8, 1f, TabFx.a(ManiacTabTheme.DIM, 0.5f + 0.5f * p));
        }

        UiFx.brackets(g, x, y, w, h, TabFx.a(ManiacTabTheme.BLOOD_HOT, 0.8f), 7, breathe, 2);
    }

    /** Крапки-індикатор «який із маньяків зараз у кадрі»; лише коли їх більше одного. */
    private void drawDots(TabContext c, int right, int y, int active) {
        int n = c.maniacs.size();
        if (n < 2) return;
        int gap = 3, size = 4;
        int x = right - (n * size + (n - 1) * gap);
        for (int i = 0; i < n; i++) {
            boolean on = i == active;
            c.g.fill(x, y, x + size, y + size,
                on ? TabFx.a(ManiacTabTheme.BLOOD_HOT, 1f) : TabFx.a(ManiacTabTheme.DIM, 0.6f));
            x += size + gap;
        }
    }

    /** Архетип за id; {@code null} для порожнього/невідомого — тоді картка падає на фолбек-голову. */
    private static ManiacArchetype archetypeOf(String archetypeId) {
        if (archetypeId == null || archetypeId.isEmpty()) return null;
        try {
            return ManiacRegistry.get(archetypeId);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * archetypeId → людська назва; порожній/невідомий id не повинен валити
     * рендер усього табу (див. попередню версію {@code TabRosterOverlay}).
     */
    private static String archetypeName(String archetypeId) {
        if (archetypeId == null || archetypeId.isEmpty()) return "";
        try {
            ManiacArchetype archetype = ManiacRegistry.get(archetypeId);
            return archetype.displayName();
        } catch (IllegalArgumentException unknown) {
            return archetypeId;
        }
    }
}

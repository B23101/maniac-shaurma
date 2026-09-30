package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Верхня картка tab-екрана: ПОТОЧНЕ ЗАВДАННЯ і скільки з нього вже зроблено.
 *
 * ── Що показує ────────────────────────────────────────────────────────
 *   • 3D-модель генератора (обертається) — власний geo-рендер оверлея
 *     ({@link TabGeoView}), не залежить від того, чи є генератор поруч.
 *     Анімація: «idle», доки не полагоджено все потрібне; тоді «running»
 *     (та сама, що в світі у полагодженого генератора);
 *   • назву завдання — ВІД РОЛІ ГЛЯДАЧА І ФАЗИ («Полагодь генератори» у
 *     виживого, «Не дай полагодити» у маньяка; на POWERED/FINALE — вихід
 *     і втеча). Раніше tab не казав цього взагалі;
 *   • «N / M» — скільки генераторів полагоджено з потрібних;
 *   • M слотів. Проміжний прогрес (відсотки ремонту, бензин) НЕ показується:
 *     слот порожній, доки генератор не полагоджено повністю, і лише тоді
 *     загоряється — «1 з 6 полагоджено»;
 *   • 4 віхи матчу: живлення, вихід, перша кров, втеча.
 *
 * ── Дані ───────────────────────────────────────────────────────────────
 * Лише {@link ObjectiveSyncPacket}: клієнт нічого не рахує. Цей клас
 * тримає тільки АНІМАЦІЙНИЙ стан (наскільки слот «догнав» ціль, спалахи) —
 * джерело правди лишається на сервері.
 *
 * ── Анімації ──────────────────────────────────────────────────────────
 * Слоти заповнюються з нуля після вступу (щоразу, як відкрито Tab);
 * завершення генератора під час перегляду дає спалах слота, вибух іскор,
 * дзвінкий звук і «удар» лічильника.
 */
final class TabObjectiveCard {

    static final int HEIGHT = 60;

    private static final int MAX_SLOTS = 12;
    private static final int VIEW_W = 56;

    private final float[] slotShown = new float[MAX_SLOTS];
    private final float[] slotFlash = new float[MAX_SLOTS];
    private float counterShown = 0f;
    private float counterPunch = 0f;
    private int lastDone = 0;
    private int pendingBurstSlot = -1;

    /** Скидає анімаційний стан на відкритті: слоти наповнюватимуться заново, без хибних «щойно завершено». */
    void onOpen(ObjectiveSyncPacket now) {
        java.util.Arrays.fill(slotShown, 0f);
        java.util.Arrays.fill(slotFlash, 0f);
        counterShown = 0f;
        counterPunch = 0f;
        lastDone = now.generatorsDone();
        pendingBurstSlot = -1;
    }

    // ── Ключі завдання ─────────────────────────────────────────────────

    static String objectiveKey(TabContext c) {
        String role = c.iAmManiac() ? "maniac" : c.iAmSurvivor() ? "survivor" : "spectator";
        return switch (c.phase) {
            case HUNT    -> "maniacmod.tab.objective." + role + ".hunt";
            case POWERED -> "maniacmod.tab.objective." + role + ".powered";
            case FINALE  -> "maniacmod.tab.objective." + role + ".finale";
            case ENDING  -> "maniacmod.tab.objective.ending";
            default      -> "maniacmod.tab.objective.wait";
        };
    }

    // ── Оновлення анімацій ─────────────────────────────────────────────

    private void update(TabContext c, int slots) {
        ObjectiveSyncPacket obj = c.objectives;
        List<ObjectiveSyncPacket.GeneratorSlot> list = obj.generators();

        boolean filling = c.t > 0.75f;
        for (int i = 0; i < MAX_SLOTS; i++) {
            float target = 0f;
            // Лише «готово / не готово»: частковий прогрес гравцям не відомий.
            if (filling && i < slots && i < list.size()
                && list.get(i).stage() == GeneratorPoi.Stage.DONE) target = 1f;
            slotShown[i] = UiAnim.approach(slotShown[i], target, 3.4f, c.dt);
            slotFlash[i] = Math.max(0f, slotFlash[i] - c.dt * 1.5f);
        }

        int done = obj.generatorsDone();
        if (filling) {
            if (done > lastDone && slots > 0) {
                int idx = Math.min(slots, done) - 1;
                slotFlash[idx] = 1f;
                counterPunch = 1f;
                pendingBurstSlot = idx;
                UiAnim.playTabGeneratorDone();
            }
            lastDone = done;
        }
        counterShown = UiAnim.approach(counterShown, filling ? done : 0f, 5f, c.dt);
        counterPunch = Math.max(0f, counterPunch - c.dt * 2.2f);
    }

    // ── Малювання ──────────────────────────────────────────────────────

    void draw(TabContext c, int x, int y, int w) {
        GuiGraphics g = c.g;
        ObjectiveSyncPacket obj = c.objectives;
        int required = Math.max(0, Math.min(MAX_SLOTS, obj.generatorsRequired()));
        update(c, required);

        boolean complete = required > 0 && obj.generatorsDone() >= required;
        float pulse = TabFx.pulse(c.nowMs, 1200f);

        TabFx.card(g, x, y, w, HEIGHT, ManiacTabTheme.CARD, ManiacTabTheme.CARD_EDGE);
        int accent = complete ? ManiacTabTheme.OK : ManiacTabTheme.SILVER;
        g.fill(x, y + 1, x + 2, y + HEIGHT - 1, TabFx.a(accent, 0.6f + 0.4f * pulse));

        drawViewport(c, x + 4, y + 4, VIEW_W, HEIGHT - 8, complete);

        int tx = x + VIEW_W + 12;
        int right = x + w - 8;

        // Рядок 1: підпис «ЗАВДАННЯ» ліворуч, віхи праворуч.
        TabFx.text(g, c.font,
            Component.translatable("maniacmod.tab.card.objective").getString().toUpperCase(Locale.ROOT),
            tx, y + 5, 0.7f, TabFx.a(ManiacTabTheme.DIM, 1f));
        drawMilestones(c, right, y + 5, obj);

        // Рядок 2: назва завдання (з «розшифровкою» на вступі) + великий лічильник.
        String title = Component.translatable(objectiveKey(c)).getString().toUpperCase(Locale.ROOT);
        float decode = TabFx.stage(c.t, 0.45f, 0.55f);
        int counterW = required > 0 ? 62 : 0;
        String shownTitle = TabFx.scramble(TabFx.clip(c.font, title, right - tx - counterW - 4, 1.2f),
            decode, 3, c.nowMs);
        TabFx.textShadow(g, c.font, shownTitle, tx, y + 15, 1.2f, TabFx.a(0xFFF2F4F8, 1f));

        if (required > 0) {
            drawCounter(c, right, y + 11, obj.generatorsDone(), required, complete);
        }

        // Рядок 3: слоти генераторів.
        int slotsRight = right - counterW - 6;
        if (required > 0) {
            drawSlots(c, tx, y + 35, slotsRight - tx, 11, required, obj.generators());
        }

        // Рядок 4: підказка «як саме».
        String hint = Component.translatable(objectiveKey(c) + ".sub").getString();
        TabFx.text(g, c.font, TabFx.clip(c.font, hint, w - VIEW_W - 24, 0.75f),
            tx, y + HEIGHT - 11, 0.75f, TabFx.a(ManiacTabTheme.DIM, TabFx.stage(c.t, 0.7f, 0.4f)));

        flushBurst(c, tx, y + 35, slotsRight - tx, required);
    }

    // ── 3D-в'юпорт генератора ──────────────────────────────────────────

    private void drawViewport(TabContext c, int x, int y, int w, int h, boolean complete) {
        GuiGraphics g = c.g;
        g.fill(x, y, x + w, y + h, 0xFF07090A);
        g.fillGradient(x, y, x + w, y + h, 0x00000000,
            TabFx.a(complete ? 0xFF10331A : 0xFF20252B, 0.9f));

        boolean drawn = false;
        if (c.model3d && TabGeoView.available()) {
            float yaw = 30f + (c.nowMs % 9000L) / 9000f * 360f;
            drawn = TabGeoView.drawGenerator(g, x + w / 2f, y + h - 8, 34f, yaw, complete);
        }
        if (!drawn) drawFallbackGenerator(c, x, y, w, h, complete);

        // Поверх моделі (глибина очищена, звичайний fill лягає зверху).
        TabFx.scanlines(g, x, y, w, h, 0x16000000, 3);
        UiFx.brackets(g, x, y, w, h, TabFx.a(ManiacTabTheme.SILVER_HI, 0.85f), 5, 0f, 0);

        // Індикатор-«лампа» стану у верхньому куті.
        boolean blink = ((c.nowMs / 380L) & 1L) == 0L;
        int lamp = complete ? ManiacTabTheme.OK : (blink ? ManiacTabTheme.WARN : 0xFF5A4318);
        g.fill(x + w - 8, y + 3, x + w - 4, y + 7, lamp);
        UiFx.glow(g, x + w - 8, y + 3, 4, 4, lamp & 0xFFFFFF, 0.5f, 2);
    }

    /** Намальований генератор: корпус, вентиляція, блискавка — коли справжньої моделі поруч нема. */
    private void drawFallbackGenerator(TabContext c, int x, int y, int w, int h, boolean complete) {
        GuiGraphics g = c.g;
        int bx = x + (w - 34) / 2;
        int by = y + h / 2 - 12;
        int body = complete ? 0xFF2C6B3A : 0xFF5C6673;
        g.fill(bx, by, bx + 34, by + 26, 0xFF14181C);
        g.fill(bx + 1, by + 1, bx + 33, by + 25, body);
        g.fill(bx + 1, by + 1, bx + 33, by + 3, TabFx.a(0xFFFFFFFF, 0.25f));
        for (int i = 0; i < 4; i++) g.fill(bx + 4, by + 12 + i * 3, bx + 16, by + 13 + i * 3, 0xFF14181C);
        // блискавка
        int lx = bx + 22, ly = by + 6;
        g.fill(lx + 3, ly, lx + 6, ly + 5, ManiacTabTheme.WARN);
        g.fill(lx, ly + 5, lx + 6, ly + 8, ManiacTabTheme.WARN);
        g.fill(lx, ly + 8, lx + 3, ly + 14, ManiacTabTheme.WARN);
        // ніжки + вихлоп, що «димить»
        g.fill(bx + 3, by + 26, bx + 8, by + 29, 0xFF14181C);
        g.fill(bx + 26, by + 26, bx + 31, by + 29, 0xFF14181C);
        int puff = (int) ((c.nowMs / 90L) % 8L);
        g.fill(bx + 30, by - 2 - puff, bx + 32, by - puff, TabFx.a(0xFFB9B9B9, 1f - puff / 8f));
    }

    // ── Лічильник «N / M» ──────────────────────────────────────────────

    private void drawCounter(TabContext c, int right, int y, int done, int required, boolean complete) {
        GuiGraphics g = c.g;
        float scale = 2.0f + 0.35f * counterPunch;
        String big = Integer.toString(Math.round(counterShown));
        String small = "/" + required;
        int color = complete ? ManiacTabTheme.OK : 0xFFF2F4F8;
        color = TabFx.mix(color, 0xFFFFFFFF, counterPunch);

        int smallW = TabFx.width(c.font, small, 1.0f);
        TabFx.textRight(g, c.font, small, right, y + 9, 1.0f, TabFx.a(ManiacTabTheme.DIM, 1f));
        int bigW = TabFx.width(c.font, big, scale);
        float bx = right - smallW - bigW - 1;
        if (counterPunch > 0.02f) {
            UiFx.glow(g, (int) bx - 2, y, bigW + 4, 18, complete ? 0x43C463 : 0xFFFFFF, counterPunch * 0.8f, 3);
        }
        TabFx.textShadow(g, c.font, big, bx, y + 9 - (scale - 2.0f) * 4f - 2, scale, color);
    }

    // ── Слоти ──────────────────────────────────────────────────────────

    private void drawSlots(TabContext c, int x, int y, int width, int h, int n,
                           List<ObjectiveSyncPacket.GeneratorSlot> list) {
        GuiGraphics g = c.g;
        int gap = 3;
        int slotW = Math.max(10, Math.min(46, (width - gap * (n - 1)) / n));
        long now = c.nowMs;

        for (int i = 0; i < n; i++) {
            ObjectiveSyncPacket.GeneratorSlot s = i < list.size() ? list.get(i) : null;
            int sx = x + i * (slotW + gap);
            float appear = TabFx.stageOut(c.t, 0.55f + i * 0.04f, 0.3f);
            if (appear <= 0.01f) continue;

            // Стан «у роботі / залитий бензином / збій» навмисно не відрізняється від
            // «не чіпали»: слот світиться ЛИШЕ коли генератор полагоджено повністю.
            boolean done = s != null && s.stage() == GeneratorPoi.Stage.DONE;
            int color = done ? ManiacTabTheme.OK : ManiacTabTheme.SILVER;

            float f = slotShown[i];
            var pose = g.pose();
            pose.pushPose();
            pose.translate(0f, (1f - appear) * 6f, 0f);

            GeneratorFuelUiTheme.drawBarFx(g, sx, y, slotW, h, f, f, color, slotFlash[i]);

            if (done) {
                UiFx.glow(g, sx, y, slotW, h, 0x43C463, 0.35f + 0.25f * TabFx.pulse(now, 900f), 2);
                int cx = sx + slotW / 2, cy = y + h / 2;
                g.fill(cx - 3, cy, cx - 2, cy + 1, 0xFF051208);
                g.fill(cx - 2, cy + 1, cx - 1, cy + 2, 0xFF051208);
                g.fill(cx - 1, cy, cx, cy + 1, 0xFF051208);
                g.fill(cx, cy - 1, cx + 1, cy, 0xFF051208);
                g.fill(cx + 1, cy - 2, cx + 2, cy - 1, 0xFF051208);
                g.fill(cx + 2, cy - 3, cx + 3, cy - 2, 0xFF051208);
            }
            pose.popPose();
        }
    }

    /** Іскри на щойно завершеному слоті — позиція відома лише тут, тому вибух відкладається до малювання. */
    private void flushBurst(TabContext c, int x, int y, int width, int n) {
        if (pendingBurstSlot < 0 || n <= 0) return;
        int gap = 3;
        int slotW = Math.max(10, Math.min(46, (width - gap * (n - 1)) / n));
        float cx = x + pendingBurstSlot * (slotW + gap) + slotW / 2f;
        c.sparks.burst(cx, y + 5, 26, 90f, 0f, -1f, 0.9f, 0x43C463);
        c.sparks.burst(cx, y + 5, 12, 60f, 0f, -1f, 0.7f, 0xFFFFFF);
        pendingBurstSlot = -1;
    }

    // ── Віхи ───────────────────────────────────────────────────────────

    private void drawMilestones(TabContext c, int right, int y, ObjectiveSyncPacket obj) {
        record M(MatchObjectives.Objective o, String key, int color) {}
        M[] ms = {
            new M(MatchObjectives.Objective.SOMEONE_ESCAPED, "maniacmod.tab.milestone.escape", ManiacTabTheme.OK),
            new M(MatchObjectives.Objective.FIRST_BLOOD,     "maniacmod.tab.milestone.blood",  ManiacTabTheme.BLOOD_HOT),
            new M(MatchObjectives.Objective.EXIT_OPENED,     "maniacmod.tab.milestone.exit",   ManiacTabTheme.SILVER_HI),
            new M(MatchObjectives.Objective.POWER_RESTORED,  "maniacmod.tab.milestone.power",  ManiacTabTheme.WARN),
        };
        float scale = 0.65f;
        int cursor = right;
        for (M m : ms) {
            String label = Component.translatable(m.key()).getString().toUpperCase(Locale.ROOT);
            boolean lit = obj.has(m.o());
            int lw = TabFx.width(c.font, label, scale);
            float p = lit ? TabFx.pulse(c.nowMs, 1100f) : 0f;
            int col = lit ? m.color() : ManiacTabTheme.DIM;
            float alpha = lit ? 1f : 0.45f;
            TabFx.textRight(c.g, c.font, label, cursor, y + 1, scale, TabFx.a(col, alpha));
            int dx = cursor - lw - 7;
            c.g.fill(dx, y + 1, dx + 4, y + 5, TabFx.a(col, alpha * (0.7f + 0.3f * p)));
            if (lit) UiFx.glow(c.g, dx, y + 1, 4, 4, col & 0xFFFFFF, 0.5f * p + 0.2f, 2);
            cursor = dx - 7;
        }
    }
}

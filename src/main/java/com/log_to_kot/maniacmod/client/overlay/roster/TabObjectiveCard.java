package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.evac.ClientEvac;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.core.match.MatchObjectives;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket.Step;
import com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Верхня картка tab-екрана: ПОТОЧНЕ ЗАВДАННЯ і скільки з нього вже зроблено.
 *
 * ── Завдання змінюється разом із матчем ───────────────────────────────
 *   HUNT → генератори (модель генератора, слоти = генератори)
 *   POWERED → «знайди приймач» (модель приймача; відстань, якщо він поруч)
 *   HELI_WAIT → «знайди сигнальну ракету» / «запусти її на галявині» (модель ракети;
 *               відстань і напрям до галявини), далі «вертоліт летить» (модель вертольота)
 *   FINALE → «лізь по мотузці» (таймер до відльоту)
 * Крок евакуації береться з {@code ClientEvac} (EvacStatusPacket) і досягнень матчу.
 * Слоти в евакуації — чотири кроки: світло → виклик → ракета → мотузка.
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
 *   • віхи матчу з іконками — лише ті, що важливі ролі глядача
 *     (див. {@link #milestoneRelevant}): виживому — живлення й вихід,
 *     маньяку — перша кров, живлення, вихід (+ втеча у фазі втечі).
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
    private int lastDone = -1;
    private int pendingBurstSlot = -1;

    /** Що зараз показує картка: слоти, лічильник і модель залежать від кроку матчу. */
    enum Mode { GENERATORS, RECEIVER, FLARE, HELI, CLIMB }

    /** Підписи слотів у режимах евакуації (ключі lang). У режимі генераторів підписів нема. */
    private static final String[] EVAC_LABELS = {
        "maniacmod.tab.step.power", "maniacmod.tab.step.call",
        "maniacmod.tab.step.flare", "maniacmod.tab.step.rope"
    };

    private final boolean[] flags = new boolean[MAX_SLOTS];
    private int required = 0;
    private int done = 0;
    private Mode lastMode = null;

    /** Скидає анімаційний стан на відкритті: слоти наповнюватимуться заново, без хибних «щойно завершено». */
    void onOpen(ObjectiveSyncPacket now) {
        java.util.Arrays.fill(slotShown, 0f);
        java.util.Arrays.fill(slotFlash, 0f);
        counterShown = 0f;
        counterPunch = 0f;
        lastDone = -1;          // перша кадрова перевірка просто запам'ятає стан, без хибного спалаху
        lastMode = null;
        pendingBurstSlot = -1;
    }

    // ── Режим картки ───────────────────────────────────────────────────

    static Mode mode(TabContext c) {
        int step = ClientEvac.step();
        return switch (c.phase) {
            case POWERED   -> Mode.RECEIVER;
            case HELI_WAIT -> (c.objectives.has(MatchObjectives.Objective.FLARE_FIRED) || step >= Step.INBOUND)
                              ? Mode.HELI : Mode.FLARE;
            case FINALE    -> Mode.CLIMB;
            default        -> Mode.GENERATORS;
        };
    }

    /** Чи є в мене сигнальна ракета (руки, хотбар, інвентар). Лише клієнтська підказка. */
    private static boolean hasFlare() {
        Player p = Minecraft.getInstance().player;
        if (p == null) return false;
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(ManiacMod.MOD_ID, "signal_flare"));
        if (item == null) return false;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.is(item)) return true;
        }
        return false;
    }

    // ── Ключі завдання ─────────────────────────────────────────────────

    static String objectiveKey(TabContext c) {
        String role = c.iAmManiac() ? "maniac" : c.iAmSurvivor() ? "survivor" : "spectator";
        String base = "maniacmod.tab.objective.";
        return switch (c.phase) {
            case HUNT      -> base + role + ".hunt";
            case POWERED   -> base + role + ".receiver";
            case HELI_WAIT -> switch (mode(c)) {
                case HELI -> base + role + ".heli";
                default   -> base + role + (c.iAmSurvivor() && hasFlare() ? ".flare_ready" : ".flare");
            };
            case FINALE    -> base + role + ".finale";
            case ENDING    -> base + "ending";
            default        -> base + "wait";
        };
    }

    /** Підказка під заголовком; де можна — з відстанню/напрямком чи таймером. */
    private static String hintText(TabContext c) {
        String key = objectiveKey(c) + ".sub";
        Minecraft mc = Minecraft.getInstance();
        switch (mode(c)) {
            case RECEIVER -> {
                if (c.iAmSurvivor()) {
                    Integer d = receiverDistance(mc);
                    if (d != null) return Component.translatable(key + "_near", d).getString();
                }
            }
            case FLARE -> {
                if (c.iAmSurvivor()) {
                    String where = clearingDirection(mc);
                    if (where != null) return Component.translatable(key + "_where", where).getString();
                }
            }
            case HELI -> {
                if (ClientEvac.step() == Step.ROPE_DROP) return Component.translatable(key + "_drop").getString();
            }
            case CLIMB -> {
                float left = ClientEvac.secondsLeft();
                if (left >= 0f && ClientEvac.step() == Step.CLIMB) {
                    return Component.translatable(key + "_timer", Math.round(left)).getString();
                }
                if (ClientEvac.step() == Step.DEPARTING) return Component.translatable(key + "_leaving").getString();
            }
            default -> {}
        }
        return Component.translatable(key).getString();
    }

    /** Відстань до найближчого приймача, якщо він у завантаженій зоні; інакше null. */
    private static Integer receiverDistance(Minecraft mc) {
        if (mc.level == null || mc.player == null) return null;
        double best = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ReceiverEntity) best = Math.min(best, e.distanceTo(mc.player));
        }
        return best == Double.MAX_VALUE ? null : (int) Math.round(best);
    }

    /** «142 м, попереду» — до найближчої галявини; null, якщо галявин клієнт не знає. */
    private static String clearingDirection(Minecraft mc) {
        Player p = mc.player;
        if (p == null) return null;
        double bestD = Double.MAX_VALUE, bx = 0, bz = 0;
        for (ClearingSyncPacket.Rect r : ClientEvac.clearings()) {
            double cx = (r.minX() + r.maxX() + 1) / 2.0, cz = (r.minZ() + r.maxZ() + 1) / 2.0;
            double d = Math.hypot(cx - p.getX(), cz - p.getZ());
            if (d < bestD) { bestD = d; bx = cx; bz = cz; }
        }
        if (bestD == Double.MAX_VALUE) return null;
        // кут до цілі відносно погляду: 0 — просто попереду
        double toTarget = Math.toDegrees(Math.atan2(-(bx - p.getX()), bz - p.getZ()));
        double rel = Mth.wrapDegrees(toTarget - p.getYRot());
        String dir = Math.abs(rel) <= 45 ? "front" : Math.abs(rel) >= 135 ? "back" : rel > 0 ? "right" : "left";
        return Component.translatable("maniacmod.tab.dir." + dir, (int) Math.round(bestD)).getString();
    }

    /** Заповнює {@link #flags}/{@link #required}/{@link #done} для поточного режиму. */
    private void computeProgress(TabContext c, Mode mode) {
        ObjectiveSyncPacket obj = c.objectives;
        java.util.Arrays.fill(flags, false);
        if (mode == Mode.GENERATORS) {
            required = Math.max(0, Math.min(MAX_SLOTS, obj.generatorsRequired()));
            List<ObjectiveSyncPacket.GeneratorSlot> list = obj.generators();
            done = 0;
            for (int i = 0; i < required && i < list.size(); i++) {
                flags[i] = list.get(i).stage() == GeneratorPoi.Stage.DONE;
                if (flags[i]) done++;
            }
            return;
        }
        // Ланцюжок евакуації: живлення → виклик → ракета → мотузка.
        int step = ClientEvac.step();
        required = EVAC_LABELS.length;
        flags[0] = true;
        flags[1] = mode != Mode.RECEIVER;
        flags[2] = mode == Mode.HELI || mode == Mode.CLIMB;
        flags[3] = mode == Mode.CLIMB || step >= Step.CLIMB;
        done = 0;
        for (int i = 0; i < required; i++) if (flags[i]) done++;
    }

    // ── Оновлення анімацій ─────────────────────────────────────────────

    private void update(TabContext c, int slots) {
        boolean filling = c.t > 0.75f;
        for (int i = 0; i < MAX_SLOTS; i++) {
            float target = (filling && i < slots && flags[i]) ? 1f : 0f;
            slotShown[i] = UiAnim.approach(slotShown[i], target, 3.4f, c.dt);
            slotFlash[i] = Math.max(0f, slotFlash[i] - c.dt * 1.5f);
        }

        if (filling) {
            if (lastDone >= 0 && done > lastDone && slots > 0) {
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
        Mode mode = mode(c);
        if (mode != lastMode) {
            // Завдання змінилось (генератори → приймач → ракета → ...): слоти наповнюються заново.
            lastMode = mode;
            lastDone = -1;
            java.util.Arrays.fill(slotShown, 0f);
            counterShown = 0f;
        }
        computeProgress(c, mode);
        update(c, required);

        boolean complete = required > 0 && done >= required;
        float pulse = TabFx.pulse(c.nowMs, 1200f);

        TabFx.card(g, x, y, w, HEIGHT, ManiacTabTheme.CARD, ManiacTabTheme.CARD_EDGE);
        int accent = complete ? ManiacTabTheme.OK : ManiacTabTheme.SILVER;
        g.fill(x, y + 1, x + 2, y + HEIGHT - 1, TabFx.a(accent, 0.6f + 0.4f * pulse));

        drawViewport(c, x + 4, y + 4, VIEW_W, HEIGHT - 8, complete, mode);

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
            drawCounter(c, right, y + 11, done, required, complete);
        }

        // Рядок 3: слоти генераторів.
        int slotsRight = right - counterW - 6;
        if (required > 0) {
            drawSlots(c, tx, y + 35, slotsRight - tx, 11, required, mode != Mode.GENERATORS);
        }

        // Рядок 4: підказка «як саме».
        String hint = hintText(c);
        TabFx.text(g, c.font, TabFx.clip(c.font, hint, w - VIEW_W - 24, 0.75f),
            tx, y + HEIGHT - 11, 0.75f, TabFx.a(ManiacTabTheme.DIM, TabFx.stage(c.t, 0.7f, 0.4f)));

        flushBurst(c, tx, y + 35, slotsRight - tx, required);
    }

    // ── 3D-в'юпорт генератора ──────────────────────────────────────────

    private void drawViewport(TabContext c, int x, int y, int w, int h, boolean complete, Mode mode) {
        GuiGraphics g = c.g;
        g.fill(x, y, x + w, y + h, 0xFF07090A);
        g.fillGradient(x, y, x + w, y + h, 0x00000000,
            TabFx.a(complete ? 0xFF10331A : 0xFF20252B, 0.9f));

        boolean drawn = false;
        if (c.model3d && TabGeoView.available()) {
            float yaw = 30f + (c.nowMs % 9000L) / 9000f * 360f;
            float cx = x + w / 2f, base = y + h - 8;
            drawn = switch (mode) {
                case GENERATORS -> TabGeoView.drawGenerator(g, cx, base, 34f, yaw, complete);
                case RECEIVER   -> TabGeoView.drawReceiver(g, cx, base, 22f, yaw, "ready");
                case FLARE      -> TabGeoView.drawFlare(g, cx, base - 2, 36f, yaw);
                case HELI, CLIMB -> TabGeoView.drawHelicopter(g, cx, base - 6, 7f, yaw);
            };
        }
        if (!drawn) drawFallbackIcon(c, x, y, w, h, complete, mode);

        // Поверх моделі (глибина очищена, звичайний fill лягає зверху).
        TabFx.scanlines(g, x, y, w, h, 0x16000000, 3);
        UiFx.brackets(g, x, y, w, h, TabFx.a(ManiacTabTheme.SILVER_HI, 0.85f), 5, 0f, 0);

        // Індикатор-«лампа» стану у верхньому куті.
        boolean blink = ((c.nowMs / 380L) & 1L) == 0L;
        int lamp = complete ? ManiacTabTheme.OK : (blink ? ManiacTabTheme.WARN : 0xFF5A4318);
        g.fill(x + w - 8, y + 3, x + w - 4, y + 7, lamp);
        UiFx.glow(g, x + w - 8, y + 3, 4, 4, lamp & 0xFFFFFF, 0.5f, 2);
    }

    /** Плоскі заглушки, коли geo-вʼюпорт недоступний. */
    private void drawFallbackIcon(TabContext c, int x, int y, int w, int h, boolean complete, Mode mode) {
        GuiGraphics g = c.g;
        int cx = x + w / 2, cy = y + h / 2;
        switch (mode) {
            case GENERATORS -> drawFallbackGenerator(c, x, y, w, h, complete);
            case RECEIVER -> {
                g.fill(cx - 12, cy - 4, cx + 12, cy + 14, 0xFF3A424C);
                g.fill(cx - 10, cy - 2, cx + 10, cy + 12, 0xFF5C6673);
                g.fill(cx + 6, cy - 16, cx + 8, cy - 4, 0xFF9AA4B2);
                g.fill(cx - 8, cy, cx - 4, cy + 4, ((c.nowMs / 400L) & 1L) == 0L ? ManiacTabTheme.WARN : 0xFF5A4318);
            }
            case FLARE -> {
                g.fill(cx - 3, cy - 8, cx + 3, cy + 14, 0xFFC4221C);
                g.fill(cx - 3, cy + 2, cx + 3, cy + 5, 0xFFF0ECE2);
                g.fill(cx - 1, cy - 13, cx + 1, cy - 8, 0xFFD6AA46);
                g.fill(cx - 2, cy - 17, cx + 2, cy - 13, ((c.nowMs / 120L) & 1L) == 0L ? 0xFFFFBE32 : 0xFFFFF5C8);
            }
            case HELI, CLIMB -> {
                g.fill(cx - 12, cy - 2, cx + 8, cy + 8, 0xFF4A5560);
                g.fill(cx + 8, cy + 1, cx + 22, cy + 4, 0xFF4A5560);
                int blade = (int) ((c.nowMs / 60L) % 2L) * 6;
                g.fill(cx - 18 + blade, cy - 5, cx + 12 - blade, cy - 3, 0xFFB9C0CA);
            }
        }
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

    private void drawSlots(TabContext c, int x, int y, int width, int h, int n, boolean labeled) {
        GuiGraphics g = c.g;
        int gap = 3;
        int slotW = Math.max(10, Math.min(46, (width - gap * (n - 1)) / n));
        long now = c.nowMs;

        for (int i = 0; i < n; i++) {
            int sx = x + i * (slotW + gap);
            float appear = TabFx.stageOut(c.t, 0.55f + i * 0.04f, 0.3f);
            if (appear <= 0.01f) continue;

            // Проміжний прогрес не показуємо: слот світиться лише коли крок/генератор виконано.
            boolean isDone = flags[i];
            int color = isDone ? ManiacTabTheme.OK : ManiacTabTheme.SILVER;

            float f = slotShown[i];
            var pose = g.pose();
            pose.pushPose();
            pose.translate(0f, (1f - appear) * 6f, 0f);

            GeneratorFuelUiTheme.drawBarFx(g, sx, y, slotW, h, f, f, color, slotFlash[i]);

            if (isDone) {
                UiFx.glow(g, sx, y, slotW, h, 0x43C463, 0.35f + 0.25f * TabFx.pulse(now, 900f), 2);
            }
            if (labeled && !isDone && i < EVAC_LABELS.length) {
                String label = Component.translatable(EVAC_LABELS[i]).getString();
                float sc = 0.6f;
                int lw = TabFx.width(c.font, label, sc);
                TabFx.text(g, c.font, label, sx + (slotW - lw) / 2, y + 2, sc, TabFx.a(ManiacTabTheme.DIM, 1f));
            } else if (isDone) {
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

    /**
     * Віхи показуємо ПО РОЛІ, а не всі чотири кожному:
     *   • виживий — живлення й вихід (це його шлях; «перша кров» і «втеча» — чужі справи);
     *   • маньяк  — перша кров, живлення, вихід; «втеча» лише у фазі, що її допускає
     *               (так само, як у зведенні: раніше цей рядок видав би, що хтось знайшов вихід);
     *   • глядач  — усі чотири.
     */
    private static boolean milestoneRelevant(TabContext c, MatchObjectives.Objective o) {
        if (c.iAmSurvivor()) {
            return o == MatchObjectives.Objective.POWER_RESTORED
                || o == MatchObjectives.Objective.EXIT_OPENED;
        }
        if (c.iAmManiac()) {
            return o != MatchObjectives.Objective.SOMEONE_ESCAPED || c.escapePhase();
        }
        return true;
    }

    private void drawMilestones(TabContext c, int right, int y, ObjectiveSyncPacket obj) {
        record M(MatchObjectives.Objective o, String key, int color, TabIcons.Icon icon) {}
        M[] ms = {
            new M(MatchObjectives.Objective.SOMEONE_ESCAPED, "maniacmod.tab.milestone.escape", ManiacTabTheme.OK,         TabIcons.Icon.ESCAPED),
            new M(MatchObjectives.Objective.FIRST_BLOOD,     "maniacmod.tab.milestone.blood",  ManiacTabTheme.BLOOD_HOT,  TabIcons.Icon.DROP),
            new M(MatchObjectives.Objective.EXIT_OPENED,     "maniacmod.tab.milestone.exit",   ManiacTabTheme.SILVER_HI,  TabIcons.Icon.DOOR),
            new M(MatchObjectives.Objective.POWER_RESTORED,  "maniacmod.tab.milestone.power",  ManiacTabTheme.WARN,       TabIcons.Icon.BOLT),
        };
        float scale = 0.65f;
        int cursor = right;
        for (M m : ms) {
            if (!milestoneRelevant(c, m.o())) continue;
            String label = Component.translatable(m.key()).getString().toUpperCase(Locale.ROOT);
            boolean lit = obj.has(m.o());
            int lw = TabFx.width(c.font, label, scale);
            float p = lit ? TabFx.pulse(c.nowMs, 1100f) : 0f;
            int col = lit ? m.color() : ManiacTabTheme.DIM;
            float alpha = lit ? 1f : 0.45f;
            TabFx.textRight(c.g, c.font, label, cursor, y + 1, scale, TabFx.a(col, alpha));

            TabIcons.Icon icon = m.icon();
            int ix = cursor - lw - icon.w - 3;
            if (lit) UiFx.glow(c.g, ix, y, icon.w, icon.h, col & 0xFFFFFF, 0.35f * p + 0.15f, 2);
            TabIcons.draw(c.g, icon, ix, y, TabFx.a(col, alpha * (0.8f + 0.2f * p)), 1);
            cursor = ix - 7;
        }
    }
}

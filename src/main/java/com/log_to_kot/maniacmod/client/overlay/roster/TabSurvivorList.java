package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket.RosterEntry;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Права колонка tab-екрана: люди, розкладені ЗА ЗНАЧЕННЯМ, а не плоским
 * списком.
 *
 * ── Секції ───────────────────────────────────────────────────────────
 *   ВИЖИВШІ — повні картки 24 px: голова, ім'я, стан, хп-шкала. Зверху
 *             найтерміновіші (лежачі → повзуть → зламана нога → здорові):
 *             команда з першого погляду бачить, кому допомогти.
 *   ВИБУЛИ  — стислі рядки 13 px: хто втік, хто загинув.
 *   ГЛЯДАЧІ — імена в одну смугу.
 * Лобі — одна секція «ГРАВЦІ» з тими самими повними картками без хп.
 *
 * ── Що «живе» ───────────────────────────────────────────────────────
 *   • рядки пливуть на нові місця, коли змінюється порядок;
 *   • хп-шкала лишає «привида» втраченої частини, що повільно з'їдається,
 *     і спалахує при ударі — разом із глухим звуком і червоним мигтінням
 *     рядка, якщо Tab відкрито;
 *   • лежачий показує ТАЙМЕР до смерті зі шкалою (дані — з
 *     {@code DownedSurvivorsPacket}, той самий джерело, що й маркери на
 *     карті) — і червоний пульс усього рядка;
 *   • на вступі рядки в'їжджають по одному з затримкою.
 *
 * Анімаційний стан тримається в {@link #anims}; записи зниклих гравців
 * прибираються щокадру ({@code prune}), тож мапа не росте.
 */
final class TabSurvivorList {

    static final int ROW_H = 24;
    static final int COMPACT_H = 13;
    static final int HEADER_H = 14;
    private static final int SECTION_GAP = 4;
    private static final int MAX_SPECTATORS = 10;

    private static final class RowAnim {
        float y = Float.NaN;
        float ghost = -1f;
        float flash = 0f;
        float hit = 0f;
        int lastHp = -1;
        SurvivorState lastState = null;
    }

    private final Map<UUID, RowAnim> anims = new HashMap<>();

    void onOpen() {
        anims.clear();
    }

    // ── Підписи/кольори стану (спільні з TabSideCards) ──────────────────

    static String stateLabel(SurvivorState s) {
        return Component.translatable("maniacmod.tab.status." + s.name().toLowerCase(Locale.ROOT)).getString();
    }

    /**
     * Чи варто взагалі писати стан. «Здоровий» — норма: його вже каже хп-шкала,
     * а рядок «Здоровий» у кожного гравця лише засмічує список. Пишемо тільки те,
     * що змінює гру: зламана нога, повзе, без свідомості, втік, вибув.
     */
    static boolean worthShowing(SurvivorState s) {
        return s != SurvivorState.HEALTHY;
    }

    static int stateColor(SurvivorState s) {
        return switch (s) {
            case HEALTHY    -> ManiacTabTheme.OK;
            case BROKEN_LEG -> ManiacTabTheme.WARN;
            case CRAWLING   -> ManiacTabTheme.WARN;
            case UNCONSCIOUS-> ManiacTabTheme.BLOOD_HOT;
            case ESCAPED    -> ManiacTabTheme.CYAN;
            case ELIMINATED -> ManiacTabTheme.DIM;
        };
    }

    // ── Вимірювання ────────────────────────────────────────────────────

    int measure(TabContext c) {
        if (c.lobby) {
            return HEADER_H + Math.max(1, c.all.size()) * ROW_H;
        }
        int h = HEADER_H + Math.max(1, c.alive.size()) * ROW_H;
        if (!c.out.isEmpty()) h += SECTION_GAP + HEADER_H + c.out.size() * COMPACT_H;
        if (!c.spectators.isEmpty()) h += SECTION_GAP + HEADER_H + COMPACT_H;
        return h;
    }

    // ── Малювання ──────────────────────────────────────────────────────

    void draw(TabContext c, int x, int y, int w) {
        Set<UUID> present = new HashSet<>();
        int cursor = 0; // відносний Y від верху списку — не залежить від того, куди центровано панель

        if (c.lobby) {
            cursor = section(c, x, y, w, cursor, "maniacmod.tab.section.players", c.all.size(), ManiacTabTheme.SILVER, 0.40f);
            cursor = fullRows(c, x, y, w, cursor, c.all, present, false, 0.5f);
        } else {
            cursor = section(c, x, y, w, cursor, "maniacmod.tab.section.survivors", c.alive.size(), ManiacTabTheme.OK, 0.40f);
            if (c.alive.isEmpty()) {
                TabFx.text(c.g, c.font, Component.translatable("maniacmod.tab.empty.survivors").getString(),
                    x + 8, y + cursor + 8, 0.85f, TabFx.a(ManiacTabTheme.DIM, TabFx.stage(c.t, 0.6f, 0.3f)));
                cursor += ROW_H;
            } else {
                cursor = fullRows(c, x, y, w, cursor, c.alive, present, true, 0.5f);
            }
            if (!c.out.isEmpty()) {
                cursor += SECTION_GAP;
                cursor = section(c, x, y, w, cursor, "maniacmod.tab.section.out", c.out.size(), ManiacTabTheme.DIM, 0.75f);
                cursor = compactRows(c, x, y, w, cursor, c.out, present);
            }
            if (!c.spectators.isEmpty()) {
                cursor += SECTION_GAP;
                cursor = section(c, x, y, w, cursor, "maniacmod.tab.section.spectators", c.spectators.size(), ManiacTabTheme.DIM, 0.85f);
                spectatorStrip(c, x, y + cursor, w);
            }
        }
        anims.keySet().retainAll(present);
    }

    // ── Заголовок секції ───────────────────────────────────────────────

    private int section(TabContext c, int x, int y, int w, int cursor, String key, int count, int color, float delay) {
        float p = TabFx.stageOut(c.t, delay - 0.15f, 0.3f);
        if (p > 0.01f) {
            String label = Component.translatable(key).getString().toUpperCase(Locale.ROOT);
            int ly = y + cursor;
            TabFx.text(c.g, c.font, label, x + 2, ly + 3, 0.8f, TabFx.a(color, p));
            int lw = TabFx.width(c.font, label, 0.8f);
            String n = Integer.toString(count);
            int bx = x + 2 + lw + 5;
            int bw = TabFx.width(c.font, n, 0.8f) + 6;
            c.g.fill(bx, ly + 1, bx + bw, ly + 11, TabFx.a(color, 0.18f * p));
            TabFx.text(c.g, c.font, n, bx + 3, ly + 3, 0.8f, TabFx.a(color, p));
            int lineX = bx + bw + 5;
            int lineW = (int) ((x + w - lineX) * p);
            c.g.fill(lineX, ly + 6, lineX + lineW, ly + 7, TabFx.a(ManiacTabTheme.CARD_EDGE, 1f));
            c.g.fill(lineX, ly + 6, lineX + Math.min(lineW, 18), ly + 7, TabFx.a(color, 0.8f * p));
        }
        return cursor + HEADER_H;
    }

    // ── Повні рядки ────────────────────────────────────────────────────

    private int fullRows(TabContext c, int x, int y, int w, int cursor, List<RosterEntry> list,
                         Set<UUID> present, boolean withVitals, float baseDelay) {
        for (int i = 0; i < list.size(); i++) {
            RosterEntry e = list.get(i);
            present.add(e.playerId());
            RowAnim a = anims.computeIfAbsent(e.playerId(), k -> new RowAnim());

            float target = cursor + i * ROW_H;
            a.y = Float.isNaN(a.y) ? target : UiAnim.approach(a.y, target, 14f, c.dt);

            float appear = TabFx.stageOut(c.t, baseDelay + i * 0.055f, 0.32f);
            if (appear <= 0.01f) continue;
            drawFullRow(c, e, a, x, Math.round(y + a.y), w, i, appear, withVitals);
        }
        return cursor + Math.max(1, list.size()) * ROW_H;
    }

    private void drawFullRow(TabContext c, RosterEntry e, RowAnim a, int x, int y, int w,
                             int index, float appear, boolean withVitals) {
        GuiGraphics g = c.g;
        boolean isMe = e.playerId().equals(c.me);
        SurvivorState state = e.state();
        boolean downed = withVitals && state == SurvivorState.UNCONSCIOUS;

        // ── реакція на зміну хп/стану (лише поки Tab відкрито й анімація не на вступі) ──
        if (withVitals && c.t > 0.9f) {
            if (a.lastHp >= 0 && e.hp() < a.lastHp) {
                a.flash = 1f;
                a.hit = 1f;
                UiAnim.playTabAlarm();
            }
            if (a.lastState != null && a.lastState != state
                && ClientMatchState.phase().isGameplay()
                && TabContext.urgencyRank(state) < TabContext.urgencyRank(a.lastState)) {
                a.flash = 1f;
            }
        }
        a.lastHp = e.hp();
        a.lastState = state;
        a.flash = Math.max(0f, a.flash - c.dt * 1.6f);
        a.hit = Math.max(0f, a.hit - c.dt * 3.0f);

        var pose = g.pose();
        pose.pushPose();
        pose.translate((1f - appear) * 34f, 0f, 0f);
        int h = ROW_H - 1;
        int rowW = w;

        // ── фон ──
        if (downed) {
            c.theme.drawRespawning(g, c.font, x, rowW, h, x + 30, y + (h - c.font.lineHeight) / 2,
                "", "", appear, c.style.tickCount());
        } else {
            c.theme.drawRowBg(g, x, y, rowW, h, index, isMe, 0, appear, c.style.tickCount());
        }
        if (a.flash > 0.02f) {
            g.fill(x, y, x + rowW, y + h, TabFx.a(ManiacTabTheme.BLOOD_HOT, a.flash * 0.45f));
        }

        // ── голова ──
        int head = c.theme.headSize();
        int hx = x + c.theme.headOffsetX();
        int hy = y + (h - head) / 2;
        if (a.hit > 0.02f) hx += Math.round((float) Math.sin(c.nowMs / 18.0) * 2f * a.hit);
        c.style.drawHead(g, e.playerId(), hx, hy, head, 1f);
        if (state == SurvivorState.ESCAPED || state == SurvivorState.ELIMINATED) {
            g.fill(hx, hy, hx + head, hy + head, TabFx.a(0xFF000000, 0.55f));
        }

        // ── ім'я + підпис стану ──
        int nameX = x + c.theme.nameOffsetX();
        int rightBlock = withVitals ? 96 : 0;
        String name = TabFx.clip(c.font, e.displayName(), w - (nameX - x) - rightBlock - 6, 1f);
        int nameColor = isMe ? ManiacTabTheme.SILVER_HI : 0xFFE6E9EE;
        // Здоровий гравець: лише ім'я по центру рядка (стан — норма, нічого писати).
        boolean showState = withVitals && worthShowing(state);
        int nameY = withVitals && !showState ? y + (h - c.font.lineHeight) / 2 : y + 3;
        TabFx.text(g, c.font, name, nameX, nameY, 1f, TabFx.a(downed ? 0xFFFFB0B5 : nameColor, 1f));

        if (withVitals) {
            if (showState) {
                int sc = stateColor(state);
                float blink = 0.75f + 0.25f * TabFx.pulse(c.nowMs, 900f);
                TabIcons.Icon icon = TabIcons.forState(state);
                TabIcons.draw(g, icon, nameX, y + 14, TabFx.a(sc, blink), 1);
                TabFx.text(g, c.font, stateLabel(state), nameX + icon.w + 4, y + 14, 0.7f, TabFx.a(sc, 1f));
            }
            drawVitals(c, e, a, downed, x + w - rightBlock - 4, y, rightBlock, h);
        } else if (isMe) {
            String you = Component.translatable("maniacmod.tab.tag.you").getString().toUpperCase(Locale.ROOT);
            TabFx.textRight(g, c.font, you, x + w - 8, y + 8, 0.75f, TabFx.a(ManiacTabTheme.SILVER_HI, 1f));
        }
        pose.popPose();
    }

    private void drawVitals(TabContext c, RosterEntry e, RowAnim a, boolean downed, int bx, int y, int bw, int h) {
        GuiGraphics g = c.g;
        int right = bx + bw;

        if (downed) {
            // Таймер до смерті: реальні дані сервера, а не вигадка клієнта.
            DownedSurvivorsPacket.Entry d = downedEntry(e.playerId());
            if (d != null && !d.timed()) {
                // Лежить / несуть: таймера немає, показуємо лише статус.
                String label = Component.translatable("maniacmod.hook.status." + d.status()).getString();
                TabFx.textRight(g, c.font, label, right, y + 3, 0.9f, TabFx.a(ManiacTabTheme.WARN, 1f));
                GeneratorFuelUiTheme.drawBarFx(g, bx, y + 15, bw, 5, 1f, 1f, ManiacTabTheme.WARN, 0f);
                return;
            }
            if (d != null) {
                long ms = ClientMatchState.downedMillisLeft(d);
                float frac = ClientMatchState.downedFraction(d);
                boolean urgent = ms < 15_000L;
                int col = d.paused() ? ManiacTabTheme.WARN : ManiacTabTheme.BLOOD_HOT;
                String time = TabFx.mmss(ms);
                float blink = urgent && !d.paused() ? (0.55f + 0.45f * TabFx.pulse(c.nowMs, 420f)) : 1f;
                TabFx.textRight(g, c.font, time, right, y + 3, 1.25f, TabFx.a(col, blink));
                GeneratorFuelUiTheme.drawBarFx(g, bx, y + 15, bw, 5, frac, frac, col, 0f);
                if (d.paused()) {
                    String p = Component.translatable("maniacmod.tab.downed.paused").getString();
                    TabFx.textRight(g, c.font, p, bx + bw - 34, y + 4, 0.65f, TabFx.a(ManiacTabTheme.WARN, 1f));
                }
                return;
            }
        }

        int maxHp = Math.max(1, e.maxHp());
        float frac = UiAnim.clamp01(e.hp() / (float) maxHp);
        // «Привид» втраченої частини: стрибає до нової ваги миттєво, з'їдається повільно.
        if (a.ghost < 0f || frac > a.ghost) a.ghost = frac;
        else a.ghost = UiAnim.approach(a.ghost, frac, 1.8f, c.dt);

        int col = TabSideCards.hpColor(frac);
        TabFx.textRight(g, c.font, Integer.toString(e.hp()), right - 22, y + 2, 1.2f, TabFx.a(col, 1f));
        TabFx.text(g, c.font, "/" + maxHp, right - 21, y + 6, 0.65f, TabFx.a(ManiacTabTheme.DIM, 1f));
        GeneratorFuelUiTheme.drawBarFx(g, bx, y + 15, bw, 5, frac, a.ghost, col, a.flash * 0.6f);
    }

    private static DownedSurvivorsPacket.Entry downedEntry(UUID id) {
        for (DownedSurvivorsPacket.Entry d : ClientMatchState.downedEntries()) {
            if (d.id().equals(id)) return d;
        }
        return null;
    }

    // ── Стислі рядки (вибули) ───────────────────────────────────────────

    private int compactRows(TabContext c, int x, int y, int w, int cursor, List<RosterEntry> list, Set<UUID> present) {
        for (int i = 0; i < list.size(); i++) {
            RosterEntry e = list.get(i);
            present.add(e.playerId());
            float appear = TabFx.stageOut(c.t, 0.8f + i * 0.04f, 0.3f);
            if (appear <= 0.01f) continue;

            int ry = y + cursor + i * COMPACT_H;
            boolean escaped = e.state() == SurvivorState.ESCAPED;
            int col = stateColor(e.state());
            var pose = c.g.pose();
            pose.pushPose();
            pose.translate((1f - appear) * 22f, 0f, 0f);

            c.g.fill(x, ry, x + w, ry + COMPACT_H - 1, TabFx.a(0xFF0B0D0F, 0.85f));
            c.g.fill(x, ry, x + 2, ry + COMPACT_H - 1, TabFx.a(col, 0.8f));
            c.style.drawHead(c.g, e.playerId(), x + 6, ry + 1, 10, 1f);
            c.g.fill(x + 6, ry + 1, x + 16, ry + 11, TabFx.a(0xFF000000, 0.5f));

            String name = TabFx.clip(c.font, e.displayName(), w - 90, 0.85f);
            if (escaped) {
                TabFx.text(c.g, c.font, name, x + 20, ry + 2, 0.85f, TabFx.a(0xFFC9D2DE, 1f));
            } else {
                c.theme.drawStrikeThrough(c.g, c.font, name, x + 20, ry + 2, 1f, c.style.tickCount());
            }
            String stateText = stateLabel(e.state()).toUpperCase(Locale.ROOT);
            TabFx.textRight(c.g, c.font, stateText, x + w - 6, ry + 2, 0.7f, TabFx.a(col, 1f));
            TabIcons.Icon stateIcon = TabIcons.forState(e.state());
            int iconX = x + w - 6 - TabFx.width(c.font, stateText, 0.7f) - stateIcon.w - 4;
            TabIcons.draw(c.g, stateIcon, iconX, ry + 3, TabFx.a(col, 1f), 1);
            pose.popPose();
        }
        return cursor + list.size() * COMPACT_H;
    }

    // ── Глядачі ────────────────────────────────────────────────────────

    private void spectatorStrip(TabContext c, int x, int y, int w) {
        float p = TabFx.stageOut(c.t, 0.9f, 0.3f);
        if (p <= 0.01f) return;
        c.g.fill(x, y, x + w, y + COMPACT_H - 1, TabFx.a(0xFF0B0D0F, 0.85f * p));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (RosterEntry e : c.spectators) {
            if (shown == MAX_SPECTATORS) break;
            if (shown > 0) sb.append("  \u00b7  ");
            sb.append(e.displayName());
            shown++;
        }
        if (c.spectators.size() > shown) sb.append("  +").append(c.spectators.size() - shown);
        TabFx.text(c.g, c.font, TabFx.clip(c.font, sb.toString(), w - 12, 0.8f), x + 6, y + 2, 0.8f,
            TabFx.a(ManiacTabTheme.DIM, p));
    }
}

package com.log_to_kot.maniacmod.client.overlay.roster;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.ManiacTabTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Locale;

/**
 * Друга картка лівої колонки — залежить від того, ХТО дивиться:
 *   • виживий → «ТИ»: власна 3D-модель, хп великими цифрами, шкала, стан;
 *   • маньяк / глядач → «ЗВЕДЕННЯ»: живі, лежать, втекли, вибули — з
 *     лічильниками, що «докручуються» до значення, і мініатюрними шкалами;
 *   • лобі → «ТИ» на всю висоту колонки (хп немає).
 * Виживому зведення не потрібне: його дублює заголовок списку виживих.
 */
final class TabSideCards {

    private static final int STAT_COUNT = 4;
    private final float[] statShown = new float[STAT_COUNT];

    /** Лічильники наново «докручуються» щоразу, як відкрито Tab. */
    void onOpen() {
        Arrays.fill(statShown, 0f);
    }

    // ── «ТИ» ─────────────────────────────────────────────────────────

    /**
     * @param lobby у лобі нема хп/стану — картка показує лише модель і напис
     *              «очікування», тож може бути високою.
     */
    void drawSelf(TabContext c, int x, int y, int w, int h, boolean lobby) {
        GuiGraphics g = c.g;
        float pulse = TabFx.pulse(c.nowMs, 1300f);
        SurvivorState state = ClientMatchState.survivorState();
        boolean survivor = c.iAmSurvivor();
        boolean down = survivor && state.isCrawlOnly();

        int edge = down ? TabFx.a(ManiacTabTheme.BLOOD_HOT, 0.5f + 0.4f * pulse) : ManiacTabTheme.CARD_EDGE;
        TabFx.card(g, x, y, w, h, ManiacTabTheme.CARD, edge);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, 0x00000000, TabFx.a(0xFF1B2126, 0.9f));

        String tag = Component.translatable("maniacmod.tab.tag.you").getString().toUpperCase(Locale.ROOT);
        TabFx.text(g, c.font, tag, x + 6, y + 5, 0.8f, TabFx.a(ManiacTabTheme.SILVER_HI, 1f));
        g.fill(x + 6, y + 14, x + 6 + TabFx.width(c.font, tag, 0.8f), y + 15, TabFx.a(ManiacTabTheme.SILVER, 0.7f));

        // ── 3D ──
        LocalPlayer me = Minecraft.getInstance().player;
        int viewW = lobby ? w - 8 : 58;
        float cx = x + 4 + viewW / 2f;
        boolean drawn = false;
        if (me != null && c.model3d && TabModel3d.available()) {
            float scale = (h - (lobby ? 44 : 26)) / 1.8f;
            float intro = 1f - TabFx.stageOut(c.t, 0.6f, 1.0f);
            float sway = (float) Math.sin(c.nowMs / 1100.0) * 26f;
            drawn = TabModel3d.render(g, me, cx, y + h - (lobby ? 28 : 8), scale, 180f + intro * 360f + sway, 6f);
        }
        if (!drawn && c.myEntry != null) {
            c.style.drawHead(g, c.myEntry.playerId(), (int) cx - 16, y + h / 2 - 16, 32, 1f);
        }
        TabFx.scanlines(g, x + 1, y + 16, viewW, h - 17, 0x14000000, 3);

        if (lobby) {
            String name = c.myEntry != null ? c.myEntry.displayName() : "";
            TabFx.textCentered(g, c.font, TabFx.clip(c.font, name, w - 12, 1f), x + w / 2f, y + h - 22, 1f,
                TabFx.a(0xFFF2F4F8, 1f));
            String wait = Component.translatable("maniacmod.tab.self.lobby").getString();
            TabFx.textCentered(g, c.font, wait, x + w / 2f, y + h - 11, 0.7f,
                TabFx.a(ManiacTabTheme.OK, 0.5f + 0.5f * pulse));
            return;
        }
        if (!survivor) return;

        // ── Показники справа від моделі ──
        int tx = x + 4 + viewW + 4;
        int right = x + w - 6;
        int hp = ClientMatchState.hp();
        int maxHp = Math.max(1, ClientMatchState.maxHp());
        float frac = UiAnim.clamp01(hp / (float) maxHp);
        int hpColor = hpColor(frac);

        TabFx.text(g, c.font, Integer.toString(hp), tx, y + 22, 2.0f, TabFx.a(hpColor, 1f));
        TabFx.textRight(g, c.font, "/" + maxHp, right, y + 33, 0.75f, TabFx.a(ManiacTabTheme.DIM, 1f));
        GeneratorFuelUiTheme.drawBarFx(g, tx, y + 44, right - tx, 7, frac, frac, hpColor, 0f);

        String label = TabSurvivorList.stateLabel(state);
        TabFx.text(g, c.font, TabFx.clip(c.font, label, right - tx, 0.75f), tx, y + 55, 0.75f,
            TabFx.a(TabSurvivorList.stateColor(state), 1f));

        // ЕКГ під показниками — «живе» тим швидше, чим менше хп.
        float bpm = down ? 0f : 66f + (1f - frac) * 70f;
        int ey = y + h - 20;
        if (bpm <= 0f) {
            g.fill(tx, ey + 7, right, ey + 8, TabFx.a(ManiacTabTheme.BLOOD, 0.8f));
        } else {
            float phase = ((c.nowMs / 1000f) * bpm / 60f) % 1f;
            UiFx.ecg(g, tx, ey, right - tx, 14, phase, hpColor & 0xFFFFFF, 0.9f, 0.9f);
        }
    }

    // ── «ЗВЕДЕННЯ» ────────────────────────────────────────────────────

    void drawStats(TabContext c, int x, int y, int w, int h) {
        GuiGraphics g = c.g;
        TabFx.card(g, x, y, w, h, ManiacTabTheme.CARD, ManiacTabTheme.CARD_EDGE);

        String title = Component.translatable("maniacmod.tab.card.stats").getString().toUpperCase(Locale.ROOT);
        TabFx.text(g, c.font, title, x + 6, y + 5, 0.8f, TabFx.a(ManiacTabTheme.SILVER_HI, 1f));
        g.fill(x + 6, y + 14, x + 6 + TabFx.width(c.font, title, 0.8f), y + 15, TabFx.a(ManiacTabTheme.SILVER, 0.7f));

        // Маньяк: «втекли» — лише у фазі втечі, «загинули» → «твої жертви».
        boolean maniacView = c.iAmManiac();
        boolean showEscaped = !maniacView || c.escapePhase();
        int total = Math.max(1, c.alive.size() + c.out.size());
        int[] allValues = { c.alive.size() - c.downedCount(), c.downedCount(), c.escapedCount(), c.eliminatedCount() };
        String[] allKeys = { "alive", "downed", "escaped", maniacView ? "kills" : "out" };
        int[] allColors = { ManiacTabTheme.OK, ManiacTabTheme.BLOOD_HOT, ManiacTabTheme.SILVER_HI, ManiacTabTheme.DIM };

        int rows = showEscaped ? STAT_COUNT : STAT_COUNT - 1;
        int[] values = new int[rows];
        String[] keys = new String[rows];
        int[] colors = new int[rows];
        for (int i = 0, r = 0; i < STAT_COUNT; i++) {
            if (i == 2 && !showEscaped) continue;
            values[r] = allValues[i];
            keys[r] = allKeys[i];
            colors[r] = allColors[i];
            r++;
        }

        int rowH = 15;
        int top = y + 20;
        for (int i = 0; i < rows; i++) {
            float appear = TabFx.stageOut(c.t, 0.7f + i * 0.07f, 0.3f);
            statShown[i] = UiAnim.approach(statShown[i], c.t > 0.85f ? values[i] : 0f, 6f, c.dt);
            if (appear <= 0.01f) continue;

            int ry = top + i * rowH;
            var pose = g.pose();
            pose.pushPose();
            pose.translate((1f - appear) * -14f, 0f, 0f);

            int col = colors[i];
            g.fill(x + 6, ry + 2, x + 9, ry + 11, TabFx.a(col, values[i] > 0 ? 1f : 0.35f));
            String label = Component.translatable("maniacmod.tab.stats." + keys[i]).getString();
            TabFx.text(g, c.font, label, x + 13, ry + 1, 0.8f, TabFx.a(ManiacTabTheme.DIM, 1f));
            TabFx.textRight(g, c.font, Integer.toString(Math.round(statShown[i])), x + w - 7, ry, 1.2f,
                TabFx.a(values[i] > 0 ? col : ManiacTabTheme.DIM, 1f));
            // Мініатюрна шкала: частка цієї групи від усіх виживих.
            GeneratorFuelUiTheme.drawBarFx(g, x + 13, ry + 10, w - 20, 3,
                UiAnim.clamp01(statShown[i] / total), UiAnim.clamp01(statShown[i] / total), col, 0f);
            pose.popPose();
        }
    }

    // ── Спільне ───────────────────────────────────────────────────────

    static int hpColor(float frac) {
        if (frac >= 0.6f) return ManiacTabTheme.OK;
        if (frac >= 0.3f) return ManiacTabTheme.WARN;
        return ManiacTabTheme.BLOOD_HOT;
    }
}

package com.log_to_kot.maniacmod.client.screen.statistics;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.style.BloodFx;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ДЕТАЛЬНА статистика матчу (кнопка «Статистика» на новому екрані підсумків
 * {@link MatchResultScreen}; «Готово»/Esc повертає назад на нього). Підсумки матчу: кривавий кінематографічний екран. Угорі — великий титул переможця з патьоками,
 * під ним картки (час гри, генератори, втекли), далі таблиця всіх учасників і окрема панель
 * «твоя статистика» з лічильниками, що накручуються. Дані — з {@link MatchSummaryPacket}
 * (сервер шле його перед ENDING); без нього показується лише титул.
 */
public final class MatchStatsScreen extends Screen {

    private static final int RED_DEEP = 0xFF1A0003;

    private long openedAtMs;
    private final Screen parent;

    private final MatchSummaryPacket snapshot;
    private final boolean outcomeSurvivorsWon;

    /** Дані — знімок з батьківського екрана (стан клієнта після скидання матчу вже порожній). */
    public MatchStatsScreen(Screen parent, MatchSummaryPacket snapshot, boolean survivorsWon) {
        super(Component.translatable("maniacmod.result.title"));
        this.parent = parent;
        this.snapshot = snapshot;
        this.outcomeSurvivorsWon = survivorsWon;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    private static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private float t(long now, long delay, long dur) {
        return UiAnim.easeOutCubic(UiAnim.clamp01((now - openedAtMs - delay) / (float) dur));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        if (openedAtMs == 0L) openedAtMs = now;
        float open = UiAnim.clamp01((now - openedAtMs) / 600f);

        // Фон: майже чорний, пульсуючий багряний серпанок знизу.
        g.fill(0, 0, width, height, BloodFx.a(0xFF000000, 0.93f * open));
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 900.0);
        for (int i = 0; i < 30; i++) {
            float k = i / 30f;
            g.fill(0, height - Math.round(height * 0.5f * (1 - k)), width, height - Math.round(height * 0.5f * (1 - k)) + height / 30 + 1,
                BloodFx.a(BloodFx.DARK, (0.05f + 0.05f * pulse) * k * open));
        }
        // Патьоки зверху через усю ширину.
        int drips = Math.max(8, width / 22);
        for (int i = 0; i < drips; i++) {
            float ph = BloodFx.hash01(i * 17L + 3);
            float grow = UiAnim.clamp01((now - openedAtMs - 200 - ph * 1200) / 2600f);
            BloodFx.drip(g, Math.round((i + 0.5f) * width / drips), 0, 2 + (int) (ph * 3),
                grow * (10 + ph * 38), now, ph, 0.75f * open);
        }

        MatchSummaryPacket s = snapshot;
        boolean survivorsWon = s != null ? s.survivorsWon() : outcomeSurvivorsWon;

        // ── Титул ──
        String head = Component.translatable(survivorsWon
            ? "maniacmod.result.big_survivors" : "maniacmod.result.big_maniac").getString().toUpperCase(Locale.ROOT);
        float hIn = t(now, 100, 700);
        float sc = 3.4f * (1.25f - 0.25f * hIn);
        int col = BloodFx.a(survivorsWon ? 0xFFE8E8E8 : BloodFx.BRIGHT, hIn);
        int tw = font.width(head);
        int titleY = 18;
        g.pose().pushPose();
        g.pose().translate(width / 2f, titleY, 0f);
        g.pose().scale(sc, sc, 1f);
        if (((col >>> 24) & 0xFF) >= 8) {
            g.drawString(font, head, Math.round(-tw / 2f) + 1, 1, BloodFx.a(BloodFx.DARK, hIn), false);
            g.drawString(font, head, Math.round(-tw / 2f), 0, col, false);
        }
        g.pose().popPose();
        int y = titleY + Math.round(9 * sc) + 10;

        if (s == null) {
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // ── Картки ──
        int cardW = Math.min(150, (width - 60) / 3);
        int gap = 12;
        int totalW = cardW * 3 + gap * 2;
        int cx0 = width / 2 - totalW / 2;
        int escaped = 0, total = 0;
        for (Entry e : s.entries()) {
            if (e.result() == MatchSummaryPacket.Result.MANIAC) continue;
            total++;
            if (e.result() == MatchSummaryPacket.Result.ESCAPED) escaped++;
        }
        float c1 = t(now, 500, 500);
        card(g, cx0, y, cardW, 38, c1, Component.translatable("maniacmod.result.card.time").getString(),
            clock(Math.round(s.playedSeconds() * t(now, 650, 1200))), BloodFx.BRIGHT);
        card(g, cx0 + cardW + gap, y, cardW, 38, t(now, 620, 500),
            Component.translatable("maniacmod.result.card.generators").getString(),
            Math.round(s.generatorsDone() * t(now, 800, 900)) + " / " + s.generatorsRequired(), 0xFFE0A030);
        card(g, cx0 + 2 * (cardW + gap), y, cardW, 38, t(now, 740, 500),
            Component.translatable("maniacmod.result.card.escaped").getString(),
            Math.round(escaped * t(now, 950, 700)) + " / " + total, escaped > 0 ? 0xFF59D26A : BloodFx.MID);
        y += 38 + 12;

        // ── Таблиця (ліворуч) + моя статистика (праворуч) ──
        UUID me = Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getUUID();
        Entry mine = null;
        for (Entry e : s.entries()) if (e.id().equals(me)) mine = e;

        boolean wide = width >= 520 && mine != null;
        int tableW = wide ? Math.min(width - 40 - 190 - 14, 360) : Math.min(width - 40, 380);
        int tableX = wide ? (width - (tableW + 14 + 190)) / 2 : width / 2 - tableW / 2;
        int tableBottom = height - 36;

        g.drawString(font, Component.translatable("maniacmod.result.table").getString().toUpperCase(Locale.ROOT),
            tableX, y, 0xFF8A8A8A, false);
        g.fill(tableX, y + 10, tableX + tableW, y + 11, BloodFx.a(BloodFx.MID, 0.8f));
        int ry = y + 16;
        List<Entry> sorted = new ArrayList<>(s.entries());
        sorted.sort((a, b) -> Integer.compare(order(a), order(b)));
        int rowH = 14;
        int i = 0;
        for (Entry e : sorted) {
            if (ry + rowH > tableBottom) break;
            float rt = t(now, 900 + i * 110L, 380);
            int slide = Math.round((1f - rt) * -30f);
            int rc = switch (e.result()) {
                case ESCAPED -> 0xFF59D26A;
                case MANIAC -> BloodFx.BRIGHT;
                case ELIMINATED, CAUGHT -> 0xFFB04040;
            };
            if (e.id().equals(me)) g.fill(tableX - 3, ry - 2, tableX + tableW + 3, ry + rowH - 2, BloodFx.a(BloodFx.MID, 0.35f * rt));
            g.fill(tableX + slide, ry + 1, tableX + slide + 3, ry + 8, BloodFx.a(0xFF000000 | rc, rt));
            if (rt > 0.05f) {
                int a = Math.max(8, Math.round(255 * rt));
                g.drawString(font, e.name(), tableX + 8 + slide, ry, (a << 24) | 0xE6E6E6, false);
                String tag = Component.translatable("maniacmod.result.tag." + e.result().name().toLowerCase(Locale.ROOT)).getString();
                g.drawString(font, tag, tableX + tableW - font.width(tag) - 46 + slide, ry, (a << 24) | (rc & 0xFFFFFF), false);
                if (e.result() != MatchSummaryPacket.Result.MANIAC) {
                    String tm = clock(e.survivedSeconds());
                    g.drawString(font, tm, tableX + tableW - font.width(tm) + slide, ry, (a << 24) | 0x9A9A9A, false);
                }
            }
            ry += rowH;
            i++;
        }

        // ── Моя статистика ──
        if (mine != null) {
            int px = wide ? tableX + tableW + 14 : width / 2 - tableW / 2;
            int pw = wide ? 190 : tableW;
            int py = wide ? y : Math.min(ry + 6, tableBottom - 74);
            float pt = t(now, 1000, 600);
            if (wide || py + 70 < height - 30) {
                g.fill(px, py, px + pw, py + 10 + 11 * 6 + 8, BloodFx.a(RED_DEEP, 0.85f * pt));
                BloodFx.border1(g, px, py, pw, 10 + 11 * 6 + 8, BloodFx.a(BloodFx.MID, pt));
                g.drawString(font, Component.translatable("maniacmod.result.mine").getString().toUpperCase(Locale.ROOT),
                    px + 8, py + 6, BloodFx.a(BloodFx.HOT, pt), false);
                float cnt = t(now, 1300, 1000);
                String[] keys = {"hits_dealt", "hits_taken", "downs", "hooks", "rescues", "repair"};
                int[] vals = {mine.hitsDealt(), mine.hitsTaken(), mine.downs(), mine.hooks(), mine.rescues(), mine.repairSeconds()};
                for (int k = 0; k < keys.length; k++) {
                    int ly = py + 20 + k * 11;
                    String label = Component.translatable("maniacmod.result.stat." + keys[k]).getString();
                    String val = k == 5 ? clock(Math.round(vals[k] * cnt)) : String.valueOf(Math.round(vals[k] * cnt));
                    int a = Math.max(8, Math.round(255 * pt));
                    g.drawString(font, label, px + 8, ly, (a << 24) | 0xA8A8A8, false);
                    g.drawString(font, val, px + pw - 8 - font.width(val), ly, (a << 24) | 0xFFFFFF, false);
                }
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private static int order(Entry e) {
        return switch (e.result()) {
            case MANIAC -> 0;
            case ESCAPED -> 1;
            case CAUGHT -> 2;
            case ELIMINATED -> 3;
        };
    }

    private void card(GuiGraphics g, int x, int y, int w, int h, float in, String label, String value, int rgbOrArgb) {
        int slide = Math.round((1f - in) * 14f);
        int yy = y + slide;
        g.fill(x, yy, x + w, yy + h, BloodFx.a(RED_DEEP, 0.85f * in));
        BloodFx.border1(g, x, yy, w, h, BloodFx.a(BloodFx.MID, in));
        g.fill(x, yy, x + 2, yy + h, BloodFx.a(0xFF000000 | (rgbOrArgb & 0xFFFFFF), in));
        int a = Math.max(8, Math.round(255 * in));
        g.drawString(font, label.toUpperCase(Locale.ROOT), x + 8, yy + 6, (a << 24) | 0x8A8A8A, false);
        g.pose().pushPose();
        g.pose().translate(x + 8, yy + 17, 0f);
        g.pose().scale(1.6f, 1.6f, 1f);
        g.drawString(font, value, 0, 0, (a << 24) | (rgbOrArgb & 0xFFFFFF), false);
        g.pose().popPose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

package com.log_to_kot.maniacmod.client.evac;

import com.log_to_kot.maniacmod.client.style.GeneratorFuelUiTheme;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Панель диспетчера внизу екрана: рація, еквалайзер, «друкарський» текст репліки.
 *
 * <p>Текст відкривається ВІД ТОГО Ж моменту, що й звук ({@link ClientDispatcher.Session#startMs}),
 * і закінчується на {@link #TYPE_FRACTION} тривалості файлу. Швидкість нерівна, як у живої мови:
 * кома і тире — коротка пауза, крапка/знак оклику — довша ({@link #weight}). Свіжі букви
 * з'являються бурштиновими й за мить біліють; на початку кожної репліки — короткий «збій»
 * сигналу. Усе рахується від {@link System#currentTimeMillis()}, тож не залежить від тіків.</p>
 *
 * <p>Позицію змінює {@link #BOTTOM_OFFSET} (відступ від низу екрана до нижнього краю панелі).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DispatcherOverlay {

    private static final float TYPE_FRACTION = 0.86f;
    private static final int BOTTOM_OFFSET = 78;
    private static final int MAX_W = 380;
    private static final int FRESH_CHARS = 4;

    private static final int AMBER = 0xFFE0A030, WHITE = 0xFFFFFFFF, DIM = 0xFF7A7A7A,
        RED = 0xFFE5483C, CASE = 0xFF3C4336, CASE_DARK = 0xFF171A14, TRACK = 0xFF2A3038;

    private DispatcherOverlay() {}

    public static void render(GuiGraphics g) {
        ClientDispatcher.Session s = ClientDispatcher.session();
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (now >= s.endMs + ClientDispatcher.FADE_OUT_MS) { ClientDispatcher.stop(); return; }

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        long age = now - s.t0;
        float appear = UiAnim.easeOutBack(UiAnim.clamp01(age / 380f), 0.7f);
        float fadeOut = UiAnim.clamp01((now - s.endMs) / (float) ClientDispatcher.FADE_OUT_MS);
        float a = UiAnim.clamp01(age / 220f) * (1f - fadeOut);
        if (a <= 0.02f) return;

        int w = Math.min(MAX_W, sw - 24);
        int textW = w - 28;
        // Висота резервується під НАЙДОВШУ репліку — панель не стрибає між репліками.
        int maxLines = 1;
        for (String t : s.text) maxLines = Math.max(maxLines, wrap(font, t, textW).size());
        maxLines = Math.min(maxLines, 4);
        int h = 30 + maxLines * 11 + 10;
        int x = (sw - w) / 2;
        int y = sh - BOTTOM_OFFSET - h + Math.round((1f - appear) * 26f);

        // ── Панель ───────────────────────────────────────────────────────
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, a);
        GeneratorFuelUiTheme.drawPanel(g, x, y, w, h);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        g.fill(x + 1, y + 1, x + 4, y + h - 1, GeneratorFuelUiTheme.alpha(AMBER, a));

        // Яка репліка зараз (-1 — ще встановлюється зв'язок).
        int idx = -1;
        for (int i = 0; i < s.startMs.length; i++) if (now >= s.startMs[i]) idx = i;
        boolean speaking = idx >= 0 && now < s.startMs[idx] + s.durMs[idx];

        drawHeader(g, font, x, y, w, now, a, speaking);

        int tx = x + 16, ty = y + 27;
        if (idx < 0) {
            int dots = (int) ((now / 350L) % 4);
            String wait = Component.translatable("maniacmod.evac.dispatcher.connecting").getString()
                + ".".repeat(dots);
            g.drawString(font, wait, tx, ty, GeneratorFuelUiTheme.alpha(DIM, a), false);
        } else {
            drawTyped(g, font, s, idx, tx, ty, textW, now, a);
            drawProgress(g, s, idx, x, y, w, h, now, a);
        }
        drawPips(g, s, idx, x + w - 12 - s.text.length * 7, y + h - 9, a);
    }

    // ── Частини ──────────────────────────────────────────────────────────

    private static void drawHeader(GuiGraphics g, Font font, int x, int y, int w, long now, float a, boolean speaking) {
        // Іконка рації: корпус, динамік, антена.
        int ix = x + 12, iy = y + 7;
        g.fill(ix, iy + 2, ix + 13, iy + 12, GeneratorFuelUiTheme.alpha(CASE, a));
        g.fill(ix, iy + 2, ix + 13, iy + 3, GeneratorFuelUiTheme.alpha(0xFF59604F, a));
        g.fill(ix + 2, iy + 5, ix + 7, iy + 10, GeneratorFuelUiTheme.alpha(CASE_DARK, a));
        g.fill(ix + 9, iy + 5, ix + 11, iy + 7, GeneratorFuelUiTheme.alpha(speaking ? AMBER : 0xFF59604F, a));
        g.fill(ix + 10, iy - 3, ix + 11, iy + 2, GeneratorFuelUiTheme.alpha(0xFFC2C9D6, a));

        g.drawString(font, Component.translatable("maniacmod.evac.dispatcher.name"), x + 32, y + 6,
            GeneratorFuelUiTheme.alpha(WHITE, a), false);
        g.drawString(font, Component.translatable("maniacmod.evac.dispatcher.channel"),
            x + 32 + font.width(Component.translatable("maniacmod.evac.dispatcher.name")) + 8, y + 6,
            GeneratorFuelUiTheme.alpha(DIM, a), false);

        // Еквалайзер: пульсує, поки диспетчер говорить; у паузах ледь тремтить.
        float level = speaking ? 1f : 0.15f;
        int ex = x + w - 40;
        for (int i = 0; i < 5; i++) {
            float wave = 0.5f + 0.5f * Mth.sin(now / (70f + i * 23f) + i * 1.7f);
            int bh = 2 + Math.round(level * 9f * wave);
            g.fill(ex + i * 6, y + 17 - bh, ex + i * 6 + 4, y + 17, GeneratorFuelUiTheme.alpha(AMBER, a * (speaking ? 1f : 0.5f)));
        }
        // «ЕФІР» з червоною крапкою, що блимає.
        Component onAir = Component.translatable("maniacmod.evac.dispatcher.onair");
        int ow = font.width(onAir);
        int ox = ex - 10 - ow;
        g.drawString(font, onAir, ox, y + 6, GeneratorFuelUiTheme.alpha(DIM, a), false);
        boolean blink = (now / 450L) % 2 == 0;
        g.fill(ox - 7, y + 8, ox - 3, y + 12, GeneratorFuelUiTheme.alpha(blink ? RED : 0xFF4A2220, a));

        g.fill(x + 8, y + 21, x + w - 8, y + 22, GeneratorFuelUiTheme.alpha(TRACK, a));
    }

    /** Друкує репліку; нові літери бурштинові, збій сигналу на початку, каретка блимає. */
    private static void drawTyped(GuiGraphics g, Font font, ClientDispatcher.Session s, int idx,
                                  int tx, int ty, int textW, long now, float a) {
        long since = now - s.startMs[idx];
        float p = UiAnim.clamp01(since / (s.durMs[idx] * TYPE_FRACTION));
        List<String> lines = wrap(font, s.text[idx], textW);

        // Скільки символів показати: накопичені «ваги» (паузи на розділових знаках).
        float total = 0f;
        for (String ln : lines) for (int i = 0; i < ln.length(); i++) total += weight(ln.charAt(i));
        float target = p * total, acc = 0f;
        int shown = 0;
        outer:
        for (String ln : lines) {
            for (int i = 0; i < ln.length(); i++) {
                float wgt = weight(ln.charAt(i));
                if (acc + wgt > target) break outer;
                acc += wgt;
                shown++;
            }
        }

        // Збій сигналу на початку репліки: тремтіння по X і біла смужка.
        boolean glitch = since < 220L;
        int jitter = glitch ? (int) (((now / 33L) * 2654435761L >>> 28) & 3L) - 1 : 0;

        int remaining = shown, caretX = tx, caretY = ty;
        for (int li = 0; li < lines.size() && li < 4; li++) {
            String ln = lines.get(li);
            int n = Math.min(remaining, ln.length());
            int ly = ty + li * 11;
            int fresh = Math.min(FRESH_CHARS, n);
            int stable = n - fresh;
            int cx = tx + jitter;
            if (stable > 0) {
                String head = ln.substring(0, stable);
                g.drawString(font, head, cx, ly, GeneratorFuelUiTheme.alpha(WHITE, a), false);
                cx += font.width(head);
            }
            for (int k = 0; k < fresh; k++) {
                // k = 0 — найстаріша з «свіжих» (майже біла), остання — найяскравіше бурштинова.
                float heat = (k + 1) / (float) (FRESH_CHARS + 1);
                int col = GeneratorFuelUiTheme.mix(WHITE, AMBER, heat);
                String ch = String.valueOf(ln.charAt(stable + k));
                g.drawString(font, ch, cx, ly, GeneratorFuelUiTheme.alpha(col, a), false);
                cx += font.width(ch);
            }
            if (remaining > 0) { caretX = cx; caretY = ly; }
            remaining -= ln.length();
            if (remaining < 0) break;
        }
        // Каретка блимає, поки репліка звучить.
        if (now < s.startMs[idx] + s.durMs[idx] && (now / 260L) % 2 == 0) {
            g.fill(caretX + 1, caretY - 1, caretX + 2, caretY + 9, GeneratorFuelUiTheme.alpha(AMBER, a));
        }
        if (glitch) {
            int gy = ty + (int) ((since / 12L) % (Math.max(1, lines.size()) * 11L));
            g.fill(tx - 4, gy, tx + textW, gy + 1, GeneratorFuelUiTheme.alpha(WHITE, a * 0.35f));
        }
    }

    /** Тонка шкала поточної репліки по нижньому краю. */
    private static void drawProgress(GuiGraphics g, ClientDispatcher.Session s, int idx,
                                     int x, int y, int w, int h, long now, float a) {
        float f = UiAnim.clamp01((now - s.startMs[idx]) / (float) s.durMs[idx]);
        int bx = x + 14, bw = w - 28 - s.text.length * 7 - 8, by = y + h - 7;
        g.fill(bx, by, bx + bw, by + 2, GeneratorFuelUiTheme.alpha(TRACK, a));
        g.fill(bx, by, bx + Math.round(bw * f), by + 2, GeneratorFuelUiTheme.alpha(AMBER, a));
    }

    /** Крапки «репліка N із M». */
    private static void drawPips(GuiGraphics g, ClientDispatcher.Session s, int idx, int px, int py, float a) {
        for (int i = 0; i < s.text.length; i++) {
            int col = i < idx ? AMBER : i == idx ? WHITE : TRACK;
            g.fill(px + i * 7, py, px + i * 7 + 4, py + 4, GeneratorFuelUiTheme.alpha(col, a));
        }
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    /** Скільки «часу» займає символ: пробіл майже миттєвий, кома — пауза, крапка — довша. */
    private static float weight(char c) {
        return switch (c) {
            case ' ' -> 0.6f;
            case ',', '—', '–' -> 5f;
            case ';', ':' -> 6f;
            case '.', '!', '?', '…' -> 9f;
            default -> 1f;
        };
    }

    private static List<String> wrap(Font font, String text, int width) {
        List<String> out = new ArrayList<>();
        for (FormattedText part : font.getSplitter().splitLines(text, width, Style.EMPTY)) out.add(part.getString());
        if (out.isEmpty()) out.add("");
        return out;
    }
}

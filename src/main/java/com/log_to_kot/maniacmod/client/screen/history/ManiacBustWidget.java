package com.log_to_kot.maniacmod.client.screen.history;

import com.log_to_kot.maniacmod.client.overlay.roster.ProfileModels;
import com.log_to_kot.maniacmod.client.style.UiAnim;
import com.log_to_kot.maniacmod.client.style.UiFx;
import com.log_to_kot.maniacmod.client.style.ManiacUiTheme;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Віджет «бюст маніяка»: 3D-модель по пояс — голова, плечі, торс; ноги ніколи не видно.
 *
 * <h3>Кадрування під КОЖНОГО маніяка</h3>
 * Нічого не підбирається вручну під конкретний персонаж: рамка рахується з габаритів архетипу
 * ({@code hitboxHeight}, {@code eyeHeight}). Нижня межа кадру — на {@link #BUST_BELOW_EYES} зростів нижче
 * очей, верхня — трохи вище маківки. Низький, високий, масивний — всі лягають у ту саму рамку, а новий
 * маніяк у реєстрі виглядає правильно сам. Те, що опинилось нижче межі, ховає виріз по краю віджета й
 * градієнтний «туман» знизу.
 *
 * <h3>Живість</h3>
 * Модель повільно погойдується й повертається за курсором (плавно); при зміні маніяка — коротка
 * «спалах-завіса» і виїзд знизу; зверху пробігає промінь світла; позаду — пульсуюче сяйво кольору результату.
 * Стану, крім анімації, віджет не має: що малювати, вирішує екран.
 *
 * <p>Якщо 3D-в'юпорт вимкнено після збою рендера ({@link ProfileModels#maniacAvailable()}), або
 * архетипу вже нема в реєстрі, малюється плоский силует із питанням — екран лишається робочим.</p>
 */
@OnlyIn(Dist.CLIENT)
final class ManiacBustWidget {

    /** Скільки зростів нижче очей лишається в кадрі (≈ до пояса). */
    static final float BUST_BELOW_EYES = 0.52f;
    /** Запас над маківкою, у частках зросту. */
    private static final float HEADROOM = 0.07f;
    private static final long SWAP_MS = 520L;

    private String shownId = null;
    private long swapAt;
    private float yaw;
    private final long[] clock = {0L};

    /**
     * @param archetype  маніяк; {@code null} — невідомий (силует)
     * @param id         id для визначення «змінився»; порожній рядок — теж валідне значення
     * @param glow       колір сяйва за моделлю
     */
    void render(GuiGraphics g, Font font, int x, int y, int w, int h, ManiacArchetype archetype, String id,
                int glow, long now, int mx, int my, float k) {
        float dt = UiAnim.frameDt(clock);
        if (shownId == null || !shownId.equals(id)) {
            if (shownId != null) swapAt = now;
            shownId = id;
        }
        float swap = UiAnim.clamp01((now - swapAt) / (float) SWAP_MS);
        float in = swapAt == 0L ? 1f : UiAnim.easeOutCubic(swap);

        // Фон: темна колба + сяйво.
        g.fill(x, y, x + w, y + h, HistoryUi.a(ManiacUiTheme.PANEL_BG, 1f));
        float pulse = 0.65f + 0.35f * (float) Math.sin(now / 900.0);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + Math.round(h * 0.75f),
            HistoryUi.a(glow, 0.0f), HistoryUi.a(glow, 0.20f * pulse));
        // Світлова пляма за головою.
        int cx = x + w / 2;
        int spot = Math.round(Math.min(w, h) * 0.42f);
        for (int r = spot; r > 0; r -= 3) {
            float f = 1f - r / (float) spot;
            g.fill(cx - r, y + Math.round(h * 0.18f) - r / 2, cx + r, y + Math.round(h * 0.18f) + r / 2,
                HistoryUi.a(glow, 0.012f + 0.02f * f * pulse));
        }

        // Погляд за курсором + повільне погойдування.
        float cursor = UiAnim.clamp01((mx - cx) / (w * 1.2f) + 0.5f) * 2f - 1f;
        float sway = (float) Math.sin(now / 2100.0) * 7f;
        yaw = UiAnim.approach(yaw, cursor * 26f + sway, 6f, dt);

        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        boolean drawn = false;
        if (archetype != null && ProfileModels.maniacAvailable()) {
            float height = Math.max(1f, archetype.declaredHitboxHeight());
            float eye = Math.max(0.5f * height, archetype.declaredEyeHeight());
            float bottom = Math.max(0f, eye - BUST_BELOW_EYES * height);
            float top = height * (1f + HEADROOM);
            float scale = (h * 0.94f) / (top - bottom);
            // baseY — Y ніг у пікселях: нижня межа кадру (bottom) стоїть на нижньому краї віджета.
            float baseY = y + h + bottom * scale + (1f - in) * 34f * k;
            drawn = ProfileModels.drawManiac(g, archetype, cx, baseY, scale, 180f + yaw);
        }
        if (!drawn) drawSilhouette(g, font, cx, y + h / 2, Math.min(w, h), glow, now);
        g.disableScissor();

        // «Туман» знизу ховає край зрізу й підводить до плашки з іменем.
        int fogH = Math.round(h * 0.34f);
        g.fillGradient(x + 1, y + h - fogH, x + w - 1, y + h - 1, 0x00000000, HistoryUi.a(0xFF050607, 0.92f));
        // Промінь, що пробігає зліва направо.
        float beam = (now % 5200L) / 5200f;
        UiFx.scanBeam(g, x + 1, y + 1, w - 2, h - 2, beam, Math.max(6, Math.round(14 * k)), glow, 38);

        // Завіса при зміні маніяка.
        if (swap < 1f) {
            g.fill(x, y, x + w, y + h, HistoryUi.a(0xFF020304, 0.92f * (1f - swap) * (1f - swap)));
        }

        ManiacUiTheme.border1px(g, x, y, w, h, HistoryUi.a(ManiacUiTheme.BORDER, 1f));
        UiFx.brackets(g, x, y, w, h, HistoryUi.a(ManiacUiTheme.CORNER_ACCENT, 0.9f), Math.round(10 * k), 0f, 0);
    }

    private static void drawSilhouette(GuiGraphics g, Font font, int cx, int cy, int size, int glow, long now) {
        int head = Math.max(8, size / 6);
        int shoulders = head * 3;
        int a = Math.round(255 * (0.55f + 0.1f * (float) Math.sin(now / 700.0)));
        int col = (a << 24) | 0x0B0D0E;
        g.fill(cx - head, cy - head * 2, cx + head, cy, col);
        g.fill(cx - shoulders, cy + head / 2, cx + shoulders, cy + head * 4, col);
        HistoryUi.text(g, font, "?", cx, cy - head, Math.max(1f, head / 5f), HistoryUi.a(glow, 0.8f), 1);
    }
}

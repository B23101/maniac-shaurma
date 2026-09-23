package com.log_to_kot.maniacmod.client.overlay.notify;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Малює одну п'ятикутну зірочку (2D, GUI-простір) заповненими
 * трикутниками — {@code graphics.fill} по горизонтальних смугах, той
 * самий прийом, що {@code DownedSurvivorMarker#drawDiamond} для ромба.
 *
 * ── Навіщо процедурно, а не текстурою ──────────────────────────────────
 * Дизайн від користувача каже "зірочки це текстура" — очікується
 * растровий спрайт. На момент написання коду готової текстури НЕМАЄ
 * (ні в проєкті, ні наданої користувачем), а чекати на неї означало б
 * лишити весь стан "оглушений маньяк" без візуального індикатора.
 * Це тимчасова заміна: контур і пропорції п'ятикутної зірки, той самий
 * розмір/API, що майбутній {@code blit(...)} з текстурою. Якщо
 * текстура з'явиться — {@link ManiacStunOverlay} і
 * {@link ManiacStunWorldMarker} міняють виклик {@link #draw} на
 * {@code graphics.blit(TEXTURE, x, y, 0, 0, size, size, size, size)},
 * решта коду (позиціювання по колу, обертання, підрахунок кута) не
 * зміниться.
 *
 * ── Геометрія ──────────────────────────────────────────────────────────
 * П'ять вершин на зовнішньому колі радіуса {@code outerRadius}, п'ять
 * западин на внутрішньому колі радіуса {@code outerRadius * 0.38}
 * (стандартна пропорція для "гострої" п'ятикутної зірки), чергуються
 * через 36°, перша вершина — під кутом {@code rotationDeg} від
 * вертикалі. Заповнення — скан-лініями зліва направо, найпростіший
 * спосіб намалювати опуклий/зірчастий полігон через самі {@code fill}
 * без завантаження шейдера чи буфера вершин.
 */
@OnlyIn(Dist.CLIENT)
final class StarShape {

    private StarShape() {}

    /**
     * @param cx           центр зірочки, X (GUI-координати)
     * @param cy           центр зірочки, Y
     * @param outerRadius  радіус до кінчиків променів, px
     * @param rotationDeg  поворот усієй фігури, градуси (0 = один
     *                     промінь точно вгору); анімація обертання —
     *                     це просто зміна цього значення кадр у кадр
     * @param argb         колір із альфою, той самий формат, що
     *                     {@code GuiGraphics.fill} (0xAARRGGBB)
     */
    static void draw(GuiGraphics graphics, float cx, float cy, float outerRadius, float rotationDeg, int argb) {
        float innerRadius = outerRadius * 0.38f;
        float[] xs = new float[10];
        float[] ys = new float[10];
        for (int i = 0; i < 10; i++) {
            float radius = (i % 2 == 0) ? outerRadius : innerRadius;
            double angle = Math.toRadians(rotationDeg + i * 36.0 - 90.0);
            xs[i] = cx + (float) (radius * Math.cos(angle));
            ys[i] = cy + (float) (radius * Math.sin(angle));
        }

        float minY = ys[0], maxY = ys[0];
        for (float y : ys) {
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        // Скан-лінії: для кожного цілого Y у діапазоні фігури знаходимо
        // всі перетини контуру з горизонталлю, сортуємо по X і
        // зафарбовуємо пари (непарний-правило, стандартний "even-odd
        // fill" для довільного, навіть неопуклого, замкненого полігона —
        // зірка якраз неопукла).
        for (int y = (int) Math.floor(minY); y <= (int) Math.ceil(maxY); y++) {
            float scanY = y + 0.5f;
            float[] crossings = new float[10];
            int count = 0;
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10;
                float y1 = ys[i], y2 = ys[j];
                if ((y1 <= scanY && y2 > scanY) || (y2 <= scanY && y1 > scanY)) {
                    float t = (scanY - y1) / (y2 - y1);
                    crossings[count++] = xs[i] + t * (xs[j] - xs[i]);
                }
            }
            java.util.Arrays.sort(crossings, 0, count);
            for (int i = 0; i + 1 < count; i += 2) {
                int xStart = Math.round(crossings[i]);
                int xEnd = Math.round(crossings[i + 1]);
                if (xEnd > xStart) graphics.fill(xStart, y, xEnd, y + 1, argb);
            }
        }
    }
}

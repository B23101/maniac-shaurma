package com.log_to_kot.maniacmod.client.anim;

/**
 * Де опиняються ОЧІ гравця, коли анімація рухає голову, — чиста математика.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Камера від першої особи стоїть в очах. Без анімації очі завжди на
 * {@link #STANDING_EYE_HEIGHT} над ногами. Але коли гравець лежить і
 * підводиться, художник у Blockbench рухає ГОЛОВУ (позиція + поворот),
 * і камера мусить іти за нею — інакше модель лежить, а картинка
 * дивиться з висоти в 1.62 блока.
 *
 * ── Модель голови ────────────────────────────────────────────────────
 * У ванільній {@code PlayerModel} голова і тулуб — СУСІДНІ кістки, обидві
 * діти кореня (голова НЕ дитина тулуба). Тому поворот тулуба голову не
 * зсуває: положення голови задають лише її власні ключі
 * {@code head.position} і {@code head.rotation}. Саме їх і читаємо.
 *
 * ── Простір моделі (ModelPart) ───────────────────────────────────────
 *   +x — ЛІВОРУЧ від гравця (права рука стоїть на x = -5);
 *   +y — ВНИЗ;
 *   -z — ВПЕРЕД (обличчя на боці -z, руки з xRot = -90° тягнуться в -z).
 * Одиниця — піксель моделі. Рендерер малює модель зменшеною до
 * {@link #MODEL_SCALE}, тож 1 піксель = MODEL_SCALE/16 блока.
 *
 * Порядок обертання ModelPart — {@code translateAndRotate}: спершу
 * зсув, потім Z, Y, X через {@code PoseStack.mulPose}. Матриця виходить
 * {@code T·Rz·Ry·Rx}, а до точки застосовується справа наліво: ПЕРШИМ
 * X, потім Y, потім Z.
 *
 * ── Чому зсув відносно стоячої пози ──────────────────────────────────
 * Повертаємо не абсолютну висоту, а ЗМІЩЕННЯ ока відносно стоячої пози
 * (блоки). Без анімації (нульові ключі голови) зсув рівно 0 і камера
 * лишається ванільною.
 */
public final class HeadKinematics {

    private HeadKinematics() {}

    /** Ванільна висота очей стоячого гравця, блоки. */
    public static final float STANDING_EYE_HEIGHT = 1.62f;

    /** Масштаб, з яким рендерер малює модель гравця ({@code PlayerRenderer.scale}). */
    public static final float MODEL_SCALE = 0.9375f;

    /**
     * Висота півота голови над ногами, блоки: рендерер піднімає початок
     * моделі на 1.501 УЖЕ після масштабування.
     */
    public static final float PIVOT_HEIGHT = 1.501f * MODEL_SCALE;

    /** Блоків в одному пікселі моделі. */
    public static final float BLOCKS_PER_PX = MODEL_SCALE / 16f;

    /**
     * Ока відносно півота голови, пікселі; від'ємне = ВИЩЕ півота.
     * Камера стоїть на 1.62, півот на {@link #PIVOT_HEIGHT}.
     */
    public static final float EYE_LOCAL_Y = -(STANDING_EYE_HEIGHT - PIVOT_HEIGHT) / BLOCKS_PER_PX;

    /** Зсув ока відносно стоячої пози, блоки, у осях гравця. */
    public record Offset(float right, float up, float forward) {
        public static final Offset ZERO = new Offset(0f, 0f, 0f);
    }

    /**
     * @param headPosPx  {@code head.position} із кліпу, пікселі моделі
     * @param headRotDeg {@code head.rotation} із кліпу, градуси (x, y, z)
     */
    public static Offset eyeOffset(float[] headPosPx, float[] headRotDeg) {
        double ex = 0.0, ey = EYE_LOCAL_Y, ez = 0.0;

        double rx = Math.toRadians(headRotDeg[0]);
        double ry = Math.toRadians(headRotDeg[1]);
        double rz = Math.toRadians(headRotDeg[2]);

        // X першим
        double x1 = ex;
        double y1 = ey * Math.cos(rx) - ez * Math.sin(rx);
        double z1 = ey * Math.sin(rx) + ez * Math.cos(rx);
        // потім Y
        double x2 = x1 * Math.cos(ry) + z1 * Math.sin(ry);
        double y2 = y1;
        double z2 = -x1 * Math.sin(ry) + z1 * Math.cos(ry);
        // потім Z
        double x3 = x2 * Math.cos(rz) - y2 * Math.sin(rz);
        double y3 = x2 * Math.sin(rz) + y2 * Math.cos(rz);
        double z3 = z2;

        double dx = x3 + headPosPx[0] - ex;
        double dy = y3 + headPosPx[1] - ey;
        double dz = z3 + headPosPx[2] - ez;

        // +x моделі = ліворуч → праворуч мінус; +y вниз → вгору мінус;
        // -z = вперед → вперед мінус.
        return new Offset((float) (-dx * BLOCKS_PER_PX),
                          (float) (-dy * BLOCKS_PER_PX),
                          (float) (-dz * BLOCKS_PER_PX));
    }
}

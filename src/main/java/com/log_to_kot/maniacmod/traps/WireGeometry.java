package com.log_to_kot.maniacmod.traps;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Геометрія електричного дроту: усе — через ВІДРІЗОК і ПРОМІНЬ, а не через
 * AABB-рамку навколо дроту.
 *
 * ── Чому не хітбокс ──────────────────────────────────────────────────
 * AABB відрізка на діагоналі — це великий прямокутник, більша частина
 * якого порожня: гравець у його куті «торкався» б дроту, а приціл ножиць
 * ловив би повітря. Тому:
 *   • «наступив» = відрізок дроту перетинає справжній AABB гравця
 *     ({@link #segmentTouchesBox});
 *   • «приціл ножицями» = найближче зближення променя погляду з
 *     відрізком ({@link #rayHitDistance}).
 */
public final class WireGeometry {

    private WireGeometry() {}

    /** Радіус самого дроту для перетину з гравцем (дріт тонкий). */
    public static final double WIRE_RADIUS = 0.06;

    /** Чи відрізок a→b перетинає (трохи роздутий на товщину дроту) AABB. */
    public static boolean segmentTouchesBox(Vec3 a, Vec3 b, AABB box) {
        AABB inflated = box.inflate(WIRE_RADIUS);
        return inflated.contains(a) || inflated.contains(b) || inflated.clip(a, b).isPresent();
    }

    /** Квадрат відстані від точки до відрізка a→b. */
    public static double distanceToSegmentSqr(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double lengthSqr = ab.lengthSqr();
        if (lengthSqr < 1e-9) return p.distanceToSqr(a);
        double t = p.subtract(a).dot(ab) / lengthSqr;
        t = Math.max(0.0, Math.min(1.0, t));
        return p.distanceToSqr(a.add(ab.scale(t)));
    }

    /**
     * Наскільки далеко ВЗДОВЖ променя він найближче підходить до відрізка
     * a→b — і чи підходить достатньо близько.
     *
     * @param origin  початок променя (очі)
     * @param dir     НОРМАЛІЗОВАНИЙ напрямок погляду
     * @param maxLen  довжина променя
     * @param radius  на скільки близько промінь має пройти від дроту
     * @return відстань від {@code origin} до точки найближчого зближення
     *         або {@link Double#NaN}, якщо промінь дріт не «зачепив»
     */
    public static double rayHitDistance(Vec3 origin, Vec3 dir, double maxLen,
                                        Vec3 a, Vec3 b, double radius) {
        Vec3 d1 = dir.scale(maxLen);     // промінь як відрізок origin → origin+d1
        Vec3 d2 = b.subtract(a);         // дріт
        Vec3 r = origin.subtract(a);
        double aa = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        final double eps = 1e-9;

        double s;   // параметр на промені (0..1)
        double t;   // параметр на дроті (0..1)
        if (aa <= eps) return Double.NaN;
        double c = d1.dot(r);
        if (e <= eps) {
            t = 0.0;
            s = clamp01(-c / aa);
        } else {
            double bb = d1.dot(d2);
            double denom = aa * e - bb * bb;
            s = denom > eps ? clamp01((bb * f - c * e) / denom) : 0.0;
            t = (bb * s + f) / e;
            if (t < 0.0) {
                t = 0.0;
                s = clamp01(-c / aa);
            } else if (t > 1.0) {
                t = 1.0;
                s = clamp01((bb - c) / aa);
            }
        }

        Vec3 onRay = origin.add(d1.scale(s));
        Vec3 onWire = a.add(d2.scale(t));
        if (onRay.distanceToSqr(onWire) > radius * radius) return Double.NaN;
        return s * maxLen;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

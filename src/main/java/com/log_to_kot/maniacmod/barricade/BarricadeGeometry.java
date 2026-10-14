package com.log_to_kot.maniacmod.barricade;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Геометрія дошки: розміри, локальна система координат, промінь і точки колізії.
 *
 * <h3>Чому розміри тут, а не в ConfigSchema</h3>
 * Ширина/товщина дошки — частина ВИГЛЯДУ, який клієнт малює сам. Конфіг сервера клієнт не читає,
 * тож змінюваний «в yml» розмір розійшовся б між тим, що бачить гравець, і тим, що рахує сервер.
 * Усе, що адмін справді налаштовує (довжина, кількість, знос лома) — у {@code ConfigSchema}.
 *
 * <h3>Система координат дошки</h3>
 * {@code dir} — вздовж дошки (a→b), {@code n} — нормаль широкої грані, {@code u = dir × n} —
 * впоперек (по ширині). Бокс-орієнтований (OBB): діагональна дошка не перетворюється на «великий
 * прямокутник» AABB, а промінь/приціл рахуються точно (той самий підхід, що {@code WireGeometry}).
 */
public final class BarricadeGeometry {

    private BarricadeGeometry() {}

    /** Ширина дошки (блоки). */
    public static final double WIDTH = 0.30;
    /** Товщина дошки (блоки). */
    public static final double THICKNESS = 0.07;
    /** Дошка трохи довша за відрізок a→b з кожного боку — щоб кінці «входили» у поверхню, а не висіли. */
    public static final double END_PAD = 0.04;
    /** Зсув точки від поверхні, на яку клікнули, щоб дошка не мерехтіла (z-fighting) з блоком. */
    public static final double SURFACE_OFFSET = 0.02;
    /** Найкоротша дозволена дошка. */
    public static final double MIN_LENGTH = 0.35;

    /** Розмір кубика-колайдера, яким апроксимується дошка (див. BarricadeColliderEntity). */
    public static final double COLLIDER_SIZE = 0.4;
    /** Крок між кубиками уздовж дошки: менший за розмір → вони перекриваються й щілин немає. */
    public static final double COLLIDER_STEP = 0.3;

    /** Запас для променя (приціл по тонкій дошці без нього майже неможливий). */
    private static final double RAY_PAD = 0.04;

    /** Локальний базис дошки. */
    public record Frame(Vec3 center, Vec3 dir, Vec3 u, Vec3 n,
                        double halfLength, double halfWidth, double halfThickness) {

        public Vec3 corner(int sx, int sy, int sz) {
            return center
                .add(dir.scale(sx * halfLength))
                .add(u.scale(sy * halfWidth))
                .add(n.scale(sz * halfThickness));
        }
    }

    /** Базис дошки; {@code null}, якщо вона вироджена (a == b). */
    public static Frame frame(BoardSpec spec) {
        Vec3 ab = spec.b().subtract(spec.a());
        double length = ab.length();
        if (length < 1e-6) return null;
        Vec3 dir = ab.scale(1.0 / length);
        Vec3 n = orthogonal(spec.normal(), dir);
        if (n == null) n = fallbackNormal(dir);
        Vec3 u = dir.cross(n).normalize();
        return new Frame(spec.a().add(spec.b()).scale(0.5), dir, u, n,
            length / 2.0 + END_PAD, WIDTH / 2.0, THICKNESS / 2.0);
    }

    public static double length(BoardSpec spec) {
        return spec.a().distanceTo(spec.b());
    }

    /**
     * Нормаль широкої грані для дошки a→b, що її ставить гравець: дошка «дивиться» на того, хто її
     * ставить — це те, що він бачить, коли прикидає, як зашити прохід. Для підлоги (дивимось
     * зверху) це «вгору», для стіни — від стіни до гравця, для дверного прорізу між косяками —
     * у бік гравця, а не вздовж косяків.
     */
    public static Vec3 faceNormal(Vec3 a, Vec3 b, Vec3 viewerEye) {
        Vec3 ab = b.subtract(a);
        double length = ab.length();
        if (length < 1e-6) return new Vec3(0, 1, 0);
        Vec3 dir = ab.scale(1.0 / length);
        Vec3 toViewer = viewerEye.subtract(a.add(b).scale(0.5));
        Vec3 n = orthogonal(toViewer, dir);
        return n != null ? n : fallbackNormal(dir);
    }

    private static Vec3 orthogonal(Vec3 v, Vec3 dir) {
        Vec3 n = v.subtract(dir.scale(v.dot(dir)));
        double len = n.length();
        return len < 1e-3 ? null : n.scale(1.0 / len);
    }

    private static Vec3 fallbackNormal(Vec3 dir) {
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 n = orthogonal(up, dir);
        return n != null ? n : new Vec3(1, 0, 0);
    }

    /** Охоплюючий AABB — лише для відсікання рендеру й грубого відбору, не для колізій. */
    public static AABB bounds(BoardSpec spec) {
        Frame f = frame(spec);
        if (f == null) return new AABB(spec.a(), spec.b()).inflate(0.2);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vec3 c = f.corner(sx, sy, sz);
                    minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
                    minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
                    minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(0.05);
    }

    public static AABB bounds(List<BoardSpec> boards) {
        AABB total = null;
        for (BoardSpec board : boards) {
            AABB box = bounds(board);
            total = total == null ? box : total.minmax(box);
        }
        return total == null ? new AABB(0, 0, 0, 1, 1, 1) : total;
    }

    public static Vec3 centroid(List<BoardSpec> boards) {
        if (boards.isEmpty()) return Vec3.ZERO;
        Vec3 sum = Vec3.ZERO;
        for (BoardSpec board : boards) sum = sum.add(board.a()).add(board.b());
        return sum.scale(1.0 / (boards.size() * 2.0));
    }

    /**
     * Відстань уздовж променя до дошки (промінь × орієнтований бокс), або {@code -1}, якщо
     * промінь не влучає або влучає далі за {@code maxLen}.
     *
     * @param dir НОРМАЛІЗОВАНИЙ напрямок погляду
     */
    public static double rayDistance(BoardSpec spec, Vec3 origin, Vec3 dir, double maxLen) {
        Frame f = frame(spec);
        if (f == null) return -1;
        Vec3 rel = origin.subtract(f.center());
        double[] o = { rel.dot(f.dir()), rel.dot(f.u()), rel.dot(f.n()) };
        double[] d = { dir.dot(f.dir()), dir.dot(f.u()), dir.dot(f.n()) };
        double[] h = { f.halfLength() + RAY_PAD, f.halfWidth() + RAY_PAD, f.halfThickness() + RAY_PAD };

        double tMin = 0.0;
        double tMax = maxLen;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < -h[i] || o[i] > h[i]) return -1;
            } else {
                double t1 = (-h[i] - o[i]) / d[i];
                double t2 = (h[i] - o[i]) / d[i];
                if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) return -1;
            }
        }
        return tMin;
    }

    /** Центри кубиків-колайдерів уздовж дошки (кінці включно). */
    public static List<Vec3> colliderCenters(BoardSpec spec) {
        List<Vec3> centers = new ArrayList<>();
        double length = length(spec);
        if (length < 1e-6) {
            centers.add(spec.a());
            return centers;
        }
        int steps = Math.max(1, (int) Math.ceil(length / COLLIDER_STEP));
        Vec3 ab = spec.b().subtract(spec.a());
        for (int i = 0; i <= steps; i++) {
            centers.add(spec.a().add(ab.scale((double) i / steps)));
        }
        return centers;
    }
}

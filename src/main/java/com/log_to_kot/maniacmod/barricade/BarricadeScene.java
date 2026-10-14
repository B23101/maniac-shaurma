package com.log_to_kot.maniacmod.barricade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Плоска «сцена» барикади для міні-гри «витягни цвяхи»: ті самі дошки, що стоять у світі, але
 * так, як їх бачить гравець, який стоїть перед ними (вигляд спереду, вісь Y — вгору, одиниці —
 * блоки). Чистa математика без клієнтських класів — тому її можна використати і на сервері
 * (скільки дощок/цвяхів), і на клієнті (малювання).
 *
 * <h3>Звідки беруться дошки</h3>
 * <ul>
 *   <li><b>Гнучка барикада</b> — {@link BoardSpec} один-в-один: проєкція a→b на площину, куди
 *       «дивляться» дошки; довжина, ширина ({@link BarricadeGeometry#WIDTH}), поворот і блок
 *       текстури ті самі, що малює {@code PlankRenderer} у світі.</li>
 *   <li><b>Фіксована барикада</b> — елементи моделей {@code boards_lower/boards_upper.json}
 *       (ті самі файли, що й блок у світі): розміри, поворот і {@code rescale} читаються з JSON,
 *       тому якщо модель змінять — міні-гра зміниться разом із нею.</li>
 * </ul>
 *
 * <h3>Цвяхи</h3>
 * На кожну дошку рівно 2 цвяхи — по одному на кінець (для гнучкої — у точках a і b, куди дошку
 * «прибито» до поверхні). Індекс цвяха = {@code дошка * 2 + кінець}; саме він іде в мережу.
 */
public final class BarricadeScene {

    public static final int NAILS_PER_PLANK = 2;

    /** Відступ цвяха від торця дошки у фіксованій барикаді (2 px моделі). */
    private static final double FIXED_NAIL_INSET = 2.0 / 16.0;
    /** Запас навколо дошок, що утворює «дірку» гнучкої барикади. */
    private static final double HOLE_MARGIN = 0.16;
    /** Найменша видима ширина дошки, яку видно ребром (щоб її взагалі можна було вхопити). */
    private static final double MIN_APPARENT_WIDTH = BarricadeGeometry.WIDTH * 0.45;
    private static final double MIN_APPARENT_LENGTH = 0.32;

    private static final String LOWER_MODEL = "/assets/maniacmod/models/block/boards_lower.json";
    private static final String UPPER_MODEL = "/assets/maniacmod/models/block/boards_upper.json";

    /**
     * Одна дошка на сцені.
     *
     * @param cx,cy        центр (блоки)
     * @param length       довжина вздовж осі (блоки)
     * @param width        ширина впоперек (блоки)
     * @param angle        кут осі дошки, рад, проти годинникової, Y вгору: кінець 1 = центр + dir*length/2
     * @param depth        «ближче до глядача» = більше; для порядку малювання
     * @param block        блок, текстура якого на дошці
     * @param grainOffset  зсув текстури по ширині (частка спрайта) — як у {@code PlankRenderer}
     * @param grainAcross  текстура лягає ПОПЕРЕК довжини (діагональні планки у фіксованій барикаді)
     */
    public record Plank(double cx, double cy, double length, double width, double angle,
                        double depth, ResourceLocation block, double grainOffset, boolean grainAcross) {

        public double dirX() { return Math.cos(angle); }

        public double dirY() { return Math.sin(angle); }
    }

    /** Цвях: {@code index() = plank * 2 + end}. */
    public record Nail(int plank, int end, double x, double y) {
        public int index() { return plank * NAILS_PER_PLANK + end; }
    }

    private final boolean fixed;
    private final List<Plank> planks;
    private final List<Nail> nails;
    private final List<double[]> hole;
    private final int[] drawOrder;
    private final double minX, minY, maxX, maxY;

    private BarricadeScene(boolean fixed, List<Plank> planks, List<Nail> nails, List<double[]> hole) {
        this.fixed = fixed;
        this.planks = List.copyOf(planks);
        this.nails = List.copyOf(nails);
        this.hole = List.copyOf(hole);
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (double[] p : this.hole) {
            x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]);
            y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
        }
        for (Plank plank : this.planks) {
            for (double[] c : corners(plank)) {
                x0 = Math.min(x0, c[0]); x1 = Math.max(x1, c[0]);
                y0 = Math.min(y0, c[1]); y1 = Math.max(y1, c[1]);
            }
        }
        this.minX = x0; this.minY = y0; this.maxX = x1; this.maxY = y1;

        Integer[] order = new Integer[this.planks.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> this.planks.get(i).depth()));
        this.drawOrder = new int[order.length];
        for (int i = 0; i < order.length; i++) this.drawOrder[i] = order[i];
    }

    public boolean isFixed() { return fixed; }
    public List<Plank> planks() { return planks; }
    public List<Nail> nails() { return nails; }
    /** Контур «дірки» (опуклий багатокутник проти годинникової), блоки. */
    public List<double[]> hole() { return hole; }
    /** Індекси дощок у порядку малювання: найдальші від глядача — першими (стабільне сортування). */
    public int[] drawOrder() { return drawOrder.clone(); }
    public int plankCount() { return planks.size(); }
    public int nailCount() { return nails.size(); }
    public double minX() { return minX; }
    public double minY() { return minY; }
    public double maxX() { return maxX; }
    public double maxY() { return maxY; }
    public double width() { return maxX - minX; }
    public double height() { return maxY - minY; }

    /** Кути прямокутника дошки (блоки). */
    public static double[][] corners(Plank p) {
        double dx = Math.cos(p.angle()), dy = Math.sin(p.angle());
        double ux = -dy, uy = dx;
        double hl = p.length() / 2.0, hw = p.width() / 2.0;
        double[][] out = new double[4][];
        int i = 0;
        for (int sl = -1; sl <= 1; sl += 2) {
            for (int sw = -1; sw <= 1; sw += 2) {
                out[i++] = new double[] {
                    p.cx() + dx * hl * sl + ux * hw * sw,
                    p.cy() + dy * hl * sl + uy * hw * sw };
            }
        }
        return out;
    }

    // ── Кількість без повної сцени (сервер) ──────────────────────────────

    /** Скільки дощок у фіксованій барикаді (з моделей блока). */
    public static int fixedPlankCount() {
        return fixed().plankCount();
    }

    // ── Фіксована барикада ───────────────────────────────────────────────

    private static volatile BarricadeScene fixedCache;

    /** Сцена фіксованої барикади: 1×2 блоки, дошки з {@code boards_lower/upper.json}. */
    public static BarricadeScene fixed() {
        BarricadeScene scene = fixedCache;
        if (scene == null) {
            scene = buildFixed();
            fixedCache = scene;
        }
        return scene;
    }

    private static BarricadeScene buildFixed() {
        List<Plank> planks = new ArrayList<>();
        ResourceLocation block = new ResourceLocation("minecraft", "dark_oak_planks");
        boolean ok = false;
        try {
            JsonObject lower = readModel(LOWER_MODEL);
            JsonObject upper = readModel(UPPER_MODEL);
            if (lower != null && upper != null) {
                block = blockOf(lower, block);
                addModelPlanks(planks, lower, 0.0, block);
                addModelPlanks(planks, upper, 16.0, blockOf(upper, block));
                ok = !planks.isEmpty();
            }
        } catch (RuntimeException | IOException ignored) {
            ok = false;
        }
        if (!ok) {
            // Модель недоступна (не повинно траплятись) — запасний варіант, що збігається з нею.
            planks.clear();
            for (double yOff : new double[] {0.0, 16.0}) {
                double sign = yOff == 0.0 ? 1.0 : -1.0;
                addPlank(planks, 8, 3 + yOff, 16, 5, 0, 0, 0, false, -15, block);
                addPlank(planks, 8, 8.5 + yOff, 14, 5, 0, 0, 0, false, -15, block);
                addPlank(planks, 8, 13.75 + yOff, 16, 4.5, 0, 0, 0, false, -15, block);
                addPlank(planks, 8, 8 + yOff, 24 * Math.sqrt(2), 2 * Math.sqrt(2), 90, 45 * sign, 0, true, -13.5, block);
            }
        }
        return assembleFixed(planks);
    }

    private static JsonObject readModel(String path) throws IOException {
        try (InputStream in = BarricadeScene.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** {@code textures.planks = "minecraft:block/dark_oak_planks"} → блок {@code minecraft:dark_oak_planks}. */
    private static ResourceLocation blockOf(JsonObject model, ResourceLocation fallback) {
        try {
            String tex = model.getAsJsonObject("textures").get("planks").getAsString();
            ResourceLocation rl = ResourceLocation.tryParse(tex);
            if (rl == null) return fallback;
            String path = rl.getPath().startsWith("block/") ? rl.getPath().substring("block/".length()) : rl.getPath();
            return new ResourceLocation(rl.getNamespace(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void addModelPlanks(List<Plank> out, JsonObject model, double yOffsetPx, ResourceLocation block) {
        JsonArray elements = model.getAsJsonArray("elements");
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject();
            double[] from = vec(e.getAsJsonArray("from"));
            double[] to = vec(e.getAsJsonArray("to"));
            double cxm = (from[0] + to[0]) / 2.0, cym = (from[1] + to[1]) / 2.0;
            double dx = to[0] - from[0], dy = to[1] - from[1];
            double zMid = (from[2] + to[2]) / 2.0;

            double rotDeg = 0.0;
            if (e.has("rotation")) {
                JsonObject r = e.getAsJsonObject("rotation");
                // Міні-гра розуміє лише поворот навколо Z (вигляд спереду) — інших у цих моделях немає.
                if (r.has("axis") && "z".equals(r.get("axis").getAsString())) {
                    rotDeg = r.get("angle").getAsDouble();
                    double[] origin = vec(r.getAsJsonArray("origin"));
                    double rad = Math.toRadians(rotDeg);
                    double rx = cxm - origin[0], ry = cym - origin[1];
                    cxm = origin[0] + rx * Math.cos(rad) - ry * Math.sin(rad);
                    cym = origin[1] + rx * Math.sin(rad) + ry * Math.cos(rad);
                    if (r.has("rescale") && r.get("rescale").getAsBoolean()) {
                        double k = 1.0 / Math.cos(Math.abs(rad));
                        dx *= k;
                        dy *= k;
                    }
                }
            }
            boolean vertical = dy > dx;
            double length = vertical ? dy : dx;
            double width = vertical ? dx : dy;
            addPlank(out, cxm, cym + yOffsetPx, length, width, vertical ? 90 : 0, rotDeg, 0, vertical, -zMid, block);
        }
    }

    /**
     * Додає дошку з координатами моделі (пікселі, X на схід, Y вгору; глядач стоїть із боку −Z,
     * тому X дзеркалиться, а кут повороту змінює знак).
     */
    private static void addPlank(List<Plank> out, double cxPx, double cyPx, double lengthPx, double widthPx,
                                 double baseDeg, double modelRotDeg, double grain, boolean across,
                                 double depth, ResourceLocation block) {
        double thetaModel = Math.toRadians(baseDeg + modelRotDeg);       // у площині моделі, проти годинникової зі сторони +Z
        double thetaScreen = Math.PI - thetaModel;                         // дзеркало X для глядача з боку −Z
        thetaScreen = normalizeHalfTurn(thetaScreen);
        out.add(new Plank((16.0 - cxPx) / 16.0, cyPx / 16.0, lengthPx / 16.0, widthPx / 16.0,
            thetaScreen, depth, block, grain, across));
    }

    /** Приводить кут осі до (−90°, 90°]: напрямок без значення, а кінці дошки симетричні. */
    private static double normalizeHalfTurn(double a) {
        double twoPi = Math.PI * 2;
        a = ((a % twoPi) + twoPi) % twoPi;           // [0, 2π)
        if (a > Math.PI) a -= twoPi;                  // (−π, π]
        if (a > Math.PI / 2 + 1e-9) a -= Math.PI;
        else if (a <= -Math.PI / 2 + 1e-9) a += Math.PI;
        return a;
    }

    private static double[] vec(JsonArray arr) {
        double[] v = new double[arr.size()];
        for (int i = 0; i < v.length; i++) v[i] = arr.get(i).getAsDouble();
        return v;
    }

    private static BarricadeScene assembleFixed(List<Plank> planks) {
        List<Nail> nails = new ArrayList<>();
        for (int i = 0; i < planks.size(); i++) {
            Plank p = planks.get(i);
            double inset = FIXED_NAIL_INSET;
            double half = Math.max(0.0, p.length() / 2.0 - inset);
            nails.add(new Nail(i, 0, p.cx() - p.dirX() * half, p.cy() - p.dirY() * half));
            nails.add(new Nail(i, 1, p.cx() + p.dirX() * half, p.cy() + p.dirY() * half));
        }
        // «Дірка» дверей — прорізь 1×2 блоки.
        List<double[]> hole = List.of(
            new double[] {0, 0}, new double[] {1, 0}, new double[] {1, 2}, new double[] {0, 2});
        return new BarricadeScene(true, planks, nails, hole);
    }

    // ── Гнучка барикада ──────────────────────────────────────────────────

    /**
     * Сцена гнучкої барикади.
     *
     * @param boards дошки як у світі
     * @param lookX,lookZ горизонтальний напрямок погляду гравця (потрібен лише для дощок на
     *                    підлозі/стелі: вони «дивляться» вгору, і «верх екрана» — куди дивиться гравець)
     */
    public static BarricadeScene flexible(List<BoardSpec> boards, double lookX, double lookZ) {
        Vec3 n = Vec3.ZERO;
        for (BoardSpec b : boards) n = n.add(b.normal());
        n = n.length() < 1e-6 ? new Vec3(0, 1, 0) : n.normalize();

        Vec3 up;
        if (Math.abs(n.y) > 0.75) {
            Vec3 look = new Vec3(lookX, 0, lookZ);
            if (look.length() < 1e-6) look = new Vec3(0, 0, -1);
            up = look.subtract(n.scale(look.dot(n)));
        } else {
            up = new Vec3(0, 1, 0).subtract(n.scale(n.y));
        }
        up = up.length() < 1e-6 ? new Vec3(0, 0, -1) : up.normalize();
        Vec3 right = n.scale(-1).cross(up).normalize();      // глядач дивиться вздовж −n

        List<Plank> planks = new ArrayList<>();
        List<double[]> a2 = new ArrayList<>();
        List<double[]> b2 = new ArrayList<>();
        for (BoardSpec spec : boards) {
            BarricadeGeometry.Frame f = BarricadeGeometry.frame(spec);
            if (f == null) {
                // Вироджена дошка (a == b): будувальник такої не пропустить, але індекси мають
                // збігатися з сервером, тож лишаємо коротку заглушку.
                double[] pa = project(spec.a(), right, up);
                planks.add(new Plank(pa[0], pa[1], MIN_APPARENT_LENGTH, MIN_APPARENT_WIDTH, 0.0,
                    spec.a().dot(n), spec.block(), 0.0, false));
                a2.add(new double[] {pa[0] - 0.12, pa[1]});
                b2.add(new double[] {pa[0] + 0.12, pa[1]});
                continue;
            }
            double[] pa = project(spec.a(), right, up);
            double[] pb = project(spec.b(), right, up);
            if (Math.hypot(pa[0] - pb[0], pa[1] - pb[1]) < 0.2) {
                // Дошка спрямована в глядача: розводимо цвяхи, щоб обидва можна було вхопити.
                double mx0 = (pa[0] + pb[0]) / 2.0, my0 = (pa[1] + pb[1]) / 2.0;
                pa = new double[] {mx0 - 0.12, my0};
                pb = new double[] {mx0 + 0.12, my0};
            }
            double mx = (pa[0] + pb[0]) / 2.0, my = (pa[1] + pb[1]) / 2.0;

            double dirR = f.dir().dot(right), dirU = f.dir().dot(up);
            double dirLen = Math.hypot(dirR, dirU);
            double angle;
            if (dirLen < 1e-4) {
                angle = 0.0;                                   // дошка спрямована в глядача
            } else {
                angle = Math.atan2(dirU, dirR);
            }
            double length = Math.max(MIN_APPARENT_LENGTH, dirLen * f.halfLength() * 2.0);

            // Видима ширина: проєкція осі «впоперек» на перпендикуляр до осі в площині екрана.
            double perpR = -Math.sin(angle), perpU = Math.cos(angle);
            double uR = f.u().dot(right), uU = f.u().dot(up);
            double width = Math.max(MIN_APPARENT_WIDTH,
                Math.abs(uR * perpR + uU * perpU) * BarricadeGeometry.WIDTH);

            double depth = f.center().dot(n);
            double grain = (Math.abs(spec.a().hashCode()) % 8) / 8.0 * 0.6;   // як у PlankRenderer
            planks.add(new Plank(mx, my, length, width, angle, depth, spec.block(), grain, false));
            a2.add(pa);
            b2.add(pb);
        }
        // Індекси дощок і цвяхів = порядок дощок у ВХІДНОМУ списку (його ж знає сервер);
        // порядок малювання дає окремо drawOrder().
        List<Nail> nails = new ArrayList<>();
        for (int i = 0; i < planks.size(); i++) {
            nails.add(new Nail(i, 0, a2.get(i)[0], a2.get(i)[1]));
            nails.add(new Nail(i, 1, b2.get(i)[0], b2.get(i)[1]));
        }

        // Дірка: опукла оболонка кутів усіх дощок + запас.
        List<double[]> pts = new ArrayList<>();
        for (Plank p : planks) {
            for (double[] c : corners(p)) {
                for (int k = 0; k < 8; k++) {
                    double ang = Math.PI / 4 * k;
                    pts.add(new double[] {c[0] + Math.cos(ang) * HOLE_MARGIN, c[1] + Math.sin(ang) * HOLE_MARGIN});
                }
            }
        }
        if (pts.isEmpty()) {
            pts.add(new double[] {0, 0}); pts.add(new double[] {1, 0}); pts.add(new double[] {1, 1});
        }
        return new BarricadeScene(false, planks, nails, convexHull(pts));
    }

    private static double[] project(Vec3 p, Vec3 right, Vec3 up) {
        return new double[] {p.dot(right), p.dot(up)};
    }

    /** Опукла оболонка (ланцюжок Ендрю), проти годинникової. */
    static List<double[]> convexHull(List<double[]> in) {
        List<double[]> pts = new ArrayList<>(in);
        pts.sort((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
        int n = pts.size();
        if (n < 3) return pts;
        double[][] h = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) k--;
            h[k++] = pts.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) k--;
            h[k++] = pts.get(i);
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) out.add(h[i]);
        return out;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
}

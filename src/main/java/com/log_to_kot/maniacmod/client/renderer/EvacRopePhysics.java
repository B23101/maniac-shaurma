package com.log_to_kot.maniacmod.client.renderer;

import com.log_to_kot.maniacmod.entity.ClimbCarrierEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Клієнтська фізика гойдання мотузки евакуації — ланцюжок Верле з {@link #SEGMENTS} сегментів.
 *
 * <h3>Чому клієнт, і чому не сервер</h3>
 * Це ЧИСТА косметика: гравця по мотузці веде {@code ClimbCarrierEntity} прямо вгору, мотузка на
 * геймплей не впливає. Тому жодних пакетів — кожен клієнт рахує свій ланцюжок сам, а різниця між
 * клієнтами непомітна (вітер детермінований від часу гри й id сутності, тож гойдається майже
 * однаково в усіх).
 *
 * <h3>Що її рухає</h3>
 * <ul>
 *   <li><b>Гравітація + опір</b> — звичайний маятник; верхній кінець прибито до вертольота;</li>
 *   <li><b>Потік від гвинта</b> — повільний «шумовий» вітер, сильніший унизу, тож мотузка
 *       ніколи не висить мертво;</li>
 *   <li><b>Той, хто лізе</b> — сегмент на його висоті притягується до нього, і мотузка вигинається
 *       до гравця (особливо коли він схопився за неї збоку).</li>
 * </ul>
 * Довжина сегмента = {@code length / SEGMENTS}, тож при розмотуванні (length росте) ланцюжок
 * природно «витікає» вниз.
 */
@OnlyIn(Dist.CLIENT)
final class EvacRopePhysics {

    static final int SEGMENTS = 14;

    /** Блоків/с². Більше — жорсткіше й швидше повертається до вертикалі. */
    private static final double GRAVITY = 16.0;
    /** Скільки швидкості ЛИШАЄТЬСЯ через секунду (0.5 — вдвічі менше). Менше — швидше згасає. */
    private static final double VELOCITY_KEEP_PER_SEC = 0.45;
    private static final int ITERATIONS = 10;
    private static final double SUBSTEP = 1.0 / 60.0;
    /** Максимальний крок часу за кадр, тіків (великий лаг не повинен «вистрілити» мотузку). */
    private static final double MAX_DT_TICKS = 5.0;
    /** Скільки блоків над кінцями рук гравця лежить точка хвату відносно його ніг. */
    private static final double GRIP_HEIGHT = 1.5;
    /** Найближче, на що мотузка підходить до лізучого (горизонтально), блоків. */
    private static final double MIN_CLIMBER_GAP = 0.45;

    final double[] x = new double[SEGMENTS + 1];
    final double[] y = new double[SEGMENTS + 1];
    final double[] z = new double[SEGMENTS + 1];
    private final double[] px = new double[SEGMENTS + 1];
    private final double[] py = new double[SEGMENTS + 1];
    private final double[] pz = new double[SEGMENTS + 1];

    private boolean initialized;
    private double lastTicks;
    private final double phase;

    EvacRopePhysics(int seed) {
        this.phase = (seed * 0.6180339887) % (Math.PI * 2.0);
    }

    /**
     * @param ax,ay,az  верхній кінець (світові координати) — прибито
     * @param length    поточна довжина мотузки
     * @param groundY   рівень землі під мотузкою (нижче вузли не опускаються)
     * @param nowTicks  ігровий час у тіках з дробовою частиною (gameTime + partialTick)
     * @param climbers  носії, що зараз на мотузці
     */
    void update(double ax, double ay, double az, double length, double groundY,
                double nowTicks, List<ClimbCarrierEntity> climbers) {
        double seg = length / SEGMENTS;

        if (!initialized) {
            for (int i = 0; i <= SEGMENTS; i++) {
                x[i] = px[i] = ax;
                y[i] = py[i] = ay - i * seg;
                z[i] = pz[i] = az;
            }
            lastTicks = nowTicks;
            initialized = true;
            return;
        }

        double dtTicks = Math.min(nowTicks - lastTicks, MAX_DT_TICKS);
        lastTicks = nowTicks;
        if (dtTicks <= 0.0) {                      // пауза/той самий кадр: лише тримаємо прив'язку
            x[0] = ax; y[0] = ay; z[0] = az;
            return;
        }

        double dt = dtTicks / 20.0;
        int steps = Math.max(1, (int) Math.ceil(dt / SUBSTEP));
        double h = dt / steps;
        for (int s = 0; s < steps; s++) {
            double t = (nowTicks - dtTicks + dtTicks * (s + 1) / steps) / 20.0;
            integrate(h, t);
            constrain(ax, ay, az, seg, groundY, climbers);
        }
    }

    private void integrate(double h, double t) {
        double keep = Math.pow(VELOCITY_KEEP_PER_SEC, h);
        // Потік від гвинта: дві накладені хвилі на кожну вісь, амплітуда — прискорення (блоків/с²).
        double windX = 0.9 * Math.sin(t * 0.8 + phase) + 0.5 * Math.sin(t * 1.9 + phase * 2.0);
        double windZ = 0.8 * Math.cos(t * 0.65 + phase) + 0.4 * Math.sin(t * 2.3 + phase * 1.3);
        double hh = h * h;
        for (int i = 1; i <= SEGMENTS; i++) {
            double w = (double) i / SEGMENTS;      // унизу вітер діє сильніше
            double vx = (x[i] - px[i]) * keep;
            double vy = (y[i] - py[i]) * keep;
            double vz = (z[i] - pz[i]) * keep;
            px[i] = x[i]; py[i] = y[i]; pz[i] = z[i];
            x[i] += vx + windX * w * hh;
            y[i] += vy - GRAVITY * hh;
            z[i] += vz + windZ * w * hh;
        }
    }

    private void constrain(double ax, double ay, double az, double seg, double groundY,
                           List<ClimbCarrierEntity> climbers) {
        double floor = groundY + 0.05;
        for (int it = 0; it < ITERATIONS; it++) {
            x[0] = ax; y[0] = ay; z[0] = az;
            for (int i = 0; i < SEGMENTS; i++) {
                double dx = x[i + 1] - x[i];
                double dy = y[i + 1] - y[i];
                double dz = z[i + 1] - z[i];
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < 1.0E-9) continue;
                double k = (d - seg) / d;
                if (i == 0) {                       // верх прибито: рухається лише наступний вузол
                    x[1] -= dx * k; y[1] -= dy * k; z[1] -= dz * k;
                } else {
                    x[i] += dx * k * 0.5; y[i] += dy * k * 0.5; z[i] += dz * k * 0.5;
                    x[i + 1] -= dx * k * 0.5; y[i + 1] -= dy * k * 0.5; z[i + 1] -= dz * k * 0.5;
                }
            }
            // Гравець лізе збоку від мотузки. Мотузку до нього НЕ тягнемо (ітерацій багато —
            // будь-яка тяга накопичується, і мотузка опиняється в камері), а навпаки відсуваємо
            // від тіла: вузли поблизу висоти гравця тримаються не ближче MIN_CLIMBER_GAP.
            for (ClimbCarrierEntity c : climbers) {
                double bottom = c.getY() - 0.2, top = c.getY() + 2.0;
                for (int i = 1; i <= SEGMENTS; i++) {
                    if (y[i] < bottom || y[i] > top) continue;
                    double dx = x[i] - c.getX(), dz = z[i] - c.getZ();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d >= MIN_CLIMBER_GAP) continue;
                    if (d < 1.0E-6) { dx = 1; dz = 0; d = 1; }
                    double k = MIN_CLIMBER_GAP / d;
                    x[i] = c.getX() + dx * k;
                    z[i] = c.getZ() + dz * k;
                }
            }
            for (int i = 1; i <= SEGMENTS; i++) if (y[i] < floor) y[i] = floor;
        }
    }
}

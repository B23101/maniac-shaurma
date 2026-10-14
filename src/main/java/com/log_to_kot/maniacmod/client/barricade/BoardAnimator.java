package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.barricade.BarricadeGeometry;
import com.log_to_kot.maniacmod.barricade.BoardSpec;
import com.log_to_kot.maniacmod.entity.BarricadeEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Поза однієї дошки гнучкої барикади у світі залежно від стану її цвяхів. ЄДИНЕ місце, що знає,
 * як виглядає розхитана, провисла й упала дошка; малює її далі той самий {@link PlankRenderer}.
 *
 * <ul>
 *   <li><b>Обидва цвяхи на місці</b> — дошка стоїть; кінець, де цвях розхитують, трохи відстає від
 *       поверхні (чим більше розхитано — тим сильніше);</li>
 *   <li><b>Один цвях вийшов</b> — дошка висить на другому: вільний кінець відскакує від поверхні
 *       й провисає вниз із пружинним доганянням;</li>
 *   <li><b>Обидва вийшли</b> — дошка падає на підлогу під барикадою (висота підлоги береться
 *       променем один раз і кешується в сутності), перевертається пласко й лишається лежати до
 *       кінця — поки не розвалиться вся барикада.</li>
 * </ul>
 *
 * Дошка лише ВИГЛЯДАЄ інакше: колізія й промінь лома рахуються сервером за початковою розміткою
 * (промінь пропускає впалі дошки — див. {@code BarricadeEntity#rayDistance}).
 */
@OnlyIn(Dist.CLIENT)
public final class BoardAnimator {

    /** Максимальний кут провисання (рад) — щоб дошка не «перекидалась» навколо цвяха. */
    private static final double MAX_SAG = Math.toRadians(70);
    /** Наскільки розхитаний цвях відводить кінець від поверхні (блоки). */
    private static final double LOOSEN_LIFT = 0.10;
    /** Наскільки відскакує від поверхні вільний кінець дошки, що висить на одному цвяху. */
    private static final double FREE_LIFT = 0.10;
    /** Прискорення падіння (блоків/с²) — трохи «мультяшне», щоб падіння було помітним. */
    private static final double FALL_GRAVITY = 22.0;
    /** Наскільки дошка при падінні відлітає від стіни (блоки, по горизонталі). */
    private static final double FALL_OUTWARD = 0.2;

    private BoardAnimator() {}

    /**
     * @param now gameTime у тіках із дробовою частиною (partialTick)
     * @return поза для малювання; той самий {@code spec}, якщо дошка стоїть недоторканою
     */
    public static BoardSpec pose(BarricadeEntity e, int index, BoardSpec spec, double now) {
        BarricadeGeometry.Frame f = BarricadeGeometry.frame(spec);
        if (f == null) return spec;
        Vec3 n = f.n();

        int nailA = index * 2;
        int nailB = index * 2 + 1;
        int stateA = e.nailState(nailA);
        int stateB = e.nailState(nailB);
        boolean outA = stateA >= BarricadeEntity.NAIL_OUT;
        boolean outB = stateB >= BarricadeEntity.NAIL_OUT;

        // 1. Обидва цвяхи на місці: лише відхід розхитаних кінців.
        if (!outA && !outB) {
            double liftA = LOOSEN_LIFT * stateA / 100.0;
            double liftB = LOOSEN_LIFT * stateB / 100.0;
            if (liftA <= 0 && liftB <= 0) return spec;
            return new BoardSpec(spec.a().add(n.scale(liftA)), spec.b().add(n.scale(liftB)), n, spec.block());
        }

        // 2. Дошка висить на цвяху, що тримався довше. Обидва вийшли — на тому, що вийшов пізніше.
        boolean pivotIsA;
        if (outA && outB) pivotIsA = e.nailOutAt(nailA) >= e.nailOutAt(nailB);
        else pivotIsA = !outA;
        int pivotNail = pivotIsA ? nailA : nailB;
        int freeNail = pivotIsA ? nailB : nailA;
        boolean pivotOut = outA && outB;

        Vec3 pivot = pivotIsA ? spec.a() : spec.b();
        Vec3 free = pivotIsA ? spec.b() : spec.a();
        Vec3 v = free.subtract(pivot);
        double len = v.length();
        if (len < 1e-6) return spec;
        Vec3 vHat = v.scale(1.0 / len);

        // Провисання: пружинне доганяння (трохи перелітає й заспокоюється). Давно вилетілий цвях → 1.
        double sinceFree = (now - e.nailOutAt(freeNail)) / 20.0;
        double sag = 1.0 - Math.exp(-4.0 * sinceFree) * Math.cos(10.0 * sinceFree);

        // Куди «вниз» у площині дошки: гравітація без складової вздовж нормалі.
        Vec3 gravity = new Vec3(0, -1, 0);
        Vec3 inPlane = gravity.subtract(n.scale(gravity.dot(n)));
        double theta = 0;
        if (inPlane.length() > 0.25) {
            Vec3 gHat = inPlane.normalize();
            double phi = Math.atan2(n.dot(vHat.cross(gHat)), vHat.dot(gHat));
            theta = Mth.clamp(phi, -MAX_SAG, MAX_SAG) * sag;
        }
        Vec3 rotated = vHat.scale(Math.cos(theta)).add(n.cross(vHat).scale(Math.sin(theta))).scale(len);

        double pivotLift = pivotOut ? 0.03 : LOOSEN_LIFT * (pivotIsA ? stateA : stateB) / 100.0;
        Vec3 pivotPos = pivot.add(n.scale(pivotLift));
        Vec3 freePos = pivot.add(rotated).add(n.scale(FREE_LIFT * Math.min(1.0, sag)));

        if (!pivotOut) {
            return pivotIsA
                ? new BoardSpec(pivotPos, freePos, n, spec.block())
                : new BoardSpec(freePos, pivotPos, n, spec.block());
        }

        // 3. Падіння на підлогу.
        double fallen = Math.max(0.0, (now - e.nailOutAt(pivotNail)) / 20.0);
        Vec3 center = pivotPos.add(freePos).scale(0.5);
        Vec3 dirHang = freePos.subtract(pivotPos).normalize();

        double ground = e.groundY(index, center);
        double restY = ground + BarricadeGeometry.THICKNESS / 2.0 + 0.01;
        double maxDrop = Math.max(0.0, center.y - restY);
        double drop = Math.min(maxDrop, 0.5 * FALL_GRAVITY * fallen * fallen);
        double k = maxDrop < 1e-4 ? 1.0 : drop / maxDrop;
        double flat = Math.min(1.0, k * 1.3);   // пласко лягає трохи раніше, ніж торкнеться підлоги

        // Лежача дошка: вісь горизонтальна (найближча до висячої), нормаль — вгору.
        Vec3 dirFlat = new Vec3(dirHang.x, 0, dirHang.z);
        if (dirFlat.length() < 0.25) {
            Vec3 outward = new Vec3(n.x, 0, n.z);
            dirFlat = outward.length() > 0.1 ? outward : new Vec3(1, 0, 0);
        }
        dirFlat = dirFlat.normalize();
        if (dirFlat.dot(dirHang) < 0) dirFlat = dirFlat.scale(-1);

        Vec3 dir = lerp(dirHang, dirFlat, flat).normalize();
        Vec3 normal = lerp(n, new Vec3(0, 1, 0), flat).normalize();

        Vec3 outwardH = new Vec3(n.x, 0, n.z);
        Vec3 pos = center.add(0, -drop, 0);
        if (outwardH.length() > 0.1) pos = pos.add(outwardH.normalize().scale(FALL_OUTWARD * flat));

        Vec3 half = dir.scale(len / 2.0);
        // Кінець не повинен провалюватись під підлогу, поки дошка ще не лягла пласко.
        double lowest = Math.min(pos.y - Math.abs(half.y), pos.y);
        double minY = ground + BarricadeGeometry.THICKNESS / 2.0 + 0.01;
        if (lowest < minY) pos = pos.add(0, minY - lowest, 0);

        return new BoardSpec(pos.subtract(half), pos.add(half), normal, spec.block());
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        return from.add(to.subtract(from).scale(t));
    }
}

package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * «Куди дивиться маньяк» для розміщення пасток — ОДНА функція і для
 * сервера, і для клієнта.
 *
 * ── Чому спільна ─────────────────────────────────────────────────────
 * Клієнт щокадру малює квадрат-підказку (зелений/червоний) на блоці,
 * у який дивиться маньяк; сервер при підтвердженні визначає той самий
 * блок. Якби це були два різні шматки коду, вони рано чи пізно
 * розійшлись би: квадрат на одному блоці, а пастка ставиться на
 * сусідньому. Тому обидва звертаються сюди.
 *
 * Приймає {@link Level} і {@link Entity}, а не серверні типи: це
 * спільні класи, які є і в {@code ClientLevel}, і в {@code ServerLevel}.
 *
 * ── Що вважається «підлогою» ─────────────────────────────────────────
 * Пастка стоїть НА ВЕРХНІЙ грані блока. Тому потрібне влучання саме в
 * {@link Direction#UP}: погляд у бік стіни чи в стелю дає {@code null}
 * (червоний квадрат) — ставити пастку на вертикальну грань немає сенсу.
 */
public final class TrapAiming {

    private TrapAiming() {}

    /**
     * @param range дальність розміщення в блоках. Передається ЗОВНІ, а не
     *              читається з конфігу: конфіг живе на сервері, і на
     *              виділеному сервері клієнт його не має — він отримує це
     *              число в {@code TrapLoadoutPacket}. Сервер передає своє
     *              значення напряму з {@code ManiacConfigs}.
     * @return блок-підлога, у верхню грань якого дивиться {@code viewer} у
     *         межах дальності; {@code null}, якщо дивиться не на верхню
     *         грань блока або задалеко
     */
    public static BlockPos targetFloor(Level level, Entity viewer, double range) {
        Vec3 eye = VrServerCompat.aimOrigin(viewer);
        Vec3 end = eye.add(VrServerCompat.aimDirView(viewer).scale(range));

        BlockHitResult hit = level.clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));

        if (hit.getType() != HitResult.Type.BLOCK) return null;
        if (hit.getDirection() != Direction.UP) return null;
        return hit.getBlockPos();
    }

    /**
     * Будь-який блок, у який влучає погляд, разом із гранню — навіть якщо
     * це не верхня грань і навіть якщо далеко. Клієнт використовує це, щоб
     * ЧЕРВОНИЙ квадрат не зникав, коли пастку тут поставити не можна
     * (стіна, стеля, задалеко): маньяк має бачити, ЩО саме не так, а не
     * «куб просто пропав».
     *
     * @param reach довжина променя (може бути більшою за дальність
     *              розміщення — тоді «задалеко» стає червоним, а не порожнім)
     * @return влучання або {@code null}, якщо погляд у небо
     */
    public static BlockHitResult rawHit(Level level, Entity viewer, double reach) {
        Vec3 eye = VrServerCompat.aimOrigin(viewer);
        Vec3 end = eye.add(VrServerCompat.aimDirView(viewer).scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /**
     * Чи справді {@code viewer} зараз дивиться у верхню грань блока
     * {@code floor} (з допуском). Сервер використовує це, щоб ПРИЙНЯТИ блок,
     * який показав клієнт, замість того щоб сліпо перерахувати свій —
     * інакше пакет, що прийшов на тік пізніше (гравець встиг повернути
     * голову, позиція ока інша), ставив би пастку не на той куб, що
     * світився.
     *
     * Допуск — {@code TOLERANCE} блока навколо верхньої грані: достатньо,
     * щоб пережити мережеву затримку й рух, замало, щоб «клікнути» блок
     * у сусідній кімнаті.
     */
    public static boolean isLookingAtFloor(Level level, Entity viewer, BlockPos floor, double range) {
        Vec3 eye = VrServerCompat.aimOrigin(viewer);
        Vec3 look = VrServerCompat.aimDirView(viewer);
        Vec3 center = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);

        // Проєкція центру верхньої грані на промінь погляду.
        Vec3 toCenter = center.subtract(eye);
        double along = toCenter.dot(look);
        if (along < 0.0 || along > range + 1.5) return false;

        // Наскільки промінь «промахується» повз центр грані.
        Vec3 closest = eye.add(look.scale(along));
        double miss = closest.distanceTo(center);
        // Половина діагоналі грані (~0.71) + допуск на затримку/поворот.
        return miss <= 0.71 + TOLERANCE;
    }

    /** Допуск (блоки), з яким сервер довіряє блоку, який показав клієнт. */
    private static final double TOLERANCE = 0.75;
}

package com.log_to_kot.maniacmod.traps;

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
        Vec3 eye = viewer.getEyePosition();
        Vec3 end = eye.add(viewer.getViewVector(1.0f).scale(range));

        BlockHitResult hit = level.clip(new ClipContext(
            eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));

        if (hit.getType() != HitResult.Type.BLOCK) return null;
        if (hit.getDirection() != Direction.UP) return null;
        return hit.getBlockPos();
    }
}

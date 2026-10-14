package com.log_to_kot.maniacmod.loot;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * «На який предмет на землі дивиться гравець» — ПРОМІНЬ погляду, а не
 * ванільний приціл.
 *
 * ── Навіщо ────────────────────────────────────────────────────────────
 * Ванільний приціл ({@code Minecraft.hitResult}) вибирає ближче з двох:
 * блок чи сутність. Трава, квіти, кущі не мають колізії, але мають
 * контур — тож приціл «впирається» в траву перед предметом, і ПКМ по
 * предмету не проходив, хоча гравець дивився прямо на нього.
 *
 * Тут блок перекриває предмет лише тоді, коли в нього є КОЛІЗІЯ
 * ({@link ClipContext.Block#COLLIDER}): крізь траву й квіти предмет
 * видно й можна взяти, а крізь стіну чи підлогу — ні.
 *
 * ── Спільний для клієнта й сервера ───────────────────────────────────
 * Ті самі числа й та сама геометрія: клієнт вирішує, чи слати намір
 * «взяти», сервер перевіряє той самий промінь заново (з більшим
 * запасом на розсинхрон погляду), а не довіряє клієнту.
 */
public final class GroundItemAim {

    /** Довжина променя, блоків. Реальну дальність підбору обмежує {@code pickupRangeBlocks}. */
    public static final double AIM_REACH = 4.5;

    /** Запас навколо боксу предмета на клієнті, блоків. */
    public static final double CLIENT_MARGIN = 0.10;

    /** Запас на сервері — більший, бо погляд гравця на сервері трохи відстає від клієнтського. */
    public static final double SERVER_MARGIN = 0.35;

    /** Допуск при порівнянні відстаней до предмета й до блока-перешкоди. */
    private static final double OCCLUSION_EPS = 0.10;

    private GroundItemAim() {}

    /** Найближчий предмет під променем погляду або {@code null}. */
    public static GroundItemEntity find(Player player, double margin) {
        Level level = player.level();
        Vec3 eye = VrServerCompat.aimOrigin(player);
        Vec3 look = VrServerCompat.aimDir(player);
        Vec3 end = eye.add(look.scale(AIM_REACH));
        double blockDist = blockDistance(player, eye, end);

        AABB search = player.getBoundingBox().expandTowards(look.scale(AIM_REACH)).inflate(1.0);
        GroundItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (GroundItemEntity item : level.getEntitiesOfClass(GroundItemEntity.class, search,
                GroundItemEntity::hasStack)) {
            double d = rayDistance(item, eye, end, margin);
            if (Double.isNaN(d) || d > blockDist + OCCLUSION_EPS) continue;
            if (d < bestDist) {
                bestDist = d;
                best = item;
            }
        }
        return best;
    }

    /** Чи промінь погляду гравця влучає саме в цей предмет (і не закритий стіною). */
    public static boolean isAimedAt(Player player, GroundItemEntity item, double margin) {
        if (!item.hasStack()) return false;
        Vec3 eye = VrServerCompat.aimOrigin(player);
        Vec3 end = eye.add(VrServerCompat.aimDir(player).scale(AIM_REACH));
        double d = rayDistance(item, eye, end, margin);
        return !Double.isNaN(d) && d <= blockDistance(player, eye, end) + OCCLUSION_EPS;
    }

    /** Відстань до першого блока З КОЛІЗІЄЮ на промені (трава й квіти не рахуються). */
    private static double blockDistance(Player player, Vec3 eye, Vec3 end) {
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? eye.distanceTo(hit.getLocation()) : AIM_REACH;
    }

    /** Відстань вздовж променя до боксу предмета або {@link Double#NaN}. */
    private static double rayDistance(GroundItemEntity item, Vec3 eye, Vec3 end, double margin) {
        AABB box = item.getBoundingBox().inflate(margin);
        if (box.contains(eye)) return 0.0;
        Optional<Vec3> hit = box.clip(eye, end);
        return hit.map(eye::distanceTo).orElse(Double.NaN);
    }
}

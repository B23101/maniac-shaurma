package com.log_to_kot.maniacmod.client.hooks;

import com.log_to_kot.maniacmod.entity.VictimSeatEntity;
import com.log_to_kot.maniacmod.hooks.HookStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Математика «жертва на плечі маніяка»: ЄДИНЕ місце, де вирішується, ДЕ малюється той, кого несуть.
 * Рахується щокадру від ІНТЕРПОЛЬОВАНОГО тіла носія, тому жертва їде разом з маніяком рівно, без
 * ривків і «телепорту» (сервер-сидіння оновлюється лише 20 Гц).
 *
 * <h3>Поза тепер повністю з файлу</h3>
 * Раніше цей клас сам перевертав модель жертви матрицею (животом униз, кут звисання), а PAL-кліп
 * лише гойдав кінцівки. Тепер поза {@code player_animation/carried_shoulder.json} (ваша «тримає
 * маніяк») САМА кладе корпус горизонтально — кістка {@code body} = -90° навколо стегон, голова
 * відкинута, руки й ноги звисають. Тому тут жодних поворотів: лише ПЕРЕНЕСЕННЯ стегон жертви на
 * плече носія + поворот тіла за носієм (yaw). Подвійного повороту (матриця + кліп) більше немає —
 * саме він давав би жертву, що «стирчить» під неправильним кутом.
 *
 * <h3>Що підкручувати</h3>
 * Висота/бік/зсув — у частках розмірів носія (працює для низького й високого маніяка однаково),
 * напрям тіла — {@link #BODY_YAW_OFFSET_DEG}. Дрібну підгонку «тулуб глибше/вище на плечі» краще
 * робити позицією кістки {@code body} у самому кліпі (Blockbench), а не тут.
 *
 * <h3>Система координат</h3>
 * «Вперед» носія {@code f = (-sin yaw, 0, cos yaw)} (yaw 0 = +Z), «праворуч» {@code r = (-cos yaw, 0, -sin yaw)}.
 * Жертва лежить НА СПИНІ (кліп) вздовж осі носія: голова — назад, ноги — вперед.
 */
@OnlyIn(Dist.CLIENT)
public final class CarryPose {

    /** Висота плеча — частка зросту носія. */
    public static final float SHOULDER_HEIGHT_FRACTION = 0.80f;
    /** Скільки блоків над плечем лежить ТАЗ жертви (півтовщини тулуба + запас, щоб не різало плече). */
    public static final float BODY_LIFT = 0.14f;
    /** Зсув убік від осі носія (частка ширини хітбокса); додатний — праве плече, від'ємний — ЛІВЕ. */
    public static final float SHOULDER_SIDE_FRACTION = -0.38f;
    /** Зсув назад від осі носія (частка ширини хітбокса); від'ємний — назад. */
    public static final float SHOULDER_FORWARD_FRACTION = -0.10f;
    /**
     * Куди повернуте тіло жертви відносно тіла носія, градуси. 0 — однаково з носієм: лежачи на спині,
     * голова жертви — за спиною носія, ноги — перед ним. 180 — навпаки. ±90 — упоперек плечей.
     */
    public static final float BODY_YAW_OFFSET_DEG = 0f;
    /** Висота таза (стегон) жертви над її ногами: 12 px моделі × 15/16 / 16 = 0.703 блока. Це вісь повороту кістки {@code body}. */
    private static final float HIP_Y = 0.703f;

    private CarryPose() {}

    /** Готовий кадр: куди посадити таз жертви і куди повернути її тіло. */
    public record Frame(Vec3 anchor, float bodyYaw) {
        /**
         * Позиція очей жертви в світі (камера власної першої особи). Корпус лежить горизонтально
         * (кліп), тому голова — на відстані «очі мінус таз» від таза ВЗДОВЖ спини жертви.
         */
        public Vec3 eyeWorld(float eyeHeight) {
            double rad = Math.toRadians(bodyYaw);
            double along = eyeHeight - HIP_Y;
            // Голова лежачого на спині — у бік «назад» самої жертви: (sin yaw, 0, -cos yaw).
            return anchor.add(Math.sin(rad) * along, 0.0, -Math.cos(rad) * along);
        }
    }

    /** Носій цієї жертви на клієнті, або {@code null}, якщо її зараз не несуть. */
    public static Player carrierOf(Entity victim) {
        if (!(victim.getVehicle() instanceof VictimSeatEntity seat)) return null;
        if (seat.status() != HookStatus.CARRIED) return null;
        if (victim.level() == null) return null;
        Entity carrier = victim.level().getEntity(seat.carrierId());
        return carrier instanceof Player p && p != victim ? p : null;
    }

    /** Рахує позицію таза й напрям тіла жертви на момент {@code partialTick}. */
    public static Frame compute(Player carrier, float partialTick) {
        float carrierYaw = Mth.rotLerp(partialTick, carrier.yBodyRotO, carrier.yBodyRot);
        double rad = Math.toRadians(carrierYaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double rx = -Math.cos(rad), rz = -Math.sin(rad);

        float width = carrier.getBbWidth();
        Vec3 base = carrier.getPosition(partialTick);
        double ax = base.x + fx * width * SHOULDER_FORWARD_FRACTION + rx * width * SHOULDER_SIDE_FRACTION;
        double ay = base.y + carrier.getBbHeight() * SHOULDER_HEIGHT_FRACTION + BODY_LIFT;
        double az = base.z + fz * width * SHOULDER_FORWARD_FRACTION + rz * width * SHOULDER_SIDE_FRACTION;

        return new Frame(new Vec3(ax, ay, az), Mth.wrapDegrees(carrierYaw + BODY_YAW_OFFSET_DEG));
    }

    /**
     * Зсув камери власної першої особи жертви: від ванільної позиції очей (по сидінню, 20 Гц) до
     * очей на плечі носія (кадр у кадр). {@code null} — гравця не несуть, камеру не чіпаємо.
     */
    public static Vec3 cameraShift(Entity cameraEntity, float partialTick) {
        if (!(cameraEntity instanceof Player victim)) return null;
        Player carrier = carrierOf(victim);
        if (carrier == null) return null;
        Frame frame = compute(carrier, partialTick);
        return frame.eyeWorld(victim.getEyeHeight()).subtract(victim.getEyePosition(partialTick));
    }

    /** Висота таза, що ляже на плече (для трансформації рендера). */
    public static float hipY() { return HIP_Y; }

    /** Свій клієнтський гравець — чи його зараз несуть (для камери). */
    public static boolean localIsCarried() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && carrierOf(mc.player) != null;
    }
}

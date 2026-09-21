package com.log_to_kot.maniacmod.loot;

import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * ЄДИНЕ місце, де створюються {@link GroundItemEntity}.
 *
 * <p>Без цього класу {@code ModEntityTypes.GROUND_ITEM.get()} довелося б
 * викликати і з модуля луту (спавн на карті), і з хука Q (кидок), і
 * колись зі скриньки/трупа — і кожен дописував би власну логіку
 * позиції та повороту. Тепер додатковий спосіб з'явитися = один метод
 * тут.</p>
 */
public final class GroundItemSpawner {

    // ── Кидок гравця: ті самі числа, що у ванільного Player.drop ─────────
    // (LivingEntity/Player.drop у 1.20.1). Виносимо в константи, бо це
    // єдине місце, де «дуга Q-викидання» може знадобитись підкрутити.

    /** Предмет з'являється трохи нижче очей, а не на рівні голови. */
    private static final double THROW_EYE_OFFSET = 0.3;

    /** Швидкість вильоту вперед по погляду, блоків/тік. */
    private static final float THROW_FORWARD_SPEED = 0.3f;

    /** Додатковий підйом, щоб дуга йшла вгору, а не пласко. */
    private static final float THROW_UPWARD_BOOST = 0.1f;

    /** Випадковий розкид напрямку. Без нього два кидки поспіль лягають в одну точку. */
    private static final float THROW_SPREAD = 0.02f;

    /** Висота, на яку піднімаємо предмет над точкою розмітки, щоб він упав, а не з'явився в підлозі. */
    private static final double MAP_SPAWN_LIFT = 0.5;

    private GroundItemSpawner() {}

    /**
     * Кладе предмет на ITEM-точку карти.
     *
     * <p>Предмет з'являється на {@link #MAP_SPAWN_LIFT} вище точки й
     * падає сам: розмітка задає позицію блока, а не «ідеальну висоту
     * предмета», і спавн рівно в підлозі дав би застряглу сутність.</p>
     *
     * @return {@code true}, якщо світ прийняв сутність
     */
    public static boolean spawnOnMap(ServerLevel level, BlockPos point, ItemStack stack, Random rng) {
        GroundItemPlacement placement = GroundItemPlacement.of(stack.getItem(), point, rng);
        GroundItemEntity entity = GroundItemEntity.spawn(
            ModEntityTypes.GROUND_ITEM.get(), level, stack,
            placement.pos().getX() + 0.5, placement.pos().getY() + MAP_SPAWN_LIFT, placement.pos().getZ() + 0.5,
            placement.rotationX());
        return entity != null;
    }

    /**
     * Кидає предмет ІЗ ІНВЕНТАРЯ гравця (клавіша Q).
     *
     * <p>Рух відтворює ванільний {@code Player.drop}: предмет з'являється
     * біля очей і летить по дузі за напрямком погляду — гравець не бачить
     * різниці з ванільним викиданням, лише сутність замість
     * {@code ItemEntity}.</p>
     *
     * @return {@code true}, якщо світ прийняв сутність
     */
    public static boolean throwFrom(ServerPlayer player, ItemStack stack) {
        Random rng = new Random();
        ServerLevel level = player.serverLevel();

        Vec3 pos = new Vec3(player.getX(), player.getEyeY() - THROW_EYE_OFFSET, player.getZ());

        // Напрямок погляду → швидкість. Ванільна формула розкладає кути
        // у XZ-компоненту (cos по pitch) і Y-компоненту (sin по pitch).
        float pitch = player.getXRot() * ((float) Math.PI / 180f);
        float yaw = player.getYRot() * ((float) Math.PI / 180f);
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);

        // Мале випадкове відхилення (ванільно — той самий 0.02-масштаб).
        double jitterAngle = rng.nextFloat() * (Math.PI * 2);
        double jitterMag = THROW_SPREAD * rng.nextFloat();

        Vec3 velocity = new Vec3(
            -sinYaw * cosPitch * THROW_FORWARD_SPEED + Math.cos(jitterAngle) * jitterMag,
            -sinPitch * THROW_FORWARD_SPEED + THROW_UPWARD_BOOST
                + (rng.nextFloat() - rng.nextFloat()) * THROW_SPREAD,
            cosYaw * cosPitch * THROW_FORWARD_SPEED + Math.sin(jitterAngle) * jitterMag);

        GroundItemEntity entity = GroundItemEntity.thrown(
            ModEntityTypes.GROUND_ITEM.get(), level, stack, pos, velocity,
            rng.nextFloat() * 360f);
        return entity != null;
    }

    // ── Смерть виживого: предмети навколо тіла ───────────────────────────

    /** Висота появи предмета над підлогою тіла: лежачий гравець низько. */
    private static final double BODY_DROP_HEIGHT = 0.35;

    /** Горизонтальний розліт, блоків/тік. Мале — предмети лягають «поколу», а не летять. */
    private static final double BODY_DROP_SPREAD_SPEED = 0.10;

    /** Невеликий підкид угору, щоб предмет не «прилипав» до тіла. */
    private static final double BODY_DROP_UP_SPEED = 0.18;

    /**
     * Викидає предмет НАВКОЛО тіла загиблого — не по дузі за поглядом, як
     * {@link #throwFrom} (Q), а в довільний бік із малою швидкістю: після
     * смерті предмети розсипаються довкола гравця, а не летять від нього.
     * Та сама сутність і те саме падіння, що й у Q-викиданні, тож підібрати
     * їх можна звичайним способом.
     *
     * @return {@code true}, якщо світ прийняв сутність
     */
    public static boolean dropAround(ServerPlayer body, ItemStack stack) {
        Random rng = new Random();
        ServerLevel level = body.serverLevel();

        Vec3 pos = new Vec3(body.getX(), body.getY() + BODY_DROP_HEIGHT, body.getZ());
        double angle = rng.nextDouble() * Math.PI * 2;
        // 0.4..1.0 від максимуму: усі предмети різної дальності, а не кільце.
        double speed = BODY_DROP_SPREAD_SPEED * (0.4 + 0.6 * rng.nextDouble());
        Vec3 velocity = new Vec3(Math.cos(angle) * speed, BODY_DROP_UP_SPEED, Math.sin(angle) * speed);

        GroundItemEntity entity = GroundItemEntity.thrown(
            ModEntityTypes.GROUND_ITEM.get(), level, stack, pos, velocity,
            rng.nextFloat() * 360f);
        return entity != null;
    }
}

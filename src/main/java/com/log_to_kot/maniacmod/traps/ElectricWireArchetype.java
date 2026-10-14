package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * Електричний дріт — нова пастка маньяка (ТЗ 2-9).
 *
 * ── Форма розміщення ──────────────────────────────────────────────
 * {@link PlacementShape#TWO_POINTS}: маньяк вибирає ДВІ точки. Між ними
 * натягується прямий дріт; на кожній точці стоїть своє кріплення
 * ({@link com.log_to_kot.maniacmod.entity.ElectricWireMountEntity}).
 * Обмеження з ТЗ 2.1 перевіряються у {@link #validatePlacement(BlockGetter, BlockPos, BlockPos)}:
 * один Y, довжина ≤ {@code electricWireMaxLengthBlocks}, без блоків між.
 *
 * ── Що тут, а що в TrapModule ─────────────────────────────────────
 * Тут — ЛИШЕ «де можна поставити» й «що з'являється у світі»
 * (сам дріт-сутність, яка створює й обидва кріплення). Життєвий цикл
 * удару струмом (лок, шкода, таймер, звуки, частинки) живе в
 * {@link TrapModule} — як і в капкана, бо це СПІЛЬНИЙ стан матчу
 * «хто зараз у пастці», а не властивість конкретної пастки.
 *
 * ── Ефект на жертву ───────────────────────────────────────────────
 * {@link #applyEffect} тут свідомо порожній: удар струмом — тривалий
 * процес із власним таймером, який веде {@link TrapModule#tickWire}
 * (дріт сам повідомляє модуль про торкання). Якби ефект писався тут,
 * він мусив би тримати таймер у собі — а таймер належить матчу, не
 * архетипу.
 */
public class ElectricWireArchetype extends TrapArchetype {

    public static final String ID = "electric_wire";

    public ElectricWireArchetype() {
        super(ID, "Електричний дріт");
    }

    @Override
    public PlacementShape placementShape() {
        return PlacementShape.TWO_POINTS;
    }

    /**
     * Перевірка ОДНІЄЇ точки кріплення: вона мусить спиратись на
     * повноцінний блок (як капкан), а місце над блоком — бути вільним,
     * щоб кріплення не опинилось усередині сусіднього блока.
     */
    @Override
    public PlacementResult validatePlacement(BlockGetter level, BlockPos point) {
        if (!TrapPlacementRules.isFullBlock(level, point)) {
            return PlacementResult.NOT_FULL_BLOCK;
        }
        BlockPos above = point.above();
        var aboveState = level.getBlockState(above);
        if (!aboveState.getCollisionShape(level, above).isEmpty() || !aboveState.getFluidState().isEmpty()) {
            return PlacementResult.NO_ROOM_ABOVE;
        }
        return PlacementResult.OK;
    }

    /**
     * Перевірка ПАРИ точок — обмеження ТЗ 2.1.
     *
     * <p>Порядок перевірок: спершу дешеві (Y, довжина), потім обхід лінії.
     * Кожна точка окремо вже перевірена {@link #validatePlacement(BlockGetter, BlockPos)}
     * — тут лише те, що залежить від ПАРИ.</p>
     */
    @Override
    public PlacementResult validatePlacement(BlockGetter level, BlockPos pointA, BlockPos pointB) {
        if (pointA.getY() != pointB.getY()) return PlacementResult.SAME_Y_REQUIRED;
        if (pointA.equals(pointB)) return PlacementResult.TOO_SHORT;

        Vec3 a = wireAnchor(level, pointA);
        Vec3 b = wireAnchor(level, pointB);
        double max = ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_MAX_LENGTH_BLOCKS);
        double distance = a.distanceTo(b);
        if (distance < 0.5) return PlacementResult.TOO_SHORT;
        // Крихітний запас на похибку float: діагональ рівно на ліміті
        // (напр. 2.83 при ліміті 2.83) не має відхилятись через 1e-12.
        if (distance > max + 1e-6) return PlacementResult.TOO_LONG;

        return isLineClear(level, a, b) ? PlacementResult.OK : PlacementResult.OBSTRUCTED;
    }

    /**
     * Чи вільний відрізок a→b для дроту ТОВЩИНОЮ з реальний хітбокс.
     *
     * ── Чому не «крок 0.25 і BlockPos.containing» ────────────────────
     * Дріт лежить рівно на висоті {@code point.getY() + 1.0} — це МЕЖА
     * між підлогою і повітрям. {@code BlockPos.containing(x, y, z)} на
     * такій межі через float-похибку то потрапляв у блок-підлогу
     * (тоді ВЕСЬ дріт «заблокований» без причини), то ні. А точкова
     * проба по центральній лінії ще й не бачила кутів: діагональний дріт
     * проходить впритул до ребер сусідніх блоків і «прорізав» їх, хоча
     * візуально це стіна.
     *
     * Тому: піднімаємо лінію на {@link #LINE_LIFT} (гарантовано в
     * повітря над підлогою) і перевіряємо ТРИ паралельні промені —
     * центральний і два бокових зі зсувом на радіус дроту перпендикулярно
     * напрямку. Промінь блокує будь-який блок із непорожньою колізійною формою.
     */
    private static boolean isLineClear(BlockGetter level, Vec3 a, Vec3 b) {
        Vec3 dir = b.subtract(a);
        double len = dir.length();
        if (len < 1e-6) return true;
        // Перпендикуляр у горизонтальній площині (дріт на одному Y).
        Vec3 side = new Vec3(-dir.z, 0.0, dir.x).normalize().scale(WIRE_HALF_WIDTH);

        Vec3 lift = new Vec3(0.0, LINE_LIFT, 0.0);
        Vec3[] offsets = { Vec3.ZERO, side, side.scale(-1.0) };
        for (Vec3 off : offsets) {
            if (!isSegmentFree(level, a.add(lift).add(off), b.add(lift).add(off))) return false;
        }
        return true;
    }

    /**
     * Чи не перетинає відрізок колізійну форму жодного блока.
     *
     * Власний обхід замість {@code level.clip(ClipContext)}: конструктор
     * {@code ClipContext} вимагає {@code Entity}, а в спільній (клієнт +
     * сервер) валідації сутності немає. {@code BlockGetter.clip(from, to,
     * pos, shape, state)} перевіряє ОДИН блок; обхід вокселів
     * ({@link BlockGetter#traverseBlocks}) дає точно ті клітинки, які
     * відрізок зачіпає — включно з кутовими на діагоналі.
     */
    private static boolean isSegmentFree(BlockGetter level, Vec3 from, Vec3 to) {
        return BlockGetter.traverseBlocks(from, to, level, (getter, pos) -> {
            var state = getter.getBlockState(pos);
            var shape = state.getCollisionShape(getter, pos);
            if (shape.isEmpty()) return null;
            var hit = shape.clip(from, to, pos);
            return hit != null ? Boolean.FALSE : null;
        }, getter -> Boolean.TRUE);
    }

    /**
     * Додатковий підйом проб: 0 — дріт і так лежить у повітрі на
     * {@link #WIRE_HEIGHT} над підлогою, перевіряємо саме його висоту.
     */
    private static final double LINE_LIFT = 0.0;

    /** Половина товщини дроту для перевірки перешкод (близько до хітбокса). */
    private static final double WIRE_HALF_WIDTH = 0.12;

    /**
     * Висота дроту над верхньою граню підлоги: 2 пікселі (2/16 блока).
     * Раніше дріт лежав рівно НА землі (Y+1.0), тож виглядав приплюснутим
     * до підлоги, а перевірка «наступив» його майже не бачила.
     */
    public static final double WIRE_HEIGHT = 2.0 / 16.0;

    /**
     * Точка кріплення дроту: центр блока-точки на висоті
     * {@link #WIRE_HEIGHT} над верхньою граню. Та сама формула, що в
     * {@link ElectricWireEntity}, рендері й прев'ю — щоб геометрія
     * валідації й геометрія моделі не розійшлись.
     */
    public static Vec3 wireAnchor(BlockGetter level, BlockPos point) {
        return new Vec3(point.getX() + 0.5, point.getY() + 1.0 + WIRE_HEIGHT, point.getZ() + 0.5);
    }

    @Override
    public Entity spawn(ServerLevel level, BlockPos pointA, BlockPos pointB, ServerPlayer placer) {
        return ElectricWireEntity.spawn(ModEntityTypes.ELECTRIC_WIRE.get(), level, pointA, pointB, placer.getUUID());
    }

    /**
     * Чи спрацьовує пастка. Для дроту власного додаткового фільтра немає:
     * спільні правила (вижив, на ногах, не в пастці) перевіряє
     * {@link TrapModule} ОДНИМ місцем на всі пастки, тож дублювати їх
     * тут означало б мати два джерела правди.
     */
    @Override
    public boolean shouldTrigger(LivingEntity candidate) {
        return true;
    }

    /**
     * Ефект на жертву виконує {@link TrapModule#tickWire} — тут порожньо
     * навмисно (див. клас-докстрінг).
     */
    @Override
    public void applyEffect(LivingEntity victim, BlockPos trapPos) {
        // Див. клас-докстрінг: таймер удару струмом належить TrapModule.
        ManiacMod.LOGGER.debug("[trap] electric_wire applyEffect викликано — очікується лише через tickWire");
    }

    @Override
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_PLACE_COOLDOWN_TICKS);
    }
}

package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.BarricadeColliderEntity;
import com.log_to_kot.maniacmod.entity.BarricadeEntity;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
import com.log_to_kot.maniacmod.entity.ClimbCarrierEntity;
import com.log_to_kot.maniacmod.entity.ElectricWireMountEntity;
import com.log_to_kot.maniacmod.entity.EvacRopeEntity;
import com.log_to_kot.maniacmod.entity.HelicopterEntity;
import com.log_to_kot.maniacmod.entity.HookEntity;
import com.log_to_kot.maniacmod.entity.VictimSeatEntity;
import com.log_to_kot.maniacmod.entity.ReceiverEntity;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
import com.log_to_kot.maniacmod.entity.ManiacAvatar;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.log_to_kot.maniacmod.entity.SmokeMineEntity;
import com.log_to_kot.maniacmod.entity.SpikeTrapEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Сутності мода.
 *
 * ── Відмінності від v3 ───────────────────────────────────────────────
 * Прибрано CORPSE: труп як окрема сутність замінено станом
 * SurvivorState.UNCONSCIOUS у самого гравця. У v3 труп був окремою
 * сутністю, яку треба було вручну шукати й видаляти (див.
 * ManiacGameManager.removeCorpseEntity — пошук по всіх сутностях
 * світу з map→id→orElse(-1)); при виході гравця труп лишався навічно.
 *
 * Додано GROUND_ITEM: предмети на землі — власні сутності з
 * geo-моделлю, а не ванільний ItemEntity.
 *
 * Додано GENERATOR: генератор тепер сутність, а не блок
 * (GeneratorBlock), що картобудівник ставив руками на карті — сутність
 * сама спавниться при застосуванні плану ({@code MatchOrchestrator.applySpawnPlan}).
 * Хітбокс 1.2×1.2 — квадрат, трохи більший за стандартний блок, щоб
 * узгодити зону взаємодії з фізичним розміром geo-моделі.
 *
 * MANIAC-сутність з'явиться тут, коли буде перенесено модуль maniacs.
 */
public final class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ManiacMod.MOD_ID);

    public static final RegistryObject<EntityType<GroundItemEntity>> GROUND_ITEM =
        ENTITY_TYPES.register("ground_item", () ->
            EntityType.Builder.<GroundItemEntity>of(GroundItemEntity::new, MobCategory.MISC)
                // Було 0.5×0.3 — надто низький хітбокс: у високій траві
                // (tall_grass, кущах) верх сутності опинявся НИЖЧЕ текстури
                // рослинності, тож гравцевий приціл (ванільний raytrace по
                // isPickable-сутностях) фізично не потрапляв у хітбокс —
                // клік по видимому предмету проходив повз ціль, «не можна
                // підняти». 0.6×0.6 виступає над стандартною висотою
                // короткої трави й дає приціл достатньо товщини по висоті,
                // лишаючись візуально малим (сам рендер масштабується
                // окремо в GroundItemRenderer.SCALE, хітбокс з ним не
                // зв'язаний).
                .sized(0.6f, 0.6f)
                // 6 чанків = 96 блоків. Предмети малі й сервер усе одно
                // обрізає це до власного view-distance; далі за
                // shouldRenderAtSqrDistance (48 блоків) їх не малюють,
                // тож більше значення лише марно слало б пакети.
                .clientTrackingRange(6)
                // 3 тіки: предмет, що падає чи котиться, має виглядати
                // плавно. Було 20 — це годилось для «лежить назавжди», але
                // тепер сутність має живу фізику, і 20 тіків давали б
                // ривки по секунді. Спляча сутність стоїть на місці, тож
                // трекер однаково нічого не шле, доки позиція не зміниться.
                .updateInterval(3)
                .noSummon()
                .fireImmune()
                .build("ground_item"));

    /**
     * Тіло гравця, що вийшов посеред матчу (shaurma-lib, OfflinePresence).
     * Розмір гравця: інакше удар маньяка й промінь прицілу не збігалися б з
     * видимим силуетом. Предметів і голограми навколо немає, тож трекінг
     * як у звичайного гравця.
     */
    public static final RegistryObject<EntityType<ManiacAvatar>> OFFLINE_AVATAR =
        ENTITY_TYPES.register("offline_avatar", () ->
            EntityType.Builder.<ManiacAvatar>of(ManiacAvatar::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .updateInterval(2)
                .noSummon()
                .build("offline_avatar"));

    public static final RegistryObject<EntityType<GeneratorEntity>> GENERATOR =
        ENTITY_TYPES.register("generator", () ->
            EntityType.Builder.<GeneratorEntity>of(GeneratorEntity::new, MobCategory.MISC)
                .sized(1.2f, 1.2f)
                // 64 чанки = 1024 блоки. Сервер все одно обрізає це до
                // власного view-distance, тож реально видно так далеко,
                // як налаштовано сервер; далі працює екранний маркер вибуху.
                .clientTrackingRange(64)
                .updateInterval(10)   // ACTIVE перемикається нечасто, але не "ніколи"
                .noSummon()
                .fireImmune()
                .build("generator"));

    // ── Евакуація (див. evac/) ───────────────────────────────────────────

    /** Приймач: один на карті за матч. Невеликий ящик-радіо. */
    public static final RegistryObject<EntityType<ReceiverEntity>> RECEIVER =
        ENTITY_TYPES.register("receiver", () ->
            EntityType.Builder.<ReceiverEntity>of(ReceiverEntity::new, MobCategory.MISC)
                .sized(1.0f, 1.5f)
                .clientTrackingRange(16)
                .updateInterval(20)   // змінюється лише візуальний стан — рідко
                .noSummon()
                .fireImmune()
                .build("receiver"));

    /**
     * Вертоліт. Видно якомога далі (сигнал для маньяка), оновлення КОЖЕН тік: він летить
     * ~28 блоків/с, на меншій частоті рух був би ривками.
     */
    public static final RegistryObject<EntityType<HelicopterEntity>> HELICOPTER =
        ENTITY_TYPES.register("helicopter", () ->
            EntityType.Builder.<HelicopterEntity>of(HelicopterEntity::new, MobCategory.MISC)
                .sized(4.0f, 2.0f)
                .clientTrackingRange(64)
                .updateInterval(1)
                .noSummon()
                .fireImmune()
                .build("helicopter"));

    /** Мотузка. Базовий хітбокс умовний — справжній росте з {@code EvacRopeEntity#getDimensions}. */
    public static final RegistryObject<EntityType<EvacRopeEntity>> EVAC_ROPE =
        ENTITY_TYPES.register("evac_rope", () ->
            EntityType.Builder.<EvacRopeEntity>of(EvacRopeEntity::new, MobCategory.MISC)
                .sized(0.9f, 14.0f)
                .clientTrackingRange(32)
                .updateInterval(5)    // довжина розмотування синхронізується через EntityData
                .noSummon()
                .fireImmune()
                .build("evac_rope"));

    /** Невидимий носій гравця, що лізе по мотузці. Оновлення щотіку — інакше їзда ривками. */
    public static final RegistryObject<EntityType<ClimbCarrierEntity>> CLIMB_CARRIER =
        ENTITY_TYPES.register("climb_carrier", () ->
            EntityType.Builder.<ClimbCarrierEntity>of(ClimbCarrierEntity::new, MobCategory.MISC)
                .sized(0.2f, 0.2f)
                .clientTrackingRange(10)
                .updateInterval(1)
                .noSummon()
                .fireImmune()
                .build("climb_carrier"));

    /**
     * Капкан. Хітбокс 0.9×0.3: плаский на вигляд, але достатньо широкий,
     * щоб гравець, який іде повз, справді наступив, і достатньо
     * товстий по висоті, щоб приціл ловив його ломом (ЛКМ). Так само,
     * як у ground_item: занадто низький хітбокс проходить «під»
     * прицілом.
     */
    public static final RegistryObject<EntityType<BearTrapEntity>> BEAR_TRAP =
        ENTITY_TYPES.register("bear_trap", () ->
            EntityType.Builder.<BearTrapEntity>of(BearTrapEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.3f)
                .clientTrackingRange(16)     // 256 блоків; маньяк має бачити свої пастки
                .updateInterval(10)          // змінюється лише SNAPPED — нечасто
                .noSummon()
                .fireImmune()
                .build("bear_trap"));

    /**
     * Електричний дріт — сутність-«тіло» пастки (ТЗ 2.3). Хітбокс
     * маленький і майже не використовується: справжній бокс повертає
     * перевизначений {@code ElectricWireEntity.getBoundingBox()} (AABB
     * уздовж усього відрізка). Тут достатньо формального розміру, щоб
     * EntityType був валідним (нульовий розмір ваніль не приймає).
     */
    public static final RegistryObject<EntityType<ElectricWireEntity>> ELECTRIC_WIRE =
        ENTITY_TYPES.register("electric_wire", () ->
            EntityType.Builder.<ElectricWireEntity>of(ElectricWireEntity::new, MobCategory.MISC)
                .sized(0.2f, 0.2f)
                .clientTrackingRange(16)
                .updateInterval(10)
                .noSummon()
                .fireImmune()
                .build("electric_wire"));

    /**
     * Кріплення електричного дроту (ТЗ 2.3): модель 3×3×4, ставиться по
     * обидва кінці. Хітбокс — приблизно під модель; сутність невразлива
     * й нецільова (приціл іде в САМ дріт, а не в опору).
     */
    public static final RegistryObject<EntityType<ElectricWireMountEntity>> ELECTRIC_WIRE_MOUNT =
        ENTITY_TYPES.register("electric_wire_mount", () ->
            EntityType.Builder.<ElectricWireMountEntity>of(ElectricWireMountEntity::new, MobCategory.MISC)
                .sized(0.9f, 1.2f)
                .clientTrackingRange(16)
                .updateInterval(20)
                .noSummon()
                .fireImmune()
                .build("electric_wire_mount"));

    /**
     * Мотузка-підвіс. Хітбокс як у капкана (0.9×0.3): це «тригер» на підлозі,
     * на який треба наступити. Нитка до стелі малюється рендерером, хітбокса
     * не має (culling-бокс перевизначено в сутності).
     */
    public static final RegistryObject<EntityType<RopeTrapEntity>> ROPE_TRAP =
        ENTITY_TYPES.register("rope_trap", () ->
            EntityType.Builder.<RopeTrapEntity>of(RopeTrapEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.3f)
                .clientTrackingRange(16)
                .updateInterval(10)
                .noSummon()
                .fireImmune()
                .build("rope_trap"));

    /**
     * Димова міна. Хітбокс як у капкана (0.9×0.3): «тригер» на підлозі, на
     * який треба наступити, і ціль для лома. Самої моделі немає — рендерер
     * малює пластину з текстури підлоги під нею.
     */
    public static final RegistryObject<EntityType<SmokeMineEntity>> SMOKE_MINE =
        ENTITY_TYPES.register("smoke_mine", () ->
            EntityType.Builder.<SmokeMineEntity>of(SmokeMineEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.3f)
                .clientTrackingRange(16)
                .updateInterval(10)          // змінюється лише TRIGGERED — нечасто
                .noSummon()
                .fireImmune()
                .build("smoke_mine"));

    /**
     * Шипи. Хітбокс як у димової міни (0.9×0.3): «тригер» на підлозі, на який
     * треба наступити. Знешкодити ломом не можна (сутність не pickable), тож
     * хітбокс лише для спрацювання; шипи, що вискочили, малює Geo-модель.
     */
    public static final RegistryObject<EntityType<SpikeTrapEntity>> SPIKE_TRAP =
        ENTITY_TYPES.register("spike_trap", () ->
            EntityType.Builder.<SpikeTrapEntity>of(SpikeTrapEntity::new, MobCategory.MISC)
                .sized(0.9f, 0.3f)
                .clientTrackingRange(16)
                .updateInterval(10)          // змінюється лише STATE — нечасто
                .noSummon()
                .fireImmune()
                .build("spike_trap"));

    // ── Гаки (див. hooks/) ───────────────────────────────────────────────

    /**
     * Гак. Хітбокс 1.0×3.0: достатньо високий, щоб у нього цілився ПКМ маньяка знизу,
     * і достатньо широкий, щоб не «промахуватись» повз. clientTrackingRange 64 чанки — як у
     * генератора: маньяк, що несе жертву, має бачити вільні гаки якомога далі (реально
     * межу ставить view-distance сервера; далі працює екранна мітка).
     */
    public static final RegistryObject<EntityType<HookEntity>> HOOK =
        ENTITY_TYPES.register("hook", () ->
            EntityType.Builder.<HookEntity>of(HookEntity::new, MobCategory.MISC)
                .sized(1.0f, 3.0f)
                .clientTrackingRange(64)
                .updateInterval(20)   // змінюється лише «зайнятий» — рідко
                .noSummon()
                .fireImmune()
                .build("hook"));

    // ── Сцена перемоги маніяка (див. victory/) ───────────────────────────

    /** Гачок сцени з «опудалом» жертви. Діапазон відстеження великий — камера сцени бачить гачки здалеку. */
    public static final RegistryObject<EntityType<com.log_to_kot.maniacmod.victory.VictoryHookEntity>> VICTORY_HOOK =
        ENTITY_TYPES.register("victory_hook", () ->
            EntityType.Builder.<com.log_to_kot.maniacmod.victory.VictoryHookEntity>of(
                    com.log_to_kot.maniacmod.victory.VictoryHookEntity::new, MobCategory.MISC)
                .sized(1.0f, 1.0f)
                .clientTrackingRange(64)
                .updateInterval(20)
                .noSummon()
                .fireImmune()
                .build("victory_hook"));

    /** Маніяк сцени перемоги: модель архетипу без гравця. */
    public static final RegistryObject<EntityType<com.log_to_kot.maniacmod.victory.VictoryManiacEntity>> VICTORY_MANIAC =
        ENTITY_TYPES.register("victory_maniac", () ->
            EntityType.Builder.<com.log_to_kot.maniacmod.victory.VictoryManiacEntity>of(
                    com.log_to_kot.maniacmod.victory.VictoryManiacEntity::new, MobCategory.MISC)
                .sized(0.8f, 2.0f)
                .clientTrackingRange(64)
                .updateInterval(20)
                .noSummon()
                .fireImmune()
                .build("victory_maniac"));

    /** Гравець (живий — танцює, привид — висить у повітрі) сцени перемоги гравців. */
    public static final RegistryObject<EntityType<com.log_to_kot.maniacmod.victory.VictoryPlayerEntity>> VICTORY_PLAYER =
        ENTITY_TYPES.register("victory_player", () ->
            EntityType.Builder.<com.log_to_kot.maniacmod.victory.VictoryPlayerEntity>of(
                    com.log_to_kot.maniacmod.victory.VictoryPlayerEntity::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(64)
                .updateInterval(20)
                .noSummon()
                .fireImmune()
                .build("victory_player"));

    /**
     * Невидиме сидіння жертви (на плечі маньяка або на гаку). Оновлення щотіку — інакше жертва на
     * плечі їхала б ривками. Діапазон як у гравця: жертву бачать там, де бачать маньяка.
     */
    public static final RegistryObject<EntityType<VictimSeatEntity>> VICTIM_SEAT =
        ENTITY_TYPES.register("victim_seat", () ->
            EntityType.Builder.<VictimSeatEntity>of(VictimSeatEntity::new, MobCategory.MISC)
                .sized(0.2f, 0.2f)
                .clientTrackingRange(32)
                .updateInterval(1)
                .noSummon()
                .fireImmune()
                .build("victim_seat"));

    // ── Барикади (див. barricade/) ───────────────────────────────────────

    /**
     * Гнучка барикада: одна сутність на ВСЮ барикаду. Хітбокс формальний — справжню геометрію
     * (дошки) малює рендерер і рахує {@code BarricadeGeometry}, а приціл/промінь іде по дошках,
     * не по боксу. Діапазон видимості 160 блоків: барикада — елемент карти, її має бути видно здалеку.
     */
    public static final RegistryObject<EntityType<BarricadeEntity>> BARRICADE =
        ENTITY_TYPES.register("barricade", () ->
            EntityType.Builder.<BarricadeEntity>of(BarricadeEntity::new, MobCategory.MISC)
                .sized(0.2f, 0.2f)
                .clientTrackingRange(10)
                .updateInterval(60)   // дані дощок не змінюються після створення
                .noSummon()
                .fireImmune()
                .build("barricade"));

    /**
     * Невидимий кубик-колайдер уздовж дошки: ванільна колізія сутностей — це AABB, тож діагональну
     * дошку замінює ланцюжок маленьких кубиків (див. {@code BarricadeColliderEntity}).
     */
    public static final RegistryObject<EntityType<BarricadeColliderEntity>> BARRICADE_COLLIDER =
        ENTITY_TYPES.register("barricade_collider", () ->
            EntityType.Builder.<BarricadeColliderEntity>of(BarricadeColliderEntity::new, MobCategory.MISC)
                .sized((float) com.log_to_kot.maniacmod.barricade.BarricadeGeometry.COLLIDER_SIZE,
                       (float) com.log_to_kot.maniacmod.barricade.BarricadeGeometry.COLLIDER_SIZE)
                .clientTrackingRange(6)
                .updateInterval(600)  // нерухомий — оновлень позиції не потребує
                .noSummon()
                .fireImmune()
                .build("barricade_collider"));

    // TODO(міграція maniacs): MANIAC EntityType переїжджає сюди разом
    // з модулем маньяків.

    private ModEntityTypes() {}
}

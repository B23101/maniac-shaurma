package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.entity.GeneratorEntity;
import com.log_to_kot.maniacmod.entity.GroundItemEntity;
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

    // TODO(міграція maniacs): MANIAC EntityType переїжджає сюди разом
    // з модулем маньяків.

    private ModEntityTypes() {}
}

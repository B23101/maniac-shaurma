package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
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
 * MANIAC-сутність з'явиться тут, коли буде перенесено модуль maniacs.
 */
public final class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ManiacMod.MOD_ID);

    public static final RegistryObject<EntityType<GroundItemEntity>> GROUND_ITEM =
        ENTITY_TYPES.register("ground_item", () ->
            EntityType.Builder.<GroundItemEntity>of(GroundItemEntity::new, MobCategory.MISC)
                .sized(0.5f, 0.3f)
                .clientTrackingRange(8)
                .updateInterval(20)   // предмет лежить нерухомо — часті апдейти не потрібні
                .noSummon()
                .fireImmune()
                .build("ground_item"));

    // TODO(міграція maniacs): MANIAC EntityType переїжджає сюди разом
    // з модулем маньяків.

    private ModEntityTypes() {}
}

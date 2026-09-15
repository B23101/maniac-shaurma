package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ManiacMod.MOD_ID);

    public static final RegistryObject<EntityType<ManiacEntity>> MANIAC =
        ENTITY_TYPES.register("maniac", () ->
            EntityType.Builder.<ManiacEntity>of(ManiacEntity::new, MobCategory.MONSTER)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(64)
                .build("maniac")
        );

    /** Lying player-skin corpse entity for dead survivors. */
    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE =
        ENTITY_TYPES.register("corpse", () ->
            EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                .sized(1.8f, 0.3f)
                .clientTrackingRange(64)
                .noSummon()
                .build("corpse")
        );
}

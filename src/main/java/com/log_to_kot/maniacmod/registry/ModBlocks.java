package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Блоки мода.
 *
 * ── Генератор більше не блок ─────────────────────────────────────────
 * Раніше тут реєструвався {@code GeneratorBlock} — блок, який
 * картобудівник мусив ставити руками на кожній карті заздалегідь. Тепер
 * генератор — сутність ({@link com.log_to_kot.maniacmod.entity.GeneratorEntity},
 * реєстрація в {@link ModEntityTypes#GENERATOR}), яка сама з'являється
 * при застосуванні плану спавну — див. {@code MatchOrchestrator.spawnGeneratorEntity}.
 * Реєстри тут лишаються порожніми заготовками під майбутні звичайні
 * блоки мода (не всі елементи карти доречно робити сутностями).
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, ManiacMod.MOD_ID);

    public static final DeferredRegister<Item> BLOCK_ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, ManiacMod.MOD_ID);

    private ModBlocks() {}
}

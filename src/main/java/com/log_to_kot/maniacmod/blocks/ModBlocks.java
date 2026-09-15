package com.log_to_kot.maniacmod.blocks;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, ManiacMod.MOD_ID);

    public static final DeferredRegister<Item> BLOCK_ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, ManiacMod.MOD_ID);

    // ── Генератор ──────────────────────────────────────────────────────────
    public static final RegistryObject<Block> GENERATOR =
        BLOCKS.register("generator", GeneratorBlock::new);

    public static final RegistryObject<Item> GENERATOR_ITEM =
        BLOCK_ITEMS.register("generator",
            () -> new BlockItem(GENERATOR.get(), new Item.Properties().stacksTo(64)));
}

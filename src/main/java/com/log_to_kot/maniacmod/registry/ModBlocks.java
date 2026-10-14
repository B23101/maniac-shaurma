package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.blocks.BoardsBlock;
import com.log_to_kot.maniacmod.blocks.BoardsBlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Блоки мода.
 *
 * ── Генератор більше не блок ─────────────────────────────────────────
 * Раніше тут реєструвався {@code GeneratorBlock} — блок, який
 * картобудівник мусив ставити руками на кожній карті заздалегідь. Тепер
 * генератор — сутність ({@link com.log_to_kot.maniacmod.entity.GeneratorEntity},
 * реєстрація в {@link ModEntityTypes#GENERATOR}), яка сама з'являється
 * при застосуванні плану спавну — див. {@code MatchOrchestrator.spawnGeneratorEntity}.
 * Реєстри лишаються заготовкою під звичайні блоки мода (не всі елементи карти доречно
 * робити сутностями) — зараз тут один блок, {@link #BOARDS}.
 */
public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
        DeferredRegister.create(ForgeRegistries.BLOCKS, ManiacMod.MOD_ID);

    public static final DeferredRegister<Item> BLOCK_ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, ManiacMod.MOD_ID);

    /** Подвійний блок «дошки»: фіксована барикада, яку виживі ламають ломом. */
    public static final RegistryObject<Block> BOARDS = BLOCKS.register("boards", BoardsBlock::new);

    public static final RegistryObject<Item> BOARDS_ITEM = BLOCK_ITEMS.register("boards",
        () -> new BoardsBlockItem(BOARDS.get(), new Item.Properties()));

    private ModBlocks() {}
}

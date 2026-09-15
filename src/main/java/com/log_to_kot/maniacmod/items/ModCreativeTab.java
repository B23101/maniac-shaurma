package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.blocks.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ManiacMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MANIAC_TAB = TABS.register("maniac_tab",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.maniacmod"))
            .icon(() -> new ItemStack(ModItems.CHUCKY_KNIFE.get()))
            .displayItems((params, output) -> {
                // ── Предмети виживаючих ──
                output.accept(ModItems.MEDKIT.get());
                output.accept(ModItems.WRENCH.get());
                output.accept(ModItems.SCREWDRIVER.get());
                output.accept(ModItems.SCISSORS.get());
                output.accept(ModItems.BAT.get());
                output.accept(ModItems.CROWBAR.get());
                output.accept(ModItems.TASER.get());
                output.accept(ModItems.DEFIBRILLATOR.get());
                // ── Зброя маньяків ──
                output.accept(ModItems.CHUCKY_KNIFE.get());
                output.accept(ModItems.SLENDER_TENTACLE.get());
                // ── Пастки маньяка ──
                output.accept(ModItems.BEAR_TRAP.get());
                output.accept(ModItems.ROPE.get());
                output.accept(ModItems.ELECTRIC_WIRE.get());
                output.accept(ModItems.MINE.get());
                // ── Блоки ──
                output.accept(ModBlocks.GENERATOR_ITEM.get());
            })
            .build()
    );
}

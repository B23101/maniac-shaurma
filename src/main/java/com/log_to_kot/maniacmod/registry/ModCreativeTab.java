package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.ItemRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Творча вкладка. Наповнюється з {@link ItemRegistry} автоматично —
 * жодного ручного списку, який може розійтися з реальним набором
 * предметів (як було в v3 ModCreativeTab).
 *
 * ── Генератор тут більше не з'являється ──────────────────────────────
 * Раніше вкладка додавала {@code ModBlocks.GENERATOR_ITEM} — предмет,
 * яким картобудівник клав блок генератора на карту вручну. Тепер
 * генератор — сутність ({@link com.log_to_kot.maniacmod.entity.GeneratorEntity}),
 * що сама спавниться при застосуванні плану матчу; класти щось
 * креативним предметом більше не потрібно й нема чим (сутність не має
 * відповідного BlockItem/SpawnEggItem).
 */
public final class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ManiacMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MANIAC_TAB = TABS.register("maniac_tab",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.maniacmod"))
            .icon(ModCreativeTab::tabIcon)
            .displayItems((params, output) -> {
                // Порядок груп фіксований, усередині групи — порядок реєстрації.
                for (ItemRegistry.Group group : ItemRegistry.Group.values()) {
                    ItemRegistry.all().values().stream()
                        .filter(e -> e.group() == group)
                        .forEach(e -> output.accept(ModItems.get(e.id()).get()));
                }
            })
            .build());

    private ModCreativeTab() {}

    /**
     * Іконка — перший зареєстрований предмет. v3 хардкодив CHUCKY_KNIFE,
     * тому вкладка падала б, якби цей предмет прибрали; тут fallback —
     * порожній стек, а не жорстко зашитий предмет, якого могло не бути.
     */
    private static ItemStack tabIcon() {
        return ModItems.all().values().stream().findFirst()
            .map(obj -> new ItemStack(obj.get()))
            .orElse(ItemStack.EMPTY);
    }
}

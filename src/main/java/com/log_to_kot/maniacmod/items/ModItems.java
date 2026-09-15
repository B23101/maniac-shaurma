package com.log_to_kot.maniacmod.items;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, ManiacMod.MOD_ID);

    // ── SURVIVOR ITEMS ──────────────────────────────────────────
    public static final RegistryObject<Item> MEDKIT =
        ITEMS.register("medkit",      MedkitItem::new);

    public static final RegistryObject<Item> WRENCH =
        ITEMS.register("wrench",      WrenchItem::new);

    public static final RegistryObject<Item> SCREWDRIVER =
        ITEMS.register("screwdriver", ScrewdriverItem::new);

    public static final RegistryObject<Item> SCISSORS =
        ITEMS.register("scissors",    ScissorsItem::new);

    public static final RegistryObject<Item> BAT =
        ITEMS.register("bat",         BatItem::new);

    public static final RegistryObject<Item> CROWBAR =
        ITEMS.register("crowbar",     CrowbarItem::new);

    public static final RegistryObject<Item> TASER =
        ITEMS.register("taser",       TaserItem::new);

    /**
     * Дефібрилятор — воскрешає мертвого виживаючого протягом вікна 3 хвилин.
     * Рятівник витрачає 1 ❤. Воскрешений отримує 1 ❤.
     */
    public static final RegistryObject<Item> DEFIBRILLATOR =
        ITEMS.register("defibrillator", DefibrillatorItem::new);

    // ── MANIAC WEAPONS — у кожного маньяка своя зброя ─────────────
    /** Ніж Чакі: 6 сек кулдаун */
    public static final RegistryObject<Item> CHUCKY_KNIFE =
        ITEMS.register("chucky_knife", ChuckyKnifeItem::new);

    /** Щупальце Слендермена: 10 сек кулдаун */
    public static final RegistryObject<Item> SLENDER_TENTACLE =
        ITEMS.register("slender_tentacle", SlenderTentacleItem::new);

    public static final RegistryObject<Item> BEAR_TRAP =
        ITEMS.register("bear_trap",     BearTrapItem::new);

    public static final RegistryObject<Item> ROPE =
        ITEMS.register("rope",          RopeItem::new);

    public static final RegistryObject<Item> ELECTRIC_WIRE =
        ITEMS.register("electric_wire", ElectricWireItem::new);

    /** Міна: невидима пастка, -1 ❤ + сповільнення, знешкоджується викруткою */
    public static final RegistryObject<Item> MINE =
        ITEMS.register("mine", MineItem::new);
}

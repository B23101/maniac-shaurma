package com.log_to_kot.maniacmod.items;

import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Перелік усіх предметів мода в одному місці.
 *
 * НАВІЩО окремо від registry/ModItems: у v3 додати предмет означало
 * правки у ТРЬОХ файлах — ModItems (реєстрація), ModCreativeTab
 * (вкладка), ItemImplementations (клас). Третій рядок регулярно
 * забували, і предмет був у грі, але не у вкладці.
 *
 * Тепер один рядок тут → предмет автоматично з'являється і в
 * DeferredRegister, і у творчій вкладці, у тому самому порядку.
 *
 * Додати предмет:
 *   1. Створити клас XxxItem extends ItemArchetype у цьому пакеті.
 *   2. Дописати register("xxx", XxxItem::new, Group.SURVIVOR) нижче.
 */
public final class ItemRegistry {

    /** Куди предмет потрапляє у творчій вкладці. */
    public enum Group { SURVIVOR, MANIAC_WEAPON, TRAP }

    public record Entry(String id, Supplier<Item> factory, Group group) {}

    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    static {
        // ── Предмети виживих ────────────────────────────────────────────
        register("medkit", MedkitItem::new, Group.SURVIVOR);
        // TODO(міграція items): у міру перенесення з v3 ItemImplementations
        //   register("flashlight",   FlashlightItem::new,   Group.SURVIVOR);
        //   register("crowbar",      CrowbarItem::new,      Group.SURVIVOR);
        //   register("bat",          BatItem::new,          Group.SURVIVOR);
        //   register("splint",       SplintItem::new,       Group.SURVIVOR);  // Шина
        //   register("screwdriver",  ScrewdriverItem::new,  Group.SURVIVOR);
        //   register("fuel_canister",FuelCanisterItem::new, Group.SURVIVOR);  // Бензин
        //   register("scissors",     ScissorsItem::new,     Group.SURVIVOR);
        //   register("adrenaline",   AdrenalineItem::new,   Group.SURVIVOR);

        // ── Зброя маньяків ──────────────────────────────────────────────
        // TODO(міграція maniacs):
        //   register("chucky_knife",     ChuckyKnifeItem::new,     Group.MANIAC_WEAPON);
        //   register("slender_tentacle", SlenderTentacleItem::new, Group.MANIAC_WEAPON);

        // ── Пастки ──────────────────────────────────────────────────────
        // TODO(міграція traps):
        //   register("bear_trap",     BearTrapItem::new,     Group.TRAP);
        //   register("rope",          RopeItem::new,         Group.TRAP);
        //   register("electric_wire", ElectricWireItem::new, Group.TRAP);
        //   register("spikes",        SpikesItem::new,       Group.TRAP);
    }

    private ItemRegistry() {}

    public static void register(String id, Supplier<Item> factory, Group group) {
        if (BY_ID.putIfAbsent(id, new Entry(id, factory, group)) != null) {
            throw new IllegalStateException("Предмет '" + id + "' зареєстровано двічі.");
        }
    }

    /** Усі записи в порядку реєстрації — саме в цьому порядку вони стають у вкладці. */
    public static Map<String, Entry> all() {
        return Collections.unmodifiableMap(BY_ID);
    }
}

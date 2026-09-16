package com.log_to_kot.maniacmod.registry;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.items.ItemRegistry;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forge-реєстрація предметів. Сам СПИСОК живе в
 * {@link ItemRegistry} — тут лише механіка DeferredRegister.
 *
 * v3-еквівалент: items/ModItems.java, де кожен предмет був окремою
 * константою. Різниця в тому, що там список предметів і список
 * творчої вкладки були двома незалежними списками, які треба було
 * тримати синхронними вручну.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, ManiacMod.MOD_ID);

    private static final Map<String, RegistryObject<Item>> REGISTERED = new LinkedHashMap<>();

    static {
        for (ItemRegistry.Entry entry : ItemRegistry.all().values()) {
            REGISTERED.put(entry.id(), ITEMS.register(entry.id(), entry.factory()));
        }
    }

    private ModItems() {}

    /**
     * Предмет за id. Кидає зрозумілу помилку замість NPE, якщо клас
     * предмета ще не перенесено з v3 — під час поетапної міграції це
     * найчастіша причина падіння.
     */
    public static RegistryObject<Item> get(String id) {
        RegistryObject<Item> found = REGISTERED.get(id);
        if (found == null) throw new IllegalArgumentException(
            "Предмет '" + id + "' не зареєстровано. Додай рядок у ItemRegistry.");
        return found;
    }

    public static boolean exists(String id) {
        return REGISTERED.containsKey(id);
    }

    public static Map<String, RegistryObject<Item>> all() {
        return Collections.unmodifiableMap(REGISTERED);
    }
}

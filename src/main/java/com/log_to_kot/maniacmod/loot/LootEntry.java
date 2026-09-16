package com.log_to_kot.maniacmod.loot;

import net.minecraft.world.item.Item;

/**
 * Один запис таблиці луту: який предмет, скільки штук, з яким шансом.
 *
 * v3-еквівалент: вкладений клас ItemSpawnZone.LootEntry — він жив
 * усередині класу зони, тому таблицю луту не можна було використати
 * ніде, крім зони. Тепер це самостійний тип: та сама таблиця годиться
 * і для точкового спавну, і для скрині, і для дропу з трупа.
 */
public record LootEntry(Item item, int count, float chance) {

    public LootEntry {
        if (count < 1) throw new IllegalArgumentException("count має бути ≥ 1");
        if (chance < 0f || chance > 1f) throw new IllegalArgumentException("chance має бути 0.0–1.0");
    }
}

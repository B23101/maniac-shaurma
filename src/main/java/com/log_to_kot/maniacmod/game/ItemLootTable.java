package com.log_to_kot.maniacmod.game;

import com.log_to_kot.maniacmod.items.ModItems;
import com.log_to_kot.maniacmod.game.ItemSpawnZone.LootEntry;
import java.util.List;

public class ItemLootTable {

    public static List<LootEntry> build() {
        return List.of(
            new LootEntry(ModItems.WRENCH.get(),        1, 0.90f),
            new LootEntry(ModItems.SCREWDRIVER.get(),   1, 0.70f),
            new LootEntry(ModItems.SCISSORS.get(),      1, 0.80f),
            new LootEntry(ModItems.CROWBAR.get(),       1, 0.75f),
            new LootEntry(ModItems.MEDKIT.get(),        1, 0.30f),
            new LootEntry(ModItems.BAT.get(),           1, 0.35f),
            new LootEntry(ModItems.TASER.get(),         1, 0.25f),
            new LootEntry(ModItems.DEFIBRILLATOR.get(), 1, 0.20f)
        );
    }

    public static String summary() {
        return "§7Шанси предметів:" +
            "\n  §eГайочний ключ §f90%" +
            "\n  §eНожниці §f80%" +
            "\n  §eЛом §f75%" +
            "\n  §eВикрутка §f70%" +
            "\n  §6Бита §f35%" +
            "\n  §cАптечка §f30%" +
            "\n  §6Шокер §f25%" +
            "\n  §d§lДефібрилятор §f20% §7(воскресіння!)";
    }
}

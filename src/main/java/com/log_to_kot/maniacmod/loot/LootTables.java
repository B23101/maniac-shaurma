package com.log_to_kot.maniacmod.loot;

import com.log_to_kot.maniacmod.registry.ModItems;

import java.util.List;

/**
 * Готові таблиці луту мода.
 *
 * <h3>Чому методи, а не {@code static final LootTable}</h3>
 * {@code ModItems.get(..).get()} працює лише коли Forge уже заморозив
 * реєстри (після {@code RegisterEvent}). Статичне поле, що
 * ініціалізується під час завантаження класу, могло б виконатись
 * раніше — і впало б у {@code NullPointerException} із незрозумілим
 * стеком. Метод викликається на старті матчу, коли сервер піднято, тож
 * ця проблема неможлива за побудовою.
 *
 * <p>Таблиця збирається заново на кожен виклик — це кілька
 * об'єктів раз на матч, а не гаряча гілка.</p>
 */
public final class LootTables {

    private LootTables() {}

    /**
     * Що лежить на ITEM-точках карти на початку матчу.
     *
     * <p>Каністра з бензином (вага 1.0, без неї стадія FUEL генератора
     * не проходиться) удвічі частіша за аптечку й шину (вага 0.5 кожна)
     * — саме через ITEM-точки виживий добуває пальне для генератора, а
     * лікування й ремонт ноги радше доповнюють, тому не мають
     * переважати. Додати предмет = один рядок
     * {@code new LootEntry(...)} нижче; вага — це {@code chance}
     * (див. {@link LootTable}).</p>
     */
    public static LootTable itemPoints() {
        return new LootTable(List.of(
            new LootEntry(ModItems.get("fuel_canister").get(), 1, 1.0f),
            new LootEntry(ModItems.get("medkit").get(), 1, 0.5f),
            new LootEntry(ModItems.get("splint").get(), 1, 0.5f),
            // Лом потрібен лише тоді, коли хтось потрапив у капкан, тож він
            // рідкісніший за пальне. Свіжий лом має 100% (немає тега = повна
            // міцність, див. CrowbarItem#getDurability) — окремо не ініціалізується.
            new LootEntry(ModItems.get("crowbar").get(), 1, 0.5f)
            // Нові предмети — сюди, тим самим рядком.
        ));
    }
}

package com.log_to_kot.maniacmod.loot;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Таблиця луту: з якого набору {@link LootEntry} обирається предмет
 * для однієї ITEM-точки.
 *
 * <h3>Як читати {@link LootEntry#chance()}</h3>
 * Тут {@code chance} — це ВАГА, а не незалежна ймовірність. Точка
 * одна, предмет на ній один, тож питання «що саме?» — це вибір з
 * набору за вагами (0.6 і 0.2 означає «втричі частіше», а не «60% і
 * 20% незалежно»). Записи з {@code chance = 0} ніколи не випадають —
 * зручно вимкнути предмет, не видаляючи рядок.
 *
 * <h3>Що додавати сюди</h3>
 * Кожен новий предмет, що має лежати на карті, — один рядок у
 * {@link #DEFAULT}. Перший (і поки єдиний) — каністра з бензином,
 * без якої стадія FUEL генератора не проходиться.
 *
 * <p><b>Чому статичний список, а не файл конфігу.</b> Предмети
 * реєструє код ({@code ItemRegistry}); дублювати їх перелік у yml —
 * це два джерела правди, ті самі «мертві налаштування», яких схема
 * конфігу не допускає. Коли таблиць стане кілька (по типах точок) —
 * вони з'являться тут же окремими константами.</p>
 */
public final class LootTable {

    private final List<LootEntry> entries;
    private final float totalWeight;

    public LootTable(List<LootEntry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Таблиця луту не може бути порожньою.");
        }
        this.entries = List.copyOf(entries);
        float sum = 0f;
        for (LootEntry entry : this.entries) sum += entry.chance();
        if (sum <= 0f) {
            throw new IllegalArgumentException(
                "Хоча б один запис таблиці луту має мати chance > 0.");
        }
        this.totalWeight = sum;
    }

    /**
     * Кидок за вагами. Повертає свіжий стек (кількість із запису) — щоразу
     * НОВИЙ екземпляр, бо викликач віддає його сутності, яка тримає його
     * довго; спільний екземпляр між точками означав би, що зміна NBT
     * (заряд каністри) в одному предметі змінила б усі.
     */
    public ItemStack roll(Random rng) {
        float pick = rng.nextFloat() * totalWeight;
        float acc = 0f;
        LootEntry chosen = entries.get(entries.size() - 1);
        for (LootEntry entry : entries) {
            acc += entry.chance();
            if (pick < acc) {
                chosen = entry;
                break;
            }
        }
        return new ItemStack(chosen.item(), chosen.count());
    }

    public List<LootEntry> entries() {
        return entries;
    }
}

package com.log_to_kot.maniacmod.loot;

import dev.shaurmalib.forge.inventory.InventorySlotAllocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Правило «у який слот класти підібраний предмет».
 *
 * <p>Живе окремо від {@code GroundItemEntity}, бо сутність не повинна
 * знати правил слотів (їх знає {@link InventorySlotAllocation}), а
 * правило слотів не повинно знати, що таке сутність. Сутність питає
 * «куди?» — цей клас відповідає, і сам же кладе.</p>
 *
 * <h3>Порядок (дослівно з вимог)</h3>
 * <ol>
 *   <li><b>Вибраний слот порожній</b> → предмет іде саме в нього.
 *       Гравець тримає порожню руку, тикає ПКМ — і предмет одразу
 *       опиняється в руці.</li>
 *   <li><b>Вибраний слот зайнятий</b> (або недозволений) → перший
 *       вільний ДОЗВОЛЕНИЙ слот, за зростанням індексу.</li>
 *   <li><b>Вільних немає</b> → {@link Result#NO_FREE_SLOT}. Нічого не
 *       змінюється, предмет лишається лежати. Викликач (взаємодія ПКМ)
 *       може після цього окремо вирішити застосувати «фізику обміну» —
 *       {@link #swapSelected} — і викинути вибраний предмет замість
 *       підібраного; сам {@link #place} цього не робить, бо викликається
 *       і з місць, де обмін не доречний (наприклад майбутній
 *       автопідбір).</li>
 * </ol>
 *
 * <h3>Обмін «повні слоти» ({@link #swapSelected})</h3>
 * Коли класти нікуди, гравець і так стоїть із чимось у вибраному слоті
 * (порожніх рук при {@code NO_FREE_SLOT} не буває — інакше спрацював
 * би пункт 1). ПКМ по предмету на землі в цьому випадку викидає те, що
 * зараз у руці, у світ (та сама дуга, що клавіша Q —
 * {@code GroundItemSpawner.throwFrom}), і кладе на звільнене місце
 * предмет, по якому клікнули — гравець «міняється» з землею, а не
 * просто впирається в повний інвентар.
 *
 * <h3>Чому не {@code Inventory#add} і не {@code addToAllowedSlots}</h3>
 * Ванільний {@code add} кладе у БУДЬ-ЯКИЙ вільний слот (9..35, броня) —
 * гравець з 4 слотами «підібрав» би предмет у слот, якого не бачить.
 * {@code InventorySlotAllocation.addToAllowedSlots} лише в дозволені,
 * але ЗЛИВАЄ предмет зі стеком тієї самої речі й не знає про «вибраний
 * слот». Тут потрібне саме контрольоване рішення «в який слот», тож
 * використовуємо лише {@link InventorySlotAllocation#isSlotAllowed}.
 *
 * <p><b>Ніякого злиття стеків навмисно.</b> Предмет-сутність несе
 * власний NBT (заряд каністри тощо); зливати його з іншим стеком
 * означало б стерти або підмінити цей NBT. Тому підбирання завжди
 * кладе в ПОРОЖНІЙ слот.</p>
 *
 * <h3>Гравець без обмежень</h3>
 * Якщо {@link InventorySlotAllocation#isRestricted} = false (креатив за
 * політикою EXEMPT, лобі до призначення ролі) — усі 9 слотів хотбару
 * дозволені, і правило те саме. Ми свідомо НЕ ліземо в 9..40:
 * підібраний предмет має бути видимий гравцю.
 */
public final class GroundItemPickup {

    /** Результат спроби підібрати. */
    public enum Result {
        /** Предмет лежить у вибраному слоті. */
        PLACED_SELECTED,
        /** Вибраний був зайнятий — предмет у першому вільному. */
        PLACED_FREE,
        /** Немає жодного вільного дозволеного слота. Нічого не змінено. */
        NO_FREE_SLOT,
        /**
         * Місця не було, тож старий предмет із вибраного слота викинуто
         * у світ, а новий покладено на його місце. Лише результат
         * {@link #swapSelected}, {@link #place} його не повертає.
         */
        SWAPPED
    }

    /** Скільки слотів хотбару існує у Minecraft. Слоти 9+ гравець не бачить. */
    private static final int HOTBAR_SIZE = 9;

    private GroundItemPickup() {}

    /**
     * Кладе стек у інвентар за правилом вище.
     *
     * <p><b>Мутує інвентар лише при успіху.</b> При
     * {@link Result#NO_FREE_SLOT} інвентар не змінений — викликач може
     * спокійно лишити сутність у світі.</p>
     *
     * @param player гравець (серверний)
     * @param stack  що класти. НЕ модифікується; у слот іде {@code copy()},
     *               тож викликач сам вирішує, коли знищити сутність.
     */
    public static Result place(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return Result.NO_FREE_SLOT;

        // Швидкий вихід через ту саму перевірку, що й hasRoom(): якщо
        // взагалі нема куди класти — не варто окремо перевіряти вибраний
        // і перебирати хотбар, помилка однакова в обох випадках.
        if (!hasRoom(player)) return Result.NO_FREE_SLOT;

        Inventory inventory = player.getInventory();
        int selected = inventory.selected;

        // 1. Вибраний слот: порожній І дозволений.
        if (isUsable(player, inventory, selected)) {
            inventory.setItem(selected, stack.copy());
            inventory.setChanged();
            return Result.PLACED_SELECTED;
        }

        // 2. Перший вільний дозволений. hasRoom() вище гарантує, що він є.
        int free = firstFreeSlot(player, inventory);
        inventory.setItem(free, stack.copy());
        inventory.setChanged();
        return Result.PLACED_FREE;
    }

    /**
     * Чи є куди покласти — БЕЗ мутації.
     *
     * <p>Викликається на початку {@link #place} як єдина перевірка
     * «є місце?»: так {@code place} і {@code hasRoom} завжди згодні
     * одне з одним — немає двох окремих реалізацій того самого
     * правила, які могли б розійтися. Придатний і окремо — там, де
     * відповідь потрібна наперед, без спроби покласти (наприклад,
     * підказка «немає слотів» ДО кліка, а не лише після).</p>
     */
    public static boolean hasRoom(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        return isUsable(player, inventory, inventory.selected)
            || firstFreeSlot(player, inventory) >= 0;
    }

    /**
     * «Фізика обміну»: усі дозволені слоти зайняті, тож замінюємо вміст
     * ВИБРАНОГО слота (руки) новим предметом, а старий — викидаємо у
     * світ тією самою дугою, що Q ({@code GroundItemSpawner.throwFrom}).
     *
     * <p>Викликається окремо від {@link #place} тим кодом, що обробляє
     * ПКМ по предмету на землі, лише коли {@link #place} щойно повернув
     * {@link Result#NO_FREE_SLOT} — так порядок дій лишається явним у
     * викликача: спершу звичайна спроба покласти, і тільки якщо вона
     * неможлива — обмін.</p>
     *
     * <p>Вибраний слот при {@code NO_FREE_SLOT} гарантовано не порожній
     * (інакше {@link #isUsable} прийняв би його ще на кроці 1 у
     * {@link #place}) і гарантовано в межах хотбару ({@code inventory.selected}
     * — завжди 0-8 у ванілі), тож єдине, що тут реально перевіряється, —
     * чи цей конкретний слот дозволений політикою (за замовчуванням так,
     * але обмежений гравець міг мати вибраним слот поза дозволеним
     * діапазоном — тоді міняти нема на що, і виклик відмовляє).</p>
     *
     * @param player гравець (серверний) — потрібен і для інвентаря, і
     *               щоб викинути старий стек за його поглядом
     * @param stack  новий предмет, який кладемо на місце викинутого
     * @return {@link Result#SWAPPED}, якщо обмін відбувся, інакше знову
     *         {@link Result#NO_FREE_SLOT} (вибраний слот не дозволений —
     *         міняти нема на що; інвентар не змінено)
     */
    public static Result swapSelected(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return Result.NO_FREE_SLOT;

        Inventory inventory = player.getInventory();
        int selected = inventory.selected;
        if (selected < 0 || selected >= HOTBAR_SIZE) return Result.NO_FREE_SLOT;
        if (!InventorySlotAllocation.isSlotAllowed(player, selected)) return Result.NO_FREE_SLOT;

        ItemStack held = inventory.getItem(selected);
        if (held.isEmpty()) return Result.NO_FREE_SLOT; // не мало статись — place() уже забрав би цей випадок

        // Спершу кладемо новий предмет, потім кидаємо старий: якщо кидок
        // у світ раптом не вдасться (світ не прийняв сутність), інвентар
        // гравця однаково лишається коректним, а не порожнім слотом.
        inventory.setItem(selected, stack.copy());
        inventory.setChanged();
        GroundItemSpawner.throwFrom(player, held);
        return Result.SWAPPED;
    }

    // ── Внутрішнє ────────────────────────────────────────────────────────

    /** Слот у межах хотбару, дозволений політикою слотів і порожній. */
    private static boolean isUsable(ServerPlayer player, Inventory inventory, int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) return false;
        if (!InventorySlotAllocation.isSlotAllowed(player, slot)) return false;
        return inventory.getItem(slot).isEmpty();
    }

    /** Найменший індекс вільного дозволеного слота, або -1. */
    private static int firstFreeSlot(ServerPlayer player, Inventory inventory) {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (isUsable(player, inventory, slot)) return slot;
        }
        return -1;
    }
}

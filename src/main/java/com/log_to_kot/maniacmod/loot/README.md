# loot — предмети на землі

Предмет на землі — це не ванільний `ItemEntity`, а власна сутність
`entity/GroundItemEntity`: не зникає сама, несе повний `ItemStack`
(id + NBT), має «сплячу» фізику (падає/котиться, потім засинає, поки
не зникне опора) і підбирається ПКМ без Shift. Малює її
`client/renderer/GroundItemRenderer` як HEAD-display.

## Файли

- `LootEntry` — запис таблиці: предмет, кількість, вага (`chance`)
- `LootTable` — набір `LootEntry`, кидок за вагами (`roll`)
- `LootTables` — готові таблиці мода; `itemPoints()` — що лежить на
  ITEM-точках карти на старті матчу
- `LootModule` — розкладає `LootTables.itemPoints()` по задіяних
  точках плану (`MatchOrchestrator.applySpawnPlan`)
- `GroundItemPlacement` — позиція + випадковий поворот одного
  предмета при спавні на карті (кидок гравця рахує свій поворот
  окремо, у `GroundItemSpawner.throwFrom`)
- `GroundItemSpawner` — ЄДИНЕ місце, де створюється
  `GroundItemEntity`: спавн на карті (`spawnOnMap`) і кидок гравця Q
  (`throwFrom`)
- `GroundItemPickup` — куди класти підібраний стек: вибраний слот, якщо
  порожній → перший вільний дозволений → «Немає вільних слотів»
  (`InventorySlotAllocation.isSlotAllowed` з shaurma-lib)

## Поворот

- `GroundItemPlacement.rotationX` — випадковий 0–360, задається при
  спавні на карті й не змінюється — предмет лежить так, як «впав»
- `GroundItemEntity.ROTATION_Y` — завжди 0

Обидва рендерить `GroundItemRenderer` (`Axis.XP`/`Axis.YP`). Якщо
предмет виглядає повернутим не тим боком — питання геометрії
конкретної моделі, а не цього коду: правиться в моделі предмета, не
тут.

## Хто ще бере участь (поза цим пакетом)

- `server/GroundItemHooks` — перехоплює `ItemTossEvent` (Q), скасовує
  ванільний дроп і кладе стек у `GroundItemSpawner.throwFrom`
- `core/match/InventoryAllocationModule` — знімає блокування Q
  (`setItemDropBlocked(false)`) для виживого після кожного
  призначення слотів
- `client/overlay/hint/GroundItemHintOverlay` — назва предмета і
  «ПКМ — взяти» над прицілом; чесно ховає другий рядок, якщо сервер
  однаково відмовить (немає дозволених слотів, лежачий/непритомний
  стан, фаза не дозволяє)
- `net/s2c/loot/GroundItemVisualSettingsPacket` — розсилає
  `loot.sparkleEnabled`: блиск малює клієнт, а конфіг лежить лише на
  сервері, тому потрібен пакет, не пряме читання файлу

## Прибирання

Кожна сутність реєструється в `MatchRuntimeRegistry` при створенні,
тож `MatchOrchestrator.reset()` знищує всі лежачі предмети сам —
окремого прибирання лут-код не робить (друге джерело правди).

## Відомі межі

- Підбір ловить лише хотбар (0–8). Це навмисно — див. докстрінг
  `GroundItemPickup`: підібраний предмет має бути видимий гравцю, а не
  зникати в основному інвентарі поза екраном.
- Предмети маньяка й пастки (`ItemRegistry.Group.MANIAC_WEAPON`,
  `Group.TRAP`) через цю систему не спавняться і не підбираються —
  вона обслуговує лише `Group.SURVIVOR`.

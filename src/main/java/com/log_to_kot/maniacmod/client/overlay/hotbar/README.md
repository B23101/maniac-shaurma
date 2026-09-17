# client/overlay/hotbar — власний рендер хотбару

shaurma-lib уже ховає ванільний хотбар і має свій запасний рендерер
(`InventorySlotAllocationClientHooks.renderAllocatedHotbar` — слот
20×20 по центру знизу), коли жоден мод не підключив власний через
`setCustomHotbarRenderer(...)`. Тут — саме таке підключення: мод
малює хотбар сам, бібліотека лише постачає список дозволених слотів
(`allowedSlots`, вже відфільтрований `InventorySlotAllocation`) і
скасовує ванільний рендер.

## Файл

`ManiacHotbarOverlay` — реалізація
`InventorySlotAllocationClientHooks.HotbarRenderer`, підключається
один раз у `ClientSetup.onClientSetup` (`FMLClientSetupEvent`), бо це
не реєстрація overlay/рендерера через forge-подію, а прямий виклик
сетера бібліотеки.

## Дизайн

- Слот 60×60 (×3 від запасного рендерера бібліотеки), прив'язка до
  правого нижнього кута — не по центру, як у fallback.
- Обраний слот плавно росте до масштабу ×1.2 (easing, не миттєво).
- Рамка виділення — окремий елемент, що плавно ковзає до позиції
  нового обраного слота, а не перестрибує.

## Чому не в overlay/vitals чи actionprogress

Хотбар — це інвентар, не показник виживання й не прогрес дії.
Власна папка, той самий рівень, що vitals/actionprogress.

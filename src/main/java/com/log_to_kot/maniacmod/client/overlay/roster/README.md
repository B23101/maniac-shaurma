# client/overlay/roster — таб-список гравців

Ця папка зараз порожня. Тут з'явиться сам рендер таб-списку (клавіша
Tab, зведення хто живий/вибув/яка роль) — функціональний overlay:
не ефект, не постійний власний HUD, не одноразове повідомлення, а
довідкова панель, що показується по утриманню клавіші.

## Джерело даних — вже існує

Пакет уже є: `s2c/matchstate/RosterSyncPacket` →
`ClientPacketHandler.onRoster(List<RosterEntry>)` — зараз метод
порожній, бо цього UI-компонента ще нема. Коли розпочнеться
реалізація:

1. Додати `List<RosterSyncPacket.RosterEntry> roster` у
   `ClientMatchState` (метод-сеттер `setRoster(...)`, як і решта
   полів — пакетом, скидання в `reset()`).
2. Дописати виклик `ClientMatchState.setRoster(entries)` у вже
   існуючий `ClientPacketHandler.onRoster(...)`.
3. Клас тут читає `ClientMatchState.roster()`, малює список лише
   коли клавіша Tab утримується (реєстрація — новий keybind у
   `ManiacKeybinds`, обробка утримання — за зразком
   `ClientInputHandler.handleRescueHold()`, бо це так само утримання,
   а не одноразове натискання).

## Заборонено (нагадування з `net/README.md`)

Не запитувати в `RosterSyncPacket` числові hp/stamina — тільки
`SurvivorState`. Якщо для UI справді бракує деталізації — питання
до пакета, а не компенсація на клієнті вигаданими значеннями.

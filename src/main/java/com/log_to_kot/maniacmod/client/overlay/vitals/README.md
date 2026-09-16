# client/overlay/vitals — постійний HUD показників гравця

Ця папка зараз порожня. Тут з'явиться постійно намальований
HUD-елемент (бар хп, іконка стану, індикатор стаміни) для ВЛАСНОГО
гравця — намальований завжди, поки триває матч, а не на подію.

## Джерело даних

Тільки `ClientMatchState` (`hp()`, `maxHp()`, `stamina()`,
`survivorState()`). Ці поля вже приходять із сервера через
`s2c/vitals/SurvivorVitalsPacket` → `ClientPacketHandler.onVitals(...)`.
Новий елемент тут НЕ отримує власного пакета — читає вже наявні
поля. Якщо оверлею потрібне поле, якого немає в `ClientMatchState`,
питання спершу до `s2c/vitals/SurvivorVitalsPacket` (чи не дублює
воно щось із іншої категорії — див. `net/README.md`), а не до нового
пакета одразу.

## Чому не `overlay/effects/`

Тут — завжди видимий елемент інтерфейсу (як бар хп у ванільному
Minecraft). `effects/` — це те, що вмикається/вимикається залежно
від порогу і займає весь екран (віньєтка, затемнення), не постійний
HUD-блок у кутку.

## Реєстрація

Як і `GeneratorProgressOverlay` — через
`RegisterGuiOverlaysEvent` у `ClientSetup.onRegisterOverlays`, метод
`render(GuiGraphics)`, скидання стану через
`ClientMatchState.reset()` (той самий шаблон, що вже є для
`actionprogress/GeneratorProgressOverlay`).

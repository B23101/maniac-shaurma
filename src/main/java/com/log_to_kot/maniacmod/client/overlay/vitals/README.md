# client/overlay/vitals — постійний HUD показників гравця

Постійно намальований HUD-елемент (хп-бар, серце, стаміна-бар, іконки
стану) для ВЛАСНОГО гравця — намальований завжди, поки триває матч
(видимий лише виживому — маньяк і глядач свого хп не бачать).

**Коли видно:** виживому — у фазах з `PhaseRule.HUD` (тобто вже з
`ROLE_REVEAL`, а не лише коли фаза «ігрова»), плюс у `LOBBY` для
дебаг-`/maniac morph survivor`. Умова `isGameplay()` тут була причиною
зникання HUD після старту гри.

**Шкала вставання:** коли стан `CRAWLING`, під приціл малюється панель
«ТИСНИ ПРОБІЛ, ЩОБ ВСТАТИ» з прогрес-баром і лічильником n/N
(`renderStandUpBar`). Дані — `ClientMatchState.standUpPresses()` /
`standUpRequired()` (пакет `StandUpProgressPacket`); оверлей нічого не
рахує сам.

**Стаміна при `BROKEN_LEG`** завжди малюється порожньою.

Реалізація: `SurvivorVitalsOverlay.java`. Стиль: круглий медальйон із
серцем зліва, справа від нього дві сегментовані шкали-"камінці" (хп
зверху, стаміна знизу) — за наданим референсом, не суцільні бари.

## Джерело даних

Тільки `ClientMatchState` (`hp()`, `maxHp()`, `stamina()`,
`survivorState()`, `heartbeat()`, `standUpPresses()`,
`standUpRequired()`). Ці поля вже приходять із сервера
через `s2c/vitals/SurvivorVitalsPacket` → `ClientPacketHandler.onVitals(...)`.
Оверлей нічого не рахує сам — питання про нове поле спершу до
`SurvivorVitalsPacket`, а не до нового пакета одразу.

## Іконки стану: потрібні PNG

`SurvivorVitalsOverlay` малює іконки поламаної ноги / повзання /
непритомності як тимчасові кольорові комірки з літерою — текстур ще
немає в проєкті. Шляхи вже зафіксовані константами в класі:

- `textures/gui/vitals/broken_leg.png`
- `textures/gui/vitals/crawling.png`
- `textures/gui/vitals/unconscious.png`

Коли PNG (16×16) покладені за цими шляхами в
`assets/maniacmod/`, заміна на реальну текстуру — один виклик
`graphics.blit(...)` замість `drawIconPlaceholder(...)` усередині
`renderStatusIcons()`; розкладка (позиція, розмір, відступи) вже
готова й міняти її не треба.

## Чому не `overlay/effects/`

Тут — завжди видимий елемент інтерфейсу (як бар хп у ванільному
Minecraft). `effects/` — це те, що вмикається/вимикається залежно
від порогу і займає весь екран (віньєтка, затемнення), не постійний
HUD-блок у кутку.

## Реєстрація

Як і `GeneratorProgressOverlay` — через `RegisterGuiOverlaysEvent` у
`ClientSetup.onRegisterOverlays`, метод `render(GuiGraphics)`. Стану
між кадрами немає (все читається з `ClientMatchState` щоразу), тому
окремого `reset()` не потребує — `ClientMatchState.reset()` вже
скидає джерело даних, яке цей оверлей читає.

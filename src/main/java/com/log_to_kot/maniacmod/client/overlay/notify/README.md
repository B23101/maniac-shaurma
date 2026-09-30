# client/overlay/notify — одноразові повідомлення

⚠ Ця папка порожня НАВМИСНО і, на відміну від інших README у
`overlay/`, це не "ще не реалізовано" — рендер одноразових подій уже
є, просто не тут.

## Де насправді малюється notify

`s2c/notify/ActionBarPacket` і `s2c/notify/CountdownPacket` вже
обробляються в `ClientPacketHandler.onActionBar(...)` /
`onCountdown(...)`, а сам рендер віддано бібліотеці:

- Actionbar → `dev.shaurmalib.forge.overlay.ActionBarMessageSystem`
- Відлік → `dev.shaurmalib.forge.overlay.AnimatedCountdownSystem`

Обидва реєструються не в `ClientSetup`, а через
`ShaurmaLib.attachOverlayEngine` у головному класі мода — бібліотека
тримає один спільний `IGuiOverlay` замість окремого на кожен тип
повідомлення (див. коментар у `ClientSetup.java`).

## Коли сюди справді щось додавати

Тільки якщо з'явиться notify-подія, для якої shaurma-lib не підходить
(наприклад щось специфічне для мода, не текстове). До того часу новий
клас тут не потрібен — новий `notify`-пакет обробляється додаванням
методу в `ClientPacketHandler`, що викликає вже наявну систему
бібліотеки, а не новим класом у цій папці.

## Що тут уже є

Власні (не текстові, не з shaurma-lib) одноразові покази:

- `GeneratorCompletedOverlay` — «Генератор N з M полагоджено».
- `RoleRevealOverlay` — оголошення ролі на початку матчу (~5 с, без
  фонових плашок). Нового пакета немає: виживий стартує від кінця
  `WakeUpAnimation`, маньяк — від `TrapLoadoutPacket`
  (`ClientPacketHandler.onTrapLoadout`); вхід у `ROLE_REVEAL`
  («заряджає» показ) іде з `ClientMatchState.setPhase`, скидання — з
  `ClientMatchState.reset()`.
- `RoleRevealFx` — фон під текстом `RoleRevealOverlay`: віньєтка кольору
  ролі (блакитна — виживий, червона — маньяк; пульсує в такт серцю,
  спалахує на «ударі» імені), спалах екрана й кров, що стікає зверху
  (лише маньяк: калюжа-«стеля», патьоки з краплями, краплі, що
  зриваються). Без текстур і пакетів — лише `fill`/`fillGradient`.

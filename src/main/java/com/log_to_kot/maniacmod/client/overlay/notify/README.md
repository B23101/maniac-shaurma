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

# client/overlay/roster — tab-екран

## Статус: реалізовано

`TabRosterOverlay` малює таблицю через `dev.shaurmalib.forge.tab.TabListStyle`
(shaurma-lib). Показ/приховання — не власний keybind, а ванільна
клавіша Tab, підключена через `dev.shaurmalib.forge.tab.TabVisibilityModule`
(shaurma-lib): `ShaurmaLib.attachTabVisibility(new TabRosterOverlay())`
викликається один раз у `ClientSetup.onClientSetup`. Модуль сам
скасовує ванільний `minecraft:player_list` і показує наш рендер по
тій самій клавіші — окремого `ManiacKeybinds`-запису для табу немає й
не треба.

## Джерело даних

`s2c/matchstate/RosterSyncPacket` → `ClientPacketHandler.onRoster(...)`
→ `ClientMatchState.setRoster(...)` → `ClientMatchState.roster()`.
`TabRosterOverlay` нічого не рахує сам, лише розкладає вже готові
`RosterEntry` в `TabRow`.

## Заборонено (нагадування з `net/README.md`)

Не запитувати в `RosterSyncPacket` stamina/heartbeat — цей таб їх не
показує. hp/maxHp — виняток, свідомо доданий (обґрунтування в
docstring самого пакета): це єдине джерело чужого хп для UI, не
компенсація вигаданими значеннями на клієнті.

## Технічна дрібниця

Голови гравців (`drawHead`) використовують `PlayerInfo.getSkin()` з
мережевого з'єднання — працює лише для гравців, які вже отримали
скін від сервера (звичайний ванільний лаг в 1 кадр на приєднанні),
нічого додатково кешувати не треба.

## Лобі-режим

Зараз у `LOBBY` кожен `RosterEntry` приходить з `role == SPECTATOR`
(роль призначається лише на `ROLE_REVEAL`), тому таб у лобі показує
всіх однаково сірим — поділу "гратиме / просто дивиться" поки немає.

Це очікувано, а не недоробка цього класу: коли гра почне вирішувати
"хто гратиме" ще на етапі лобі, вона просто виставить відповідну
роль/стан раніше — `TabRosterOverlay` уже читає `role`/`state` з
кожного `RosterEntry` і одразу підхопить це без жодної зміни коду
тут. Нічого додаткового заводити заздалегідь не треба.

# traps/ — пастки маньяка

## Що тут

- **TrapArchetype** — абстрактний базовий клас. Спільні дані
  (кулдаун розміщення) і контракт (`applyEffect`, `shouldTrigger`,
  `placementShape`, `validatePlacement`, `spawn`).
- **TrapPlacementRules** — спільні правила розміщення («не в
  гравця», а для дроту — гравець як стіна) — застосовуються
  однаково до всіх пасток, не дублюються в кожному класі.
- **TrapRegistry** — список усіх пасток гри.
- **TrapAiming** — «куди дивиться маньяк» для розміщення (спільна
  функція клієнта й сервера).
- **TrapModule** — життєвий цикл УСІХ пасток: розміщення,
  спрацювання, ефект, звільнення, перезарядки.
- **TrapEscapeMinigame** — стан однієї спроби визволення з капкана.
- Конкретні пастки:
  - **BearTrapArchetype** (Капкан) — один блок, міні-гра визволення;
  - **ElectricWireArchetype** (Електричний дріт) — ДВІ точки, удар
    струмом;
  - **RopeBindArchetype** (Мотузка) — один блок, стеля 3–6 вільних
    блоків; жертву підвішує догори ногами (`TrapModule.tickRope`),
    вихід — ножиці ПКМ або міні-гра розкачування A/D (`RopeSwingPacket`);
    пастка одноразова;
  - **SmokeMineArchetype** (Димова міна) — один блок, одноразова;
    хмара газу (`TrapModule.tickSmokeMine`/`tickSmoke`): сліпота,
    приглушені звуки, плями диму на екрані, підсвітка, стаміна на нулі
    (і ще `smokeMineStaminaLockTicks` = 10 с після виходу), −1 хп/с;
    жертву НЕ тримає — вибігти можна;
    знешкодити до спрацювання — лом (ЛКМ), як капкан;
  - **SpikeTrapArchetype** (Шипи) — один вільний блок, одноразова;
    `TrapModule.tickSpikeTrap`: −`spikeTrapDamage` хп (10), стаміна в нуль
    (+«хвіст» `spikeTrapStaminaLockTicks`), підсвітка контуром
    (`spikeTrapGlowTicks`), підкидання жертви, кров. Жертву НЕ тримає
    (без лока й міні-гри); знешкодити до спрацювання НЕ можна
    (`SpikeTrapEntity.isPickable()=false`, лом їх ігнорує). Екран жертви —
    `SpikeHitPacket` → `client/SpikeHitEffect` (шипи знизу, кров, спалах,
    удар камери, FOV). Підсвітка й стаміна ідуть тими ж картами, що й у
    димової міни (`smokeGlow`/`smokeStaminaLock`).
  - **MineArchetype** (Розтяжка) — ще не реалізована, НЕ зареєстрована.

## Дві форми розміщення

`TrapArchetype.PlacementShape` визначає, скільки точок вибирає маньяк:

| Форма | Пастки | Контракт |
|---|---|---|
| `SINGLE_BLOCK` | капкан, мотузка, димова міна, шипи | `validatePlacement(level, floor)` + `spawn(level, floor, placer)` |
| `TWO_POINTS` | електричний дріт | + `validatePlacement(level, a, b)` + `spawn(level, a, b, placer)` |

Клієнт (`TrapPlacementController`) сам вирішує за `placementShape()`,
який режим запускати: один ПКМ чи два (з лінією-прев'ю між точками,
див. `TrapPlacementRenderer`). Сервер **перераховує обидві точки з
погляду гравця** — клієнт надсилає лише «це точка №1/№2», а не
координати.

## Дві моделі життєвого циклу в TrapModule

| | Капкан | Електричний дріт |
|---|---|---|
| Тік | `tickTrap` (з `BearTrapEntity`) | `tickWire` (з `ElectricWireEntity`) |
| Тривалість | доки не звільнять | `electricWireShockTicks` (15 с) |
| Визволення | лом (ЛКМ) / міні-гра | ножиці (ПКМ) / кінець таймера |
| Після спрацювання | лишається (можна звільнити) | **повністю зникає** |

Спільне для обох: лок `MOVEMENT`, підсвітка жертви (`TrapVictimGlowPacket`),
перезарядка слоту, імунітет після звільнення, очищення на виході з фази.

## Димова міна (TrapModule, секція «Димова міна»)

Окрема від капкана модель життя: **ніхто не потрапляє в `victims`** і лок
руху не ставиться. Міна (`SmokeMineEntity`) чекає; хто наступив —
`triggerSmokeMine` створює `SmokeCloud` і ставить `TRIGGERED=true`. Далі
`tickSmoke` (раз на тік, з `onPhaseTick`) для кожної хмари:

- шукає виживих на ногах у квадраті `smokeMineRadiusBlocks` × висота
  `smokeMineHeightBlocks` (`canCatch` + `shouldTrigger`, як в інших пасток);
- на вході й далі щосекунди викликає `SmokeMineArchetype.applyEffect`
  (−`smokeMineDamagePerSecond` хп + `BLINDNESS` на `smokeMineBlindnessTicks`);
  сліпота поновлюється, тож після виходу з хмари триває ще ~5 с;
- тримає `smokeGlow` (контур для всіх через той самий `TrapVictimGlowPacket`,
  що й жертви пасток — `broadcastVictimGlow` об'єднує обидва набори);
- шле `SmokeMufflePacket` лише на вхід у першу й вихід з останньої хмари
  (клієнт множить `MASTER`-гучність, див. `client/SmokeMuffle`).

Стаміна: у хмарі щотіка притискається до нуля (`SmokeMineArchetype.applyGasTick`),
після виходу `TrapModule.smokeStaminaLock` ще `smokeMineStaminaLockTicks` тіків
тримає її на нулі (`SmokeMineArchetype.drainStamina`) — вона не відновлюється.

`SmokeMufflePacket` — єдиний сигнал клієнту «ти в газі / вийшов»: за ним
`client/SmokeMuffle` глушить звук (якщо множник < 1), а
`client/overlay/effects/SmokeScreenOverlay` малює плями диму на екрані
(з'являються при вході, підсипаються, поки гравець у хмарі, і поступово
розчиняються). Пакет шлеться й тоді, коли глушіння вимкнене (`mufflePercent=0`).

Хмара живе `smokeMineCloudTicks`, потім міна зникає. Лом по ще не
спрацювавшій міні знешкоджує її (`onCrowbarHit` приймає `Entity`, не
лише `BearTrapEntity`); по спрацювавшій — ігнорується.

Вигляд: Geo-модель `smoke_mine.geo.json` (кант-«знак небезпеки», пластина,
кришка, червоний світлодіод; анімація `idle` блимає раз на секунду, світлодіод
горить у темряві через `smoke_mine_glowmask.png`) плюс кільце-пульс на підлозі,
яке `SmokeMineRenderer` малює поверх (`RenderType.debugQuads`) — міну видно
здалеку й у темряві. Дим — частинка `smoke_cloud`
(`ModParticles.SMOKE_CLOUD`, профіль у `ManiacParticles`).

## Як дріт ловить гравця (TrapModule.tickWire)

Торкання — відрізок дроту проти AABB гравця, **протягнутого від позиції
минулого тіка до поточної** (`TrapModule.sweptBox`). Дріт тонкий (2 пікселі
над підлогою), а перевірка раз на тік: без протягнутого боксу біг, ривок чи
стрибок могли перенести гравця через дріт між двома перевірками, і пастка
не спрацьовувала. Позиція минулого тіка береться з `TrapModule.trails`
(не з `xo/yo/zo`, бо їх порядок оновлення залежить від тіку мережі).

## Як додати нову пастку

1. Новий клас `extends TrapArchetype` у цьому пакеті.
2. Один рядок `register(new NewTrapArchetype());` у `TrapRegistry`.
3. Додати пастку в `traps()` відповідного маньяка
   (`maniacs/TestManiacArchetype.java`).
4. Правила розміщення (`TrapPlacementRules`) підхоплюються
   автоматично.

## Чому TrapPlacementRules окремо від TrapArchetype

Правила розміщення — властивість МІСЦЯ (де стоїть гравець), а не
властивість конкретного типу пастки. Усі пастки мають однакове
обмеження «не в гравця» (хітбокс виживого не перетинає місце пастки;
поряд з ним ставити можна) — тримати його в одному класі означає, що
зміна правила — одна правка, а не чотири. Електричний дріт додатково
не протягується крізь виживого: його хітбокс рахується стіною
(`TrapModule.checkPair`, лише на сервері).

## Ресурси (ТЗ 10 — файли додаються пізніше)

- Електричний дріт:
  - `geo/entity/electric_wire_mount.geo.json` (+ `.animation.json` з
    анімацією `idle`),
  - `textures/entity/electric_wire_mount.png`;
  - сам дріт малюється без текстури (`ElectricWireRenderer`,
    `RenderType.lines()`).
- Geo-ножиці: `geo/item/geo_scissors.geo.json`,
  `animations/item/geo_scissors.animation.json`,
  `textures/item/geo_scissors.png`, `models/item/geo_scissors.json`.
- Димова міна: звуки поки ваніль (`TRAP_SNAP` + `FIRE_EXTINGUISH`); частинка
  `textures/particle/smoke_cloud_0..3.png` (32×32), іконка
  `textures/gui/traps/smoke_mine.png` — замініть на свої без змін коду.
- Мотузка: `rope_place` (розміщення), `rope_catch` (зловилась жертва),
  `rope_escape` (вибралась — нитка порвалась або перерізана).
- Звуки: `wire_place`, `wire_zap`/`wire_zap_2`/`wire_zap_3`,
  `wire_cut`, `minigame_hit`, `minigame_miss` — події вже
  зареєстровані в `ModSounds`/`sounds.json`; без `.ogg` вони мовчать.

## Шипи — ресурси

- `geo/entity/spike_trap.geo.json`, `animations/entity/spike_trap.animation.json`
  (`idle` / `spike_out` / `spike_in`), `textures/entity/spike_trap.png` (64×64);
- іконка `textures/gui/traps/spike_trap.png` (32×32);
- звук `sounds/spike_hit.ogg` (синтезований — замініть на свій без змін коду).

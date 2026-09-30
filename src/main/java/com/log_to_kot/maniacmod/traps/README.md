# traps/ — пастки маньяка

## Що тут

- **TrapArchetype** — абстрактний базовий клас. Спільні дані
  (кулдаун розміщення) і контракт (`applyEffect`, `shouldTrigger`,
  `placementShape`, `validatePlacement`, `spawn`).
- **TrapPlacementRules** — спільні правила розміщення («не під
  гравцем», «не ближче 3 блоків до гравця») — застосовуються
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
  - **MineArchetype** (Розтяжка), **RopeBindArchetype** (Мотузка) —
    ще не реалізовані (немає розміщення й тіла), НЕ зареєстровані.

## Дві форми розміщення

`TrapArchetype.PlacementShape` визначає, скільки точок вибирає маньяк:

| Форма | Пастки | Контракт |
|---|---|---|
| `SINGLE_BLOCK` | капкан, міна | `validatePlacement(level, floor)` + `spawn(level, floor, placer)` |
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
властивість конкретного типу пастки. Усі пастки мають однакові
обмеження «не під гравцем / не ближче 3 блоків» — тримати цю перевірку
в одному класі означає, що зміна правила (наприклад, збільшити
мінімальну відстань до 5 блоків) — одна правка, а не чотири.

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
- Звуки: `wire_place`, `wire_zap`/`wire_zap_2`/`wire_zap_3`,
  `wire_cut`, `minigame_hit`, `minigame_miss` — події вже
  зареєстровані в `ModSounds`/`sounds.json`; без `.ogg` вони мовчать.

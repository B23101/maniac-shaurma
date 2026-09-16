# Міграція v3 → v5

Цей крок — **санітарний**: розкладено структуру, зафіксовано
контракти, перенесено роль головного класу. Ігрова логіка (тіки
пасток, бій, ремонт) свідомо **ще не перенесена** — щоб перенесення
робилось модуль за модулем, на вже стабільному каркасі.

---

## 1. Що сталося з головним класом

`game/ManiacGameManager.java` (898 рядків) робив одночасно сім різних
робіт. Він розкладений так:

| Що робив | Куди переїхало |
|---|---|
| Координація матчу, фази, умови завершення | `core/match/MatchOrchestrator` |
| Увесь стан матчу (`static` поля) | `core/match/MatchContext` |
| `GameState` + `attachLifecycle()` | `core/phase/GamePhase` + `PhaseManager` |
| `tickBearTraps` / `tickElectricWires` / `tickRopeBinds` / `tickMines` | `traps/` (кожна пастка — свій архетип) |
| `placeBearTrap` / `placeElectricWire` / `placeMine` / `bindWithRope` | `traps/TrapArchetype` + `TrapPlacementRules` |
| `onLivingAttack` (бій) | окремий бойовий модуль (наступний крок) |
| `createCorpse` / `tryRevive` / `tickCorpses` | `survivors/` — стан `UNCONSCIOUS` замість трупа |
| `refreshRepair` / `doTickRepair` / `tickUnlock` | `map/zones/GeneratorPoi`, `ExitPoi` |
| `giveSurvivorItems` / `giveManiacItems` / `refillManiacItems` | `items/` (видача на фазі `SCATTER`) |
| `applyManiacEffects` | `maniacs/ManiacArchetype` |
| `broadcast(...)` | шар повідомлень (shaurma-lib actionbar/chat) |

---

## 2. Карта файлів

| v3 | v5 | Примітка |
|---|---|---|
| `game/ManiacGameManager.java` | `core/match/MatchOrchestrator` + `MatchContext` | розділено |
| `entity/ManiacType.java` | `maniacs/ChuckyArchetype`, `SlendermanArchetype` | enum → клас на маньяка |
| `game/SurvivorData.java` | `survivors/SurvivorRole` + `SurvivorState` + `MatchContext` | 3 життя → 100 хп + стани |
| `game/CorpseData.java` | `survivors/SurvivorState.UNCONSCIOUS` | труп → непритомність |
| `game/BearTrapState.java` | `traps/BearTrapArchetype` | |
| `game/ElectricWireState.java` | `traps/ElectricWireArchetype` | |
| `game/RopeBindState.java` | `traps/RopeBindArchetype` | |
| `game/MineState.java` | `traps/MineArchetype` | |
| `game/GeneratorState.java` | `map/zones/GeneratorPoi` | + стадія бензину |
| `game/ExitState.java` | `map/zones/ExitPoi` | |
| `game/EscapeZone.java` | `map/zones/EscapeZoneArchetype` | |
| `game/ManiacSpawnZone.java` | `spawn/SpawnPoint` (kind `MANIAC`) | зона → точки на архетип |
| `game/ItemSpawnZone.java` | `spawn/SpawnPlanner` + `loot/` + `map/zones/ItemSpawnZoneArchetype` | розділено на 3 |
| `game/ItemLootTable.java` | `loot/LootEntry` | вкладений клас → окремий тип |
| `game/PendingGameStarter.java` | `core/match/` (вибір маньяка у фазі `ROLE_REVEAL`) | переписати без `Thread.sleep` |
| `items/ItemImplementations.java` | `items/*Item extends ItemArchetype` | клас на предмет |
| `cinematic/*` | слухач фази `CINEMATIC` | |
| `events/ServerEventHandler.java` | лишається, але худне до делегування | |
| `network/`, `client/`, `sound/`, `blocks/`, `mixin/`, `config/` | лишаються на місці | інфраструктура |

---

## 3. Дефекти v3, які структура прибирає

Це не стиль — це знайдені під час розбору реальні проблеми.

**1. `startGame()` не чистить міни.**
`resetGame()` чистить `mines`, `startGame()` — ні. Міни з
попереднього матчу лишаються на карті наступного.
→ Новий матч створює новий `MatchContext`; забути щось неможливо.

**2. Мертвий код у `SurvivorData`.**
`tick()`, `setBound()`, `bindFor()` не викликаються **ніде**.
`boundTicksRemaining` ніколи не зменшується.
→ Стан виживого — явна машина станів `SurvivorState`.

**3. `PendingGameStarter` викидає свої аргументи.**
Конструктор `PendingStart` приймає `allPlayers`, `generators`,
`exits`, `zones`, `escZones`, `maniacSpawn` — і **не зберігає
жодного**, лише `maniacUUID` і `onReady`. Плюс таймаут зроблено через
`new Thread(...).sleep(60_000)` замість серверного таймера.
→ Вибір маньяка стає фазою з таймером у `PhaseManager`.

**4. Ремонт може створити генератор із повітря.**
`doTickRepair()` при відсутності генератора в списку робить
`generators.add(new GeneratorState(genPos))` — тобто
`countActiveGenerators()` може перевищити задумані 5.
→ Генератори лише з розмітки карти (`MapManager`).

**5. `getter` зі side-effect.**
`GeneratorState.justFailed()` скидає прапор при читанні — другий
виклик поверне `false`. Класична пастка для того, хто потім додасть
логування.
→ У `GeneratorPoi` стан підсвітки читається без побічних ефектів.

**6. Мертві `@Deprecated` заглушки.**
`tickRepair()` і `tickRepairOnBlock()` — порожні тіла, залишені «для
сумісності». Викликати їх безпечно й безрезультатно.
→ Не переносяться.

**7. Зона спавну телепортує гравців.**
`ItemSpawnZone.spawnSurvivors()` — клас про лут займається
розміщенням команди, і при невдалому пошуку поверхні мовчки
пропускає гравця (`continue`).
→ `SpawnPlanner` будує повний план і падає **до** першого телепорту.

**8. `TOTAL_GENERATORS = 5` захардкоджено**, тоді як усі сусідні
числа ремонту беруться з конфігу.
→ Кількість генераторів визначає розмітка карти.

**9. Увесь стан статичний.**
Двадцять із гаком `static` полів переживають перезавантаження світу.
→ `MatchContext` — звичайний об'єкт. Єдина статика, що лишилась, —
одне посилання в `Phases`, яке знімається на зупинці сервера.

---

## 4. Порядок наступних кроків

Переносити **по одному модулю**, збираючи мод між кроками:

1. **Фази** — підключити `PhaseManager` у `ManiacMod`, замінити
   `getGameState() != RUNNING` на `Phases.allows(...)`.
   Найбільша віддача: після цього кожен наступний модуль вмикається
   без правок у решті.
2. **Спавн** — команди розмітки точок, збереження у конфіг світу,
   `SpawnPlanner` на фазі `SCATTER`.
3. **Маньяки** — `ManiacType` → архетипи, `ManiacSelectScreen` читає
   `ManiacRegistry.all()` замість `values()`.
4. **Генератори** — дві стадії, міні-ігри, підсвітка станів.
5. **Виживі** — хп/стаміна/стани, підняття непритомних.
6. **Пастки** — по одній, спільні правила розміщення.
7. **Предмети** — по одному в `ItemArchetype`.
8. **Прибирання** — видалити `game/`, `entity/ManiacType.java`,
   `items/ItemImplementations.java`.

Між кроками 1 і 2 старий `ManiacGameManager` ще живий — це нормально.
Він вимикається лише тоді, коли всі його обов'язки покриті.

---

# Крок 2 — інфраструктура перенесена

Перенесено все, крім чотирьох модулів, які лишаються на наступний
крок: **пастки, предмети, маньяки, логіка виживих**.

## Що переїхало

| v3 | v5 | Стан |
|---|---|---|
| `ManiacMod.java` | `ManiacMod.java` | переписано: матч створюється на старті сервера й знімається на зупинці |
| `network/ModMessages.java` + 10 пакетів | `net/` (`ModNetwork`, `ModPacket`, `s2c/`, `c2s/`) | переписано: 13 пакетів, спільний контракт |
| `client/ManiacKeybinds.java` | `client/ManiacKeybinds.java` | розширено: E, 5 + ванільні пробіл/Shift |
| `client/ManiacClientState.java` | `client/ClientMatchState.java` | розширено: фаза, роль, хп, стаміна, кулдауни, підсвітка |
| `client/ClientSetup.java` | `client/ClientSetup.java` | перенесено |
| `client/overlay/GeneratorProgressOverlay` | те саме | перенесено + скидання при виході |
| `events/ServerEventHandler.java` | `server/ServerHooks.java` | 162 рядки → переадресація |
| `commands/ManiacCommand.java` | `server/ManiacCommand.java` | 526 рядків → одна гілка на всі види точок |
| `config/ManiacConfig.java` | те саме | відв'язано від enum маньяків, додано розділ `map` |
| `items/ModItems`, `ModCreativeTab` | `registry/` + `items/ItemRegistry` | список предметів став один |
| `blocks/ModBlocks`, `GeneratorBlock` | `registry/`, `blocks/` | блок більше не тягне весь матч |
| `entity/ModEntityTypes` | `registry/ModEntityTypes` | CORPSE прибрано, GROUND_ITEM додано |
| `sound/ModSounds` | `registry/ModSounds` | перенесено |
| ремонт генераторів у `ManiacGameManager` | `map/GeneratorModule` | самостійний `PhaseListener` |
| — | `entity/GroundItemEntity` | новий: лут як geo-сутність |

`sound/SoundHelper` не переносився: його замінює `SoundCenter`
із shaurma-lib (`withSound()`).

## Підключені модулі shaurma-lib

`withConfig`, `withTeleport`, `withPlayerFreeze`, `withInteractionLock`,
`withLifecycle(2)`, `withLobby`, `withInventorySlotAllocation`,
`withStamina`, `withSound`, `withOverlays` + `withActionBarMessages`
+ `withAnimatedCountdown`.

`withInventorySlotAllocation` — це те, що робить 4 слоти інвентаря
реальними, і водночас звільняє клавішу 5 під підсвітку генераторів.

## Ще не зроблено

- Збереження розмітки точок у конфіг світу (зараз тільки в пам'яті)
- Кінематограф (`CINEMATIC`) — модуль-слухач фази
- `LivingFallEvent` → збиття з ніг (чекає модуль виживих)
- `EntityEvent.Size` → хітбокс маньяка (чекає модуль маньяків)
- GeckoLib-рендер лежачих предметів (зараз baked-модель предмета)

---

# Крок 3 — конфіги, керування, удар

## Конфіг

Переписано повністю. `ManiacConfig` (плоский набір геттерів) замінено
на схему: `ConfigSchema` → `ConfigKey` → `ManiacConfigs`.

| Вимога | Як зроблено |
|---|---|
| Немає мертвих налаштувань | Ключ — об'єкт зі схеми. Ключ у файлі поза схемою потрапляє в лог як «ні на що не впливає» |
| Немає неможливих значень | Діапазон/перелік оголошені поруч із дефолтом, перевіряються при кожному читанні файлу |
| Лікування з jar — поблочно | Відсутній блок дописується текстом у кінець файлу. Вміст наявного блока не чіпається ніколи |
| Reload на 100% | Зміна файлу підхоплюється за секунду; `/maniac reload` дає повний список зауважень; `ConfigReloadBus` для похідного стану |

Числа, що були захардкоджені, переїхали в yml: дистанції розкидання,
шанс зриву міні-гри, кулдаун пасток, дальність і шкода удару, стаміна,
падіння, підняття непритомного. `SpawnRules` видалено — він дублював
конфіг.

DATA-блоки (`spawn_points`, `zones`) лікуються так само тільки цілком:
видалена точка не повертається.

## Керування

У маньяка 0 слотів інвентаря → вільні цифри:

| Клавіша | Дія |
|---|---|
| ЛКМ | Удар |
| 1 2 3 | Здібності |
| Z X C | Пастки |
| 5 | Підсвітка генераторів (виживі, 4 слоти) |

`MAX_ABILITIES` і `MAX_TRAP_SLOTS` — константи `ManiacArchetype`, а не
конфіг. Архетип, що віддасть четверту здібність, падає при реєстрації.

Пакети `AbilityActivatePacket` і `TrapPlacePacket` несуть **номер
слота**, не id: слот прив'язаний до клавіші й не залежить від набору
персонажів.

## Удар

`ManiacCombatModule`: дальність з архетипу, шкода й перезарядка з
конфігу. На час перезарядки сервер тримає `LockType.ATTACK`
(shaurma-lib), клієнт глушить натискання міксином
`ManiacAttackGuardMixin`. Ванільний урон вимкнено цілком через
`DamageInterceptorRegistry`.

## FINALE

Більше не залежить від кількості гравців. Переходи читають
`MatchObjectives`: `POWER_RESTORED` → `POWERED`, `EXIT_OPENED` →
`FINALE`. Кількість людей лишилась в одній умові — «виживих не
лишилось».

## Маньяки

`ChuckyArchetype` і `SlendermanArchetype` видалено. `ManiacRegistry`
порожній і це робочий стан: команда старту скаже «жодного маньяка не
зареєстровано» замість падіння.

## Предмети на землі

Поворот по X випадковий, по Y завжди 0, задається до падіння.
Сутність падає, приземляється і вимикає фізику назавжди.

## shaurma-lib

Додано до раніше підключених: `withDamageGuard`, `withPlayerLifecycle`,
`withSpectator`, `withPlayerAnim`, `withFreeCamera`, `withScreenEffects`,
`withAnimatedItems`, `withAnimatedBlocks`. Звуки описані через
`SoundCueRegistry`/`SoundCategoryRegistry` — мод більше ніде не будує
`SimpleSoundInstance` руками.

Свідомо не підключено: `withModeContract`/`withModeSettings` (у мода
один режим і один файл конфігу — контракт режимів був би порожньою
обгорткою), `withChat`, `withRadio`, `withGraffiti`, `withLicense`,
`withInvisibleZones`, `withSkinnableEntities`, `withAnimationRecording` —
для них поки немає жодного use-site, а підключений модуль без
використання це той самий мертвий код.

---

# Крок 4 — MatchContext закритий, знайдено реальний витік інкапсуляції

Під час аудиту вже написаного (не TODO-каркасного) коду виявлено, що
`MatchContext`, попри опис "клас навмисно тупий, доступ лише через
координацію", був `public` — і за кілька кроків міграції до нього
почали звертатись напряму (`match.context().isManiac(...)`,
`.heal(...)`, `.maniacArchetype()` тощо) одразу з чотирьох різних
пакетів: `blocks/GeneratorBlock`, `items/MedkitItem`,
`server/ManiacCommand`, `server/ServerHooks`, `server/ServerPacketHandler`.
Жоден коментар цього не зупинив, бо компілятор це дозволяв.

**Це саме той механізм**, яким "чисто спроєктована" архітектура
з часом повертається до God Object — не через одну помилку, а через
багато дрібних прямих звернень, кожне з яких у моменті виглядало
виправданим ("мені ж треба лише одне поле прочитати").

## Що зроблено

- `MatchContext` став package-private (`core.match`). Прямий імпорт
  за межами пакету тепер не компілюється.
- `MatchOrchestrator` дістав вузький фасад: `isManiac`, `isSurvivor`,
  `maniacArchetype`, `survivorRoleOf`, `survivorStateOf`,
  `healSurvivor`, `damageSurvivor`, `aliveSurvivorCount`,
  `escapedSurvivorIds`, `completedObjectives`, `completeObjective`,
  `generatorAt`, `generators`, `spawnPoints`, `addSpawnPoint`,
  `clearMap`, `debugStatusLine`. Кожен метод — одна конкретна дія,
  а не "віддай мені весь об'єкт".
- `GeneratorModule` і `ManiacCombatModule` перестали приймати
  `Supplier<MatchContext>` (не могли б і далі, бо клас закритий) —
  тепер приймають `Supplier<MatchOrchestrator>` і звертаються лише
  до фасаду.
- Виправлено виклики у `blocks/GeneratorBlock`, `items/MedkitItem`,
  `server/ManiacCommand`, `server/ServerHooks`,
  `server/ServerPacketHandler` — усі перейшли на фасадні методи.

## Знайдено та виправлено принагідно (той самий аудит)

- **Інвертована умова в `ManiacAttackGuardMixin`**: `if
  (!ClientMatchState.allows(PhaseRule.DAMAGE)) return;` мало
  протилежний до задуманого ефект — поза ігровими фазами (лобі,
  кінематограф) клік ЛКМ не глушився замість того, щоб глушитись
  завжди. Виправлено: тепер поза `DAMAGE`-фазою клік блокується
  безумовно, а кулдаун перевіряється лише всередині ігрової фази.
- **Відсутня перевірка фази в `ClientInputHandler.handleStandUp`**:
  усі інші обробники клавіш звіряються з `PhaseRule`, цей — ні.
  Додано `allows(PhaseRule.MOVEMENT)`.

## Правило на майбутнє

Повний протокол — у кореневому `AI_CODE_GUIDE.md`. Коротко: якщо
новий спільний стан матчу потрібен більш ніж одному модулю — він
живе в `MatchContext`, доступ — лише через іменований метод на
`MatchOrchestrator`. Ніякого "тимчасового" `public` заради швидкості.

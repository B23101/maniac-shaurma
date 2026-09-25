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

---

# Крок 5 — геймплейні правки й справжня підсвітка

## Підсвітка генераторів: мітка → реальний контур сутності

Було: клієнт малював **екранний ромб-маркер** на проєкції точки.
Стало: світиться **сама сутність генератора** ванільним контуром
крізь стіни — і так само лише тому, хто натиснув 5.

| Файл | Роль |
|---|---|
| `client/glow/ClientEntityGlow` | клієнтський реєстр `id → колір` |
| `client/glow/GeneratorHighlightGlow` | позиція з пакета → завантажена сутність → реєстр (щотік) |
| `mixin/EntityGlowMixin` | підміняє `Entity.isCurrentlyGlowing()` і `getTeamColor()` |
| `GeneratorHighlightMarker` | **видалено** разом із реєстрацією оверлея |

Чому саме так: ванільне `setGlowingTag` — біт у метаданих, який сервер
розсилає ВСІМ (маньяк теж побачив би). Але контур малюється на
клієнті, і єдина умова — `isCurrentlyGlowing()`. Міксин у секції
`client` підміняє його лише локально; це стандартний підхід
клієнтських аутлайнерів, жодного впливу на сервер і чужих гравців.

## Генератори

`fuelPercentPerSecond` 2 → **4** (залив удвічі швидший). Діапазон
ключа розширено до 200, щоб адмін міг прискорити ще більше.

## Непритомність

- **Радіус милосердя** `maniacMercyRadiusBlocks` (15). Поки маньяк у
  ньому — таймер до смерті стоїть, а маньяку в actionbar (shaurma-lib)
  раз на секунду приходить `maniacmod.maniac.step_away`.
  `DownedSurvivorsPacket.Entry` дістав `paused` — інакше клієнт
  дораховував би час сам і таймер «тікав» би всупереч серверу.
- **Вставання після падіння**: прогрес тепер ДРОБОВИЙ і щотіка згасає
  (`standUpDecayTicks`, 80). Одним-двома натисканнями встати
  неможливо — треба спамити пробіл. `standUpPresses` 10 → 25.
- **Удар маньяка**: живій жертві видається швидкість I
  (`maniacHitSpeedTicks`, 5 с) і ПОВНА стаміна — вікно на втечу
  (`SurvivorModule.onManiacHit`, виклик із `ManiacCombatModule`).
  Якщо удар збив із ніг — адреналін не видається.
- **Таймер** над лежачим — ЗАВЖДИ червоні цифри, не лише в останні
  секунди.
- **Предмети з трупа** розлітаються ширше (`BODY_DROP_SPREAD_SPEED`
  0.10 → 0.55, радіус ~1.3 блока) — не однією купою.

## HUD

- Шкала вставання малюється **тим самим стилем, що «Бензин/Ремонт»**
  (`GeneratorFuelUiTheme`, геометрія 1:1 з `GeneratorProgressOverlay`).
- Серце «стукає» **завжди**: базовий пульс амплітудою 0.10, близькість
  маньяка підсилює його (до 0.32) і прискорює період — той самий
  вхідний `heartbeat`, що йде у звук `HeartbeatSoundHandler`.
- Предмет після підбору не зникає миттєво: `GroundItemEntity` летить
  до долоні гравця ~6 тіків (`VACUUM_OWNER` синхронізується, клієнт
  домалює той самий рух).

---

# Крок 6 — технологія маньяка

Маньяк більше не «гравець у своєму скіні»: це гравець із ГАБАРИТАМИ,
ОЧИМА й ВЛАСНОЮ ГЕО-МОДЕЛЮЮ з анімаціями.

## Дані: один архетип = один маньяк

| Файл | Що задає |
|---|---|
| `ManiacArchetype` | габарити (`hitboxWidth/Height`), очі, `visuals()` |
| `ManiacVisuals` | шляхи до geo/текстури/анімацій (за замовчуванням — за `id`) |
| `ManiacAnimationSet` | 8 коротких імен анімацій |
| `ManiacRegistry` | реєстрація (вона ж оголошує блок конфігу маньяка) |

**Додати маньяка = 1 клас + 3 файли.** Поклади
`geo/entity/<id>.geo.json`, `animations/entity/<id>.animation.json`,
`textures/entity/<id>.png` — і все; окремого коду на рендер, хітбокс,
меню, команди чи ФАЙЛ НАЛАШТУВАНЬ дописувати не треба.

Габарити перевіряються при реєстрації: нульовий розмір або очі вище
хітбокса — падіння на старті, а не дивна поведінка в грі.

## Баланс маньяка: окремий конфіг на кожного

| Файл | Що задає |
|---|---|
| `config/ManiacStats` | ключі параметрів, блок конфігу на маньяка |
| `config/maniacmod/maniac_stats/<id>.yml` | самі числа (створюється сам) |

```yaml
# config/maniacmod/maniac_stats/test_maniac.yml
hitboxWidth: 1.2      hitboxHeight: 3.0     eyeHeight: 2.7
attackRangeBlocks: 3.5   attackCooldownTicks: 120   attackDamage: 50
speedMultiplier: 1.2
```

Три речі, через які це зроблено саме так:

1. **Одне число — одне джерело правди.** Клас оголошує лише ДЕФОЛТ
   (`declaredAttackDamage()`), а діє завжди значення з файлу. Спільних
   ключів «на всіх маньяків» більше немає: правити одного не означає
   чіпати решту.
2. **Файл створюється сам** — з дефолтів класу, при першій реєстрації,
   і лікується як будь-який інший блок конфігу.
3. **Движок не роздвоєний:** ключі будуються тими самими
   `ConfigKey`-фабриками, тож діапазони, клемп, діагностика мертвих
   ключів, гаряче перезавантаження й меню налаштувань працюють як скрізь.
   Єдині зміни в движку: `ConfigBlock.addKeys` і
   `ConfigSchema.registerManiacBlock` (блоків маньяків фізично не може
   бути у статичному списку схеми) + створення файла блока, якого немає
   в jar, зі значень схеми.

⚠ **Міграція:** ключі `attackRangeBlocks`, `attackCooldownTicks`,
`attackDamage`, `speedMultiplier` переїхали з блока `maniac`
(`maniacs.yml`) у файл маньяка. У старому файлі вони лишаться й будуть
повідомлені як «ключ ні на що не впливає» — значення перенеси руками
(за замовчуванням дальність удару 3.5, шкода 50, перезарядка 120,
швидкість 1.2).

## Що бачить гравець у меню налаштувань

Кожен маньяк — окремий таб. Перекладу за id динамічного блока не може
існувати, тому:

- назва таба — перше речення коментаря блока (людське ім'я маньяка);
- підписи рядків — СПІЛЬНІ ключі `maniacmod.settings.key.<назва>`
  (`hitboxHeight`, `attackDamage`...), тож новий маньяк не вимагає
  нових рядків локалізації. Точний ключ з блоком усе ще має
  приоритет — ним можна підназвати параметр конкретного маньяка.

## Тіло: однакові габарити на обох сторонах

| Сторона | Файл | Джерело архетипу |
|---|---|---|
| Сервер | `maniacs/ManiacBodyEvents` | `MatchOrchestrator` |
| Клієнт | `client/maniac/ClientManiacBodyEvents` | `RosterSyncPacket` |

Обидва слухають `EntityEvent.Size` і `EntityEvent.EyeHeight`.
Клієнтський хітбокс — не косметика: по ньому рахуються колізії й
рейкаст прицілу.

**Числа їдуть із сервера.** Раніше обидва брали їх з одного архетипу
й синхронізація була не потрібна; тепер габарити налаштовуються в
`maniac_stats/<id>.yml`, а це файл КОЖНОЇ сторони окремо — клієнт
створив би собі свій із дефолтів і на виділеному сервері розійшовся б
розмірами. Тому `RosterSyncPacket.RosterEntry` несе `ManiacBody`
(габарити + очі) для маньяка (`null` для всіх інших), а локальний
конфіг лишається фолбеком на час до першого ростеру.

## Модель і анімації

| Файл | Роль |
|---|---|
| `client/maniac/ClientManiacs` | гравець → архетип (з ростеру) |
| `client/renderer/maniac/ManiacRenderHandler` | `RenderPlayerEvent.Pre` → скасувати ваніль і намалювати гео |
| `client/renderer/maniac/ManiacGeoRenderer` | `GeoReplacedEntityRenderer` (сутність Mojang + зовнішній аниматабл) |
| `client/renderer/maniac/ManiacGeoModel` | шляхи асе́тів динамічно з архетипу |
| `client/renderer/maniac/ManiacAnimatable` | стейт-машина анімацій |

`GeoReplacedEntityRenderer` обрано тому, що `Player` не можна зробити
`GeoAnimatable` (чужий клас), а `GeoEntityRenderer` вимагає саме цього.

### Чому анімації не дрижать

1. Анімація — ЧИСТА функція стану гравця (без прапорців «зараз
   грається»: нічого не треба скидати, нічого не може застрягти).
2. `setAndContinue` з тим самим `RawAnimation` НЕ перезапускає
   анімацію — GeckoLib порівнює значення з поточним.
3. `RawAnimation` кешуються за іменем, тому об'єкт завжди той самий —
   порівняння в (2) спрацьовує на кожному кадрі.
4. Перехід між станами згладжує сам GeckoLib (`TRANSITION_TICKS = 3`).

Пріоритет: удар → взаємодія (`isUsingItem`) → повітря → присідання →
біг → ходьба → стояння. Код вирішує сам; гравець анімацію не «водить».

## Асе́ти-шаблон

`test_maniac.geo.json` — людиноподібна фігура рівно 48px = 3 блоки
(16px = 1 блок: ноги 24, торс 14, голова 10) — рівно висота хітбокса
архетипу. `test_maniac.animation.json` — усі вісім анімацій.
`test_maniac.png` — **тимчасово копія текстури генератора** (заглушка,
щоб рендер мав що показати); художник замінює її один-до-одного.

## Свідомо не зроблено

- Іменні таблички маньяка: скасування `RenderPlayerEvent.Pre` знімає й
  їх, а `renderNameTag` — `protected`, тож викликати ззовні не можна.
  Рішення — окремий `GeoRenderLayer`.
- Рука від першої особи: ваніль малює її окремим шляхом
  (`ItemInHandRenderer`).
- `EntityEvent.Size`/`EyeHeight` позначені Forge як `forRemoval`, але в
  1.20.1 лишаються єдиним способом змінити габарити; придушено з
  коментарем, а не замовчано.

# Крок 7 — звук генераторів

| Файл | Роль |
|---|---|
| `sound/WorldSound` | серверний позиційний звук у СВОЄМУ радіусі |
| `sound/OggSoundLength` | тривалість .ogg із ресурсів (у тіках) |
| `map/GeneratorSoundscape` | розклад: запуск → гул → гул заливу |

| Звук | Коли | Радіус |
|---|---|---|
| `generator_start` | генератор щойно завершено | 10 |
| `generator_loop` | одразу після старту, до кінця матчу | 10 |
| `fuel_fill` | поки в генератор реально ллється бензин | 5 |

**Це НЕ `SoundCenter`.** Він грає звук локально в того, хто викликав, і
без позиції в світі («як у меню»). Генератор мусить бути чутний усім у
радіусі, з точки генератора й із загасанням за відстанню — тому грає
сервер: `PlayerList#broadcast` ванільним `ClientboundSoundPacket`.
Ванільний `Level#playSound` не підходить — у нього немає параметра
радіуса (мінімум 16 блоків: `volume > 1 ? 16*volume : 16`).

**Лупа як режиму не існує** — сервер грає файл один раз, тож гул
зібрано з повторів: файл програється знову рівно тоді, коли скінчився
попередній. Довжину бере `OggSoundLength` (гранула останньої сторінки
Ogg ÷ частоту дискретизації) — з самого файлу, а не з константи, тож
заміна асета нічого не ламає.

⚠ **Ассети конвертовано з mp3 у ogg** (`generator_start` 2.8 с,
`generator_loop` 32 с, `fuel_fill` 10.3 с), mp3-джерела лишились у
`assets/maniacmod/sounds/` — їх варто прибрати з ресурсів мода, коли
переконаєшся в якості конвертації.

## Що лишається відомим боргом

`sounds.json` і `ModSounds` оголошують ще 9 звуків (`game_start`,
`countdown_beep`, `countdown_final`, `generator_repair`, `power_on`,
`exit_open`, `survivor_escaped`, `maniac_nearby`, `wire_zap`), файлів
`.ogg` для яких у ресурсах НЕМАЄ — ці події зараз грають тишу (рівно
те, про що попереджає докстрінг `ModSounds`). Пастки й лом мають свої
файли: `trap_place`, `trap_snap`, `trap_struggle`, `crowbar_hit`.

# Крок 8 — міні-гра блокує ремонт генератора для всіх

**Було:** поки гравець проходить міні-гру, заморожений був лише ЙОГО
внесок, а решта гравців на тому самому генераторі лагодили як завжди.
Через це скілл-чек нічого не важив: поки один розбирається з дротами,
двоє інших просто дотягували прогрес до 100%.

**Стало:** міні-гра зупиняє ремонт ЦЬОГО генератора для ВСІХ.

| Що | Де |
|---|---|
| `ConfigKey<Boolean> MINIGAME_BLOCKS_REPAIR` (`generators.minigameBlocksRepair`, дефолт `true`) | `config/ConfigSchema` |
| `minigameInProgressAt(pos)` — зворотний пошук по `activeMinigames` | `map/GeneratorModule` |
| `blockByMinigame(player, session)` — пауза + actionbar «тут іде міні-гра» | `map/GeneratorModule` |
| `announceMinigameStarted(player, generator)` — новина решті виживих | `map/GeneratorModule` |
| `maniacmod.generator.minigame_started` / `.minigame_busy` | `lang/uk_ua`, `lang/en_us` |

**Пауза, а не скасування** — той самий принцип, що вже діє для відходу
від генератора: сесію не видаляємо, лише ховаємо бар. Гравець може
тримати Shift+ПКМ, поки інший грає, і прогрес піде сам, щойно міні-гра
скінчиться — нового сигналу від клієнта не треба. Якби сесію видалили,
гравець із затиснутою кнопкою застиг би без прогресу до наступного
натискання.

**Повідомлення:** тому, хто грає, нічого не йде (він бачить екран
міні-гри), маньяку — тим більше: скілл-чек виживих не має бути
безкоштовною підказкою «біжи до цього генератора». Решті виживих — одне
повідомлення на старті, а тому, хто впирається в заблокований генератор
з затиснутою кнопкою — нагадування раз на 2 секунди
(`BLOCKED_NOTICE_INTERVAL_TICKS`), бо інакше він бачив би лише зниклий
бар без причини.

**Підсвітка:** генератор із активним міні-грою рахується «зайнятим»
(жовтий), навіть якщо поряд більше нікого — гравець фізично поруч і саме
ним займається. Жовтий означає «хтось тут просто зараз», і міні-гра —
саме воно.

**Вимкнено (`false`)** — повертається стара поведінка, включно з тим, що
новина не розсилається взагалі (інформувати нема про що).


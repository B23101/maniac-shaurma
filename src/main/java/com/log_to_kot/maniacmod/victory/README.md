# victory — сцена перемоги маніяка

Фаза `GamePhase.VICTORY_SCENE` (між HUNT/FINALE і ENDING). Вмикається, коли ніхто не втік і карта розмічена.

## Розмітка (адмін)
`/maniac point add <тип>` — типи `victory_hook` (10+ шт.), `victory_maniac` (2–3), `victory_camera` (рівно 2: початок і кінець руху).
Камера й гачки зберігають ТОЧНУ позицію (де стоїть адмін; для камери — позиція очей) + yaw/pitch.

## Конфіг (game.yml)
victorySceneEnabled, victorySceneCameraSeconds, victorySceneLoadSeconds, victorySceneTitleSeconds,
victorySceneLiftBlocks, victorySceneShakePercent, victorySceneEmptyHooks.

## Хід сцени
затемнення → усіх у SPECTATOR + підйом + замки, чанки точок примусово, спавн `VictoryHookEntity`
(гачок + скін жертви лише на кістках `player_*`) і `VictoryManiacEntity` (модель архетипу раунду) →
ReplayDirector їде камерою A→B → титул «МАНІЯК ПЕРЕМІГ» → затемнення → усіх у лобі → розтемнення → екран статистики.

## Захист
Гейммод повертається в SPECTATOR щотіку; зник Replay — тіло тримається на кінцевій точці; вийшов гравець — прибирається;
нікого не лишилось — сцена завершується; сутності-сироти самі себе видаляють.

## Ассети
`geo/entity/victory_hook(.|_slim.)geo.json`, `animations/entity/victory_hook.animation.json`, `textures/entity/victory_hook.png`.
Кістки `player_*` малюються скіном (ширина рук обирається автоматично), решта — текстурою гачка.
Протокол мережі: 25.
Чорний екран перед камерою: fade-in 0.6 с + load (за замовч. 1 с, у коді стеля 1.2 с) + розтемнення 1 с. Наприкінці — затемнення, а екран статистики відкривається з чорного й розтемнюється над ним (ScreenEvent.Render.Post).
Поворот моделей сцени: рендерери крутять на `-heading` (GeckoLib сам додає 180°), тож персонаж дивиться туди, куди дивився адмін на точці.

## Примусове завершення (адмін)
`/maniac win maniac` — перемога маніяка: сцена «гачки з тілами» (якщо ввімкнена й розмічена, інакше одразу підсумки).  
`/maniac win survivors` — перемога виживих: усі, хто лишився в грі, «втікають» (тост «Ім'я втік! n/N», табло), далі підсумки з титулом виживих.  
Працює лише в ігрових фазах (HUNT…FINALE). Логіка — `MatchOrchestrator.forceEnd`; результат примусово фіксується в `MatchContext.forceOutcome`, тож `survivorsWon()` лишається єдиним джерелом правди.

## Вигляд гачка
Модель — кам'яний стовп із кельтським хрестом, дерев'яна балка з розкосом, залізний гачок на кінці, мотузка, кільце з ланцюгом і тріскелем. Кістки `stand`/`swing`/`player_*` і анімації не змінені. Усі кубики не-гравця мають per-face UV в атласі 64×64 (`textures/entity/victory_hook.png`): камінь, дерево, залізо, мотузка, борозни, джгут, спіраль, хрест, тріскель, дошки основи.


## Сцена перемоги ГРАВЦІВ (нове)
Коли втік хоч один виживий (`survivorsWon()`), `VICTORY_SCENE` грає другу сцену (те саме `VictorySceneModule`, режим вибирається на `begin`).
Модель — персонаж гравця з `snipers_shaurma` (`airplane_character.geo.json`; ті самі кістки Waist/Head/Body/Right Arm/…, тож анімації з Blockbench підходять), скін — реального гравця (`VictoryPlayerEntity`, wide/slim геометрія).

Розмітка (адмін): `/maniac point add <тип>`
- `victory_player` (до 10) — місця тих, хто ВТІК: стоять і танцюють (анімації `dance*` у `animations/entity/victory_player.animation.json`; нові просто дописуй у json — код підхопить за префіксом `dance`).
- `victory_ghost` (до 10) — місця тих, хто НЕ вижив (вибулі й не врятовані): та сама модель, але висять у повітрі (анімації `ghost*`), скін напівпрозорий із блакитним відтінком, світяться, навколо частинки душ.
- `victory_players_camera` (2) — початок/кінець руху камери цієї сцени; якщо не розмічено — береться `victory_camera`.
- Титул: зелений «ГРАВЦІ ПЕРЕМОГЛИ» (`client/victory/VictoryTitleFx`), у маніяка — червоний «МАНІЯК ПЕРЕМІГ» з глітчем і кров'ю. `VictoryStartPacket` має прапор `survivorsWin` (протокол 26).
Сцену можна вимкнути тим самим `victorySceneEnabled`; не розмічено — одразу екран підсумків.

## Freecam екрана підсумків
`/maniac point add stats_camera` (1 точка, позиція — очі адміна + yaw/pitch). На фазі ENDING `StatsCameraModule` ставить камеру кожного гравця на цю точку (spectator + замки + `ReplayDirector`, як у сцені), на виході — повертає. Нема точки — нічого не робить.

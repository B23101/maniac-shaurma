# intro — кіно-вступ (CINEMATIC → SCATTER)

Що бачить гравець після `/maniac start`:

```
0 с       екран темніє (музика стартує)
0.9 с     повністю чорний → камера Replay стартує під чорним
1.6–2.4 с розтемнення; ліворуч-внизу loader (спіннер + «ЗАВАНТАЖЕННЯ»),
          під ним порада; порада міняється кожні introTipSeconds (5) с
… 10 с    Replay ВЖЕ обірвано стелею; екран знову темніє
~10.7 с   (сервер) Replay стоп → SCATTER: гравців телепортує на позиції
~12.2 с   контроль повернено (IntroReveal) → екран розтемнюється РАЗОМ із пробудженням,
          музика гасне → далі оголошення ролі (RoleRevealOverlay) як раніше
```

Під час вступу: усі заблоковані (рух, удар, предмети, блоки, чат — `LockType`),
голос вимкнено (`VoiceSilencePhaseModule`), а на екрані немає нічого, крім Replay:
ні HUD, ні Tab, ні Esc, ні чату.

## Файли

| Файл | Роль |
|---|---|
| `IntroTimeline` | ЄДИНІ тайминги для сервера й клієнта |
| `IntroModule` | серверна режисура (фази, замки, пакети, прев'ю) |
| `IntroReplayConfigs` | читання/запис `replays.yml` |
| `IntroReplayCommand` | `/maniac replay ...` |
| `client/intro/*` | `IntroClient` (стан, блокування), `IntroOverlay` (малювання), `IntroTips`, `IntroAudio` |

## Replay

Ролики — у `replays.yml` (поряд із `maniac.yml`). Скільки завгодно роликів і точок,
спільний або окремий інтервал, стеля `introReplaySeconds` обриває все у визначений момент.

```yaml
replays:
  - id: fly_over
    audience: ALL            # ALL | SURVIVOR | MANIAC
    interpolation: SPLINE    # LINEAR | SMOOTH | SPLINE
    sharedIntervalMs: 3000   # інтервал між точками, якщо в точки нема свого
    cutAtMs: 0               # обірвати ролик на цій мс (0 — не обривати)
    transition: CUT          # CUT | DIP_TO_BLACK (вхід у цей ролик)
    dipMs: 600
    shake: 0.3               # рука оператора, градуси
    points:
      - {x: 120.5, y: 90.0, z: -40.2, yaw: 45.0, pitch: 15.0, fov: 0.0, intervalMs: 0}
      - {x: 160.0, y: 80.0, z: -10.0, yaw: 90.0, pitch: 5.0,  fov: 0.0, intervalMs: 1500}
```

Ставити точки зручніше командами: стань, куди має дивитись камера, і
`/maniac replay point fly_over` (необов'язково — інтервал у мс). `/maniac replay preview`
програє весь вступ тобі одному в лобі (`preview maniac` — версія маньяка).

## Маньяк

Той самий вступ, але сценарій добирається за `audience: MANIAC`/`ALL`. Після розтемнення
маньяк отримує вибір пасток (екран відкривається, щойно розтемнення закінчилось), а
оголошення ролі з іконками обраних пасток показує `RoleRevealOverlay` — вже після вибору.

## Вимкнути

`game.introEnabled: false` у `maniac.yml` — CINEMATIC знову живе один тік, старт миттєвий.

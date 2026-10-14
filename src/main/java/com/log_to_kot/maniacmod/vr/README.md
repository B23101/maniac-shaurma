# VR (Vivecraft) — сумісність

Vivecraft — **необов'язковий** мод. Без нього все працює як раніше; з ним VR-гравці
отримують окремі правила там, де ванільна логіка (клавіші, камера, поза тіла) для VR не годиться.

## Правила, які не можна ламати

1. **Жодної compile-time залежності від Vivecraft.** Усі виклики його API — через
   рефлексію в `VrApiReflect` (єдине місце). Збірка не потребує jar/Maven, питань SRG-vs-official
   немає; якщо API зміниться — відповідна частина тихо вимикається.
2. **Без VR — нуль змін поведінки.** Кожна VR-гілка закрита `VrCompat.isVrActive()` (клієнт) або
   `VrServerCompat.isVrPlayer(p)` (спільне). Для не-VR `aimOrigin/aimDir/aimDirView` повертають
   ТОЙ САМИЙ вираз, що був у коді раніше (`getEyePosition()`, `getLookAngle()`, `getViewVector(1.0f)`).
3. **Нові міксини — з `require = 0`**, як і решта проєкту.
4. Бібліотека `shaurma-lib` не змінювалась.

## Карта

| Що | Де |
|---|---|
| Фасади (клієнт / спільне) | `client/vr/VrCompat`, `vr/VrServerCompat` |
| Рефлексія над API | `vr/VrApiReflect` (+ `VrApiReflectAccess` для `client.vr`) |
| Налаштування клієнта | `vr/VrClientConfig` → `config/maniacmod-vr-client.toml` |
| Серверний замок руху | `vr/VrMovementLock` |
| HUD перед обличчям у матчі | `client/vr/VrHudLock` (внутрішні класи Vivecraft, рефлексія) |
| Повноекранні ефекти на очі | `client/vr/VrEyeEffects` |
| Вібрація серцебиття | `client/vr/VrHeartbeatHaptics` |
| Слоти «наступний/попередній» | `mixin/MixinInventorySwapPaintVr` |

## Свідомі межі

- Серверні частини (прицілювання рукою, лягання, замок руху) бачать VR-гравців лише якщо
  Vivecraft стоїть і на сервері.
- Телепорт-локомоція Vivecraft на 4+ блоків за тік під замком руху проходить
  (`VrMovementLock.SERVER_TELEPORT_DIST`).
- `VrHudLock` і фільтр проходів у `VrEyeEffects` читають ВНУТРІШНІ поля Vivecraft.
- Клавіші здібностей/пасток/підсвітки призначаються гравцем у налаштуваннях контролерів Vivecraft.

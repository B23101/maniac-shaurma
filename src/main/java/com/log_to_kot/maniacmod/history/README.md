# history — історія матчів

Повний запис кожного ЗАВЕРШЕНОГО матчу: склад, результат, до якої стадії дійшла гра, хто коли й чому
вибув, хронологія подій, XP кожного. Дивиться адмін (усі матчі) і гравці (особиста історія в профілі).

## Потік даних

```
події матчу ──► MatchOrchestrator.recordXxx / markEscaped / markEliminated / phase listener
                     │  (фасад; нічого не пише на диск)
                     ▼
              MatchContext.timeline()  = MatchTimeline   (core.match, package-private; склад + події + фази)
                     │  на ENDING (або reset() посеред матчу = ABORTED)
                     ▼
              MatchOrchestrator.archiveMatch()  ── пропускає: історія вимкнена / ДЕБАГ / не дійшли до HUNT
                     ▼
              MatchRecordBuilder.build()  = MatchRecord   (цифри з MatchSummaryPacket + XP з MatchRewardService)
                     ▼
              MatchHistoryService.archive()   пам'ять одразу, диск — у фоновому потоці
                     ▼
              <світ>/data/maniacmod/history/{index.jsonl, matches/<id>.json}
```

* `MatchRecord` — єдина форма даних: той самий клас іде на диск (Gson), по мережі (`write/read`) і на екран.
* `MatchBrief` — короткий рядок списку (індекс). Повний матч вантажиться лише коли його відкрили.
* Єдине джерело правди — `matches/*.json`. `index.jsonl` відбудовується сам, якщо зник/пошкоджений.

## Правила

* **Дебаг не записується.** Якщо в матчі хоч раз був `DebugMode.enabled()` — він у історію не потрапляє
  (`MatchTimeline.debugTainted`). Не дійшов до HUNT — теж ні. Перервані (маніяк вийшов) — так, як `ABORTED`, без XP.
* **Права** (`HistoryAccess`, перевіряються на сервері на КОЖЕН запит):
  * усі матчі — лише адмін (`hasPermissions(2)`);
  * особиста історія — власник профілю; адмін; решта — за правилом самого профілю (лобі + гравець онлайн);
  * один матч — адмін, його учасник, або той, хто відкрив його з історії гравця, чию історію йому видно.
* `id` матчу з клієнта проходить `MatchHistoryStorage.validId` — path traversal неможливий.

## Як додати нову подію в хронологію

1. Значення в КІНЕЦЬ `HistoryEventType` (мережа пише ordinal) + опис полів у його Javadoc.
2. `MatchOrchestrator.recordXxx(...)` → `context.timeline().add(...)`. Модулі гри викликають лише фасад.
3. Текст і колір події — `HistoryUi.eventText/eventColor/eventCategory` (клієнт) + ключ `maniacmod.history.ev.*` в обох lang.

## Нова ігрова фаза / новий маніяк / нове поле запису

* Нова ігрова фаза в `GamePhase` → додати в `HistoryUi.STAGES`, ключі `maniacmod.history.stage.*` і `.phase.*`.
* Новий маніяк — нічого: 3D-бюст кадрується з його `hitboxHeight`/`eyeHeight` (`ManiacBustWidget`).
* Нове поле `MatchRecord` — додати в `write` І `read` (в одному порядку) і в `normalize`; старі файли
  читаються зі значеннями за замовчуванням. Змінився формат пакетів → `PROTOCOL_VERSION` у `ModNetwork`.

## Конфіг

`game.historyEnabled` (true), `game.historyMaxEvents` (1500; при переповненні відкидаються лише удари/пастки).

## Клієнт

`client/screen/history/`: `MatchHistoryScreen` (список + досьє з 3D-бюстом; адмінський архів або особиста історія),
`MatchDetailScreen` (Огляд / Гравці / Хронологія / Досвід), `ManiacBustWidget` (голова й торс без ніг),
`HistoryUi` (формати й тексти). Стан — `client/history/ClientHistoryState`. Входи: кнопка в ESC-меню (лише адмін),
команда `/maniac history [гравець]` (оператор; відкриває архів або історію гравця через `OpenHistoryPacket`)
і кнопка «Історія» (H) у профілі. Досьє з бюстом з'являється лише коли матч вибрано кліком.

# leaderboard — топи гравців

Таблиці лідерів за даними профілів (`PlayerProfileStorage`). Окремого сховища нема: файли профілів — єдине джерело правди.

## Потік даних
```
матч завершено ─► MatchRewardService ─► ProfileRecorder + LeaderboardService.stamp (нік/скін/час) ─► PlayerProfileStorage.save
вхід/вихід     ─► LeaderboardHooks.touch ───────────────────────────────────────────────────────────┘        │
                                                                              LeaderboardService.onProfileSaved (кеш)
екран ─► LeaderboardRequestPacket ─► LeaderboardService ─► LeaderboardPagePacket / LeaderboardOverviewPacket ─► ClientLeaderboardState
```
* `LeaderboardCategory` — усі таблиці (вкладка, формат, як рахувати). Порядок = мережевий протокол: нова — лише в КІНЕЦЬ + `PROTOCOL_VERSION`.
* Порожні профілі (без XP й матчів) у топи не потрапляють. Відсоток перемог — від 10 матчів.
* Рівність значень: вищий загальний XP, далі нік.
* Корона = місце №1 в таблиці; кількість корон іде в рядок і в огляд.

## Скін для офлайн-гравців
Сервер кладе `textures` у профіль (`skinTexture`) при вході/виході/кінці матчу; рядок несе його клієнту.
`LeaderboardAvatar` малює НЕРУХОМИЙ 3D-бюст (голова + торс до пояса) через клієнтський «манекен» (`RemotePlayer`,
у світ не додається). Поки скін докачується — дефолтний за UUID.

## Нова таблиця
1. Значення в КІНЕЦЬ `LeaderboardCategory` (+ `maniacmod.lb.cat.<id>` і `.desc` у обох lang).
2. Підняти `PROTOCOL_VERSION`.

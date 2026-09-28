# Maniac Mod

Forge-мод для Minecraft 1.20.1.

## Вимоги

- Java 17 (JDK, не JRE)
- Git
- GitHub-доступ до пакета `dev.shaurmalib:shaurma-lib-forge`

## Збірка

```powershell
.\gradlew.bat clean build --no-daemon
```

Готові файли з'являться в `build\libs\`.

ShaurmaLib підключається як опублікований артефакт із GitHub Packages:
`dev.shaurmalib:shaurma-lib-forge:0.1.0-SNAPSHOT`.

## Налаштування доступу до ShaurmaLib

GitHub Packages потребує Personal Access Token зі scope `read:packages`.
Створи файл `%USERPROFILE%\.gradle\gradle.properties` поза репозиторієм:

```properties
gpr.user=ТВІЙ_GITHUB_USERNAME
gpr.key=ТВІЙ_GITHUB_TOKEN
```

Токен не можна додавати в цей репозиторій.

## Запуск у Minecraft

Після збірки скопіюй у папку `mods`:

1. `build\libs\maniacmod-3.0.0.jar`
2. `shaurma-lib-forge-0.1.0-SNAPSHOT.jar`
3. Forge-сумісний GeckoLib
4. PlayerAnimator, якщо використовується у встановленій версії мода

Усі моди мають бути для Minecraft 1.20.1 і Forge 47.x.

## Голосовий чат (Simple Voice Chat)

Голосовий шар мода **опційний**: якщо SVC не встановлено, усе інше працює
як звичайно. Якщо встановлено — модуль додає:

- «жахливий» голос маньяка (гра керує ним за роллю й фазою, у гравця
  немає налаштувань голосу);
- тишу на технічних фазах (кіно, розкидання, оголошення ролей, скидання);
- спільний ефір без обмеження відстані на фазі `ENDING`.

Щоб гравці не могли вручну створювати групи SVC, додайте у
`config/voicechat/voicechat-server.toml`:

```toml
groups_enabled = false
```

Це офіційний прапорець SVC, коду в моді не потребує. Деталі, налаштування
й чесні обмеження — у `src/main/java/com/log_to_kot/maniacmod/voice/README.md`.

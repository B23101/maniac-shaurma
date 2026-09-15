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

Якщо поруч із цим проектом є папка `shaurma-lib`, Gradle використає її локально.
Інакше залежність завантажується з GitHub Packages.

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

# core/phase — фази матчу

Єдине джерело правди про те, що зараз відбувається в матчі.

## Файли

- `GamePhase` — список усіх фаз. Кожна знає свій `PhaseKind`
  (TECHNICAL / GAMEPLAY), набір дозволів і те, у який стан
  shaurma-lib вона мапиться.
- `PhaseKind` — технічна фаза чи ігрова.
- `PhaseRule` — дозволи: DAMAGE, TRAPS, GENERATOR_REPAIR, ESCAPE…
- `PhaseListener` — інтерфейс модуля, що реагує на зміну фаз.
- `PhaseManager` — власник поточної фази, розсилає події.
- `Phases` — статичний фасад для однорядкових перевірок.

## Як цим користуватись

**Перевірка в предметі/блоці/пакеті:**
```java
if (!Phases.allows(PhaseRule.GENERATOR_REPAIR)) return InteractionResult.FAIL;
```

**Модуль, що має свою поведінку у фазах:**
```java
public final class TrapModule implements PhaseListener {
    public String id() { return "traps"; }

    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase == GamePhase.RESET) removeAllTraps();
    }

    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long t) {
        if (!phase.allows(PhaseRule.TRAPS)) return;
        tickTraps(players);
    }
}
```
Реєстрація: `orchestrator.registerModule(new TrapModule());`

## Правила

1. **Ніхто, крім `PhaseManager`, не змінює фазу.** Модуль може
   попросити перехід через `MatchOrchestrator`, але не виставляє
   поле сам.
2. **Не порівнюй фази напряму, якщо можна спитати дозвіл.**
   `phase == HUNT` зламається, щойно з'явиться `POWERED`.
   `allows(PhaseRule.DAMAGE)` — ні.
3. **Модулі не знають один про одного.** Якщо `traps` потребує
   даних від `survivors` — це йде через `MatchContext`, не прямим
   викликом.

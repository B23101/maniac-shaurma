package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec;

import java.util.UUID;

/**
 * Стан ОДНІЄЇ спроби вибратися з капкана — міні-гра «попади в ціль»,
 * той самий повзунок і та сама формула траєкторії, що в міні-грі
 * ремонту генератора (див. {@link TargetMinigameSpec}), але зі СВОЇМИ
 * правилами:
 *
 *   • потрібно {@code trapEscapeHitsRequired} влучань (за замовчуванням 5);
 *   • промах НЕ провалює міні-гру, а знімає
 *     {@code trapEscapeMissPenalty} влучань (за замовчуванням 2, не
 *     нижче нуля) — капкан не вибухає, він просто не відпускає;
 *   • спроба одна на захлопування, з лімітом часу
 *     {@code trapEscapeTicks}. Час вийшов — гравець лишається в капкані
 *     й чекає на товариша з ломом (див. {@code TrapModule}).
 *
 * ── Чому окремий клас, а не {@code ActiveRepairMinigame} ──────────────
 * {@code ActiveRepairMinigame} належить ремонту генератора й має
 * ПРОТИЛЕЖНУ політику помилки: там промах — провал і вибух, тут —
 * втрата прогресу. Спільним лишається рівно те, що справді спільне:
 * геометрія повзунка й перевірка правдоподібності кліку
 * ({@link TargetMinigameSpec}) — і вона перевикористана, а не
 * переписана. Один клас із двома режимами помилки в одному місці був би
 * гіршим: кожен виклик мусив би питати «а який це тип?».
 *
 * ── Хто вирішує результат ────────────────────────────────────────────
 * Лише сервер. Клієнт малює повзунок за власним годинником і надсилає
 * ЛИШЕ заявлену позицію; сервер перераховує траєкторію сам
 * ({@link TargetMinigameSpec#isPlausible}) і не приймає на віру
 * вигадану позицію — вона рахується як промах.
 */
public final class TrapEscapeMinigame {

    /** Що сталося від одного кліку. */
    public enum Result {
        /** Влучання зараховано, але влучань ще не досить. */
        HIT,
        /** Промах (чи неправдоподібна позиція): влучання зменшено на штраф. */
        MISS,
        /** Набрано потрібну кількість влучань — гравець вільний. */
        ESCAPED
    }

    private final UUID trapId;
    private final TargetMinigameSpec spec;
    private final int missPenalty;
    private final long openedAtMillis = System.currentTimeMillis();

    private int hits = 0;
    private int ticksLeft;

    public TrapEscapeMinigame(UUID trapId, TargetMinigameSpec spec, int missPenalty, int totalTicks) {
        this.trapId = trapId;
        this.spec = spec;
        this.missPenalty = Math.max(0, missPenalty);
        this.ticksLeft = Math.max(1, totalTicks);
    }

    public UUID trapId() { return trapId; }

    /** Скільком влучанням треба дійти до визволення (для пакетів і повідомлень). */
    public int hitsRequired() { return spec.hitsRequired(); }

    public int hits() { return hits; }

    /** Скільки тіків лишилось до кінця спроби. */
    public int ticksLeft() { return ticksLeft; }

    /**
     * Один тік спроби. Оглушення часу тут, а не в модулі — {@code TrapModule}
     * лише питає {@link #isExpired()}, щоб не тримати в собі ще й таймер.
     */
    public void tick() {
        if (ticksLeft > 0) ticksLeft--;
    }

    public boolean isExpired() { return ticksLeft <= 0; }

    /**
     * Клік у міні-грі. Позицію з пакета сервер НЕ бере на віру: спершу
     * перевіряє, чи могла вона бути позицією повзунка у вікні затримки
     * мережі; вигадана позиція — це промах, а не «влучання без ризику».
     *
     * @param claimedCursorPosition позиція повзунка (0.0-1.0), яку заявив клієнт
     * @param lagToleranceMs        допуск затримки мережі, мс
     */
    public Result attempt(double claimedCursorPosition, long lagToleranceMs) {
        long elapsedMs = System.currentTimeMillis() - openedAtMillis;

        // Спершу правдоподібність, потім влучання: порядок важливий, бо
        // isHit() порівнює з ціллю, а ціль у isPlausible навмисно не
        // згадується — інакше підказка «де ціль» пішла б у клієнта.
        boolean plausible = spec.isPlausible(claimedCursorPosition, elapsedMs, lagToleranceMs)
            && spec.isHit(claimedCursorPosition);

        if (plausible) {
            hits++;
            return hits >= spec.hitsRequired() ? Result.ESCAPED : Result.HIT;
        }

        hits = Math.max(0, hits - missPenalty);
        return Result.MISS;
    }
}

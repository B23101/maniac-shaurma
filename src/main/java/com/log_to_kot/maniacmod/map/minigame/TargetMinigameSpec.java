package com.log_to_kot.maniacmod.map.minigame;

/**
 * Параметри міні-гри "ціль" (Generator Startup) на один запуск.
 *
 * ── Хто що рахує ─────────────────────────────────────────────────────
 * Траєкторія повзунка — детермінована трикутна хвиля від часу,
 * що минув з моменту відкриття екрана: {@link #cursorAt}. Ту саму
 * формулу викликають І клієнтський екран (щоб малювати повзунок), І
 * сервер (щоб перевірити клік). Тож розійтись вони не можуть.
 *
 * ── Чому сервер не довіряє позиції з пакета ──────────────────────────
 * Раніше сервер брав {@code cursorPosition}, яку надіслав клієнт, і
 * просто порівнював із ціллю: модифікований клієнт слав би позицію
 * цілі щоразу й ніколи не промахувався. Тепер сервер перераховує
 * траєкторію сам і приймає клік лише якщо заявлена позиція є такою,
 * яку повзунок справді мав у вікні часу з урахуванням затримки мережі
 * (див. {@link #isPlausible}). Вигадану позицію відхиляє.
 *
 * {@code targetPosition} — позиція нерухомої цілі на смузі (0.0-1.0),
 * та сама для сервера й клієнта.
 */
public record TargetMinigameSpec(
        long seed,
        double cursorSpeed,
        double hitZoneWidth,
        double targetPosition,
        int hitsRequired
) {
    /** Допуск на порівняння позицій: округлення float у пакеті та годинник клієнта. */
    private static final double POSITION_EPSILON = 0.02;

    /** Крок вибірки вікна, секунди. Дрібніший за будь-який реалістичний рух повзунка. */
    private static final double SAMPLE_STEP_SECONDS = 0.005;

    /**
     * Позиція повзунка (0.0-1.0) через {@code elapsedSeconds} після
     * відкриття: трикутна хвиля туди-назад зі швидкістю {@code cursorSpeed}
     * (частка смуги за секунду).
     */
    public static double cursorAt(double elapsedSeconds, double cursorSpeed) {
        double phase = (Math.max(0.0, elapsedSeconds) * cursorSpeed) % 2.0;
        return phase <= 1.0 ? phase : 2.0 - phase;
    }

    public double cursorAt(double elapsedSeconds) {
        return cursorAt(elapsedSeconds, cursorSpeed);
    }

    /** Чи потрапляння в координату (0.0-1.0) на смузі рахується влучанням. */
    public boolean isHit(double cursorPositionOnClick) {
        return Math.abs(cursorPositionOnClick - targetPosition) <= hitZoneWidth / 2.0;
    }

    /**
     * Чи могла заявлена клієнтом позиція справді бути позицією повзунка.
     *
     * Час на клієнті тече від моменту відкриття екрана, а сервер бачить
     * клік із запізненням {@code lagToleranceMs} (доставка пакета). Тому
     * істинний клієнтський час кліку лежить у вікні
     * {@code [serverElapsed − допуск, serverElapsed]}, а заявлена позиція
     * має збігатися з {@link #cursorAt} для якогось моменту з цього вікна.
     *
     * @param claimed         позиція з пакета; не число або поза 0..1 — неправда
     * @param serverElapsedMs скільки мс тому сервер відкрив міні-гру
     * @param lagToleranceMs  допуск затримки, мс
     */
    public boolean isPlausible(double claimed, long serverElapsedMs, long lagToleranceMs) {
        if (!Double.isFinite(claimed) || claimed < 0.0 || claimed > 1.0) return false;

        double newest = serverElapsedMs / 1000.0;
        double oldest = Math.max(0.0, newest - lagToleranceMs / 1000.0);
        for (double t = newest; t >= oldest - 1e-9; t -= SAMPLE_STEP_SECONDS) {
            if (Math.abs(cursorAt(t) - claimed) <= POSITION_EPSILON) return true;
        }
        // Вікно могло закінчитись між двома кроками — перевіряємо й межу.
        return Math.abs(cursorAt(oldest) - claimed) <= POSITION_EPSILON;
    }
}

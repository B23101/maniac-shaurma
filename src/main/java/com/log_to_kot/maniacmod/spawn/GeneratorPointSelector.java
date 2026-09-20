package com.log_to_kot.maniacmod.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleBiFunction;

/**
 * Вибір підмножини точок генераторів: МАКСИМАЛЬНО ВИПАДКОВИЙ, але
 * РОЗПОДІЛЕНИЙ по карті.
 *
 * ── Що було не так ───────────────────────────────────────────────────
 * Попередній алгоритм у {@code SpawnPlanner} брав випадково лише ПЕРШУ
 * точку, а кожну наступну — «найдальшу від уже обраних». Це
 * детермінована жадібна процедура: вимір на 14 точках і 6 потрібних
 * показав, що з 3003 можливих наборів за 20 000 матчів з'являлось лише
 * 9, а три точки обиралися у 100% матчів (кути карти). Гравці швидко
 * вчили б «генератори завжди там» — рівно той кемпінг, від якого
 * захищають бонусні генератори.
 *
 * ── Як тепер ─────────────────────────────────────────────────────────
 * 1. Кандидати перемішуються — порядок повністю випадковий.
 * 2. Проходимо список і приймаємо точку, лише якщо вона не ближче
 *    {@code minToPeer} до вже обраних і не ближче {@code minToManiac}
 *    до маньяка (якщо він є).
 * 3. Не набрали потрібну кількість — обидві дистанції множаться на
 *    {@code relaxationStep} і все повторюється з чистого аркуша. Той
 *    самий покроковий підхід, що вже діє для розкидання гравців.
 * 4. Останній прохід — дистанції 0: лишається тільки правило
 *    «одна точка = один генератор». Тож результат є ЗАВЖДИ, якщо
 *    кандидатів достатньо (їх нестачу перевіряє викликач раніше).
 *
 * Так правило «не ставити генератори купкою» тримається, поки карта
 * фізично це дозволяє, і лише потім м'яко послаблюється, а не ламає
 * матч.
 *
 * ── Чому окремий клас без Minecraft ──────────────────────────────────
 * Це чиста логіка над координатами: її можна прогнати мільйон разів у
 * тесті й виміряти розподіл, чого не зробиш усередині Forge. Дистанція
 * приходить як функція, тому клас не знає ні про {@code SpawnPoint}, ні
 * про {@code BlockPos}.
 */
public final class GeneratorPointSelector {

    private GeneratorPointSelector() {}

    /**
     * @param candidates      усі розмічені точки (не змінюється)
     * @param desired         скільки обрати; має бути ≤ candidates.size()
     * @param maniacAnchor    точка старту маньяка або {@code null}
     * @param minToPeer       мінімальна відстань між двома генераторами, блоків
     * @param minToManiac     мінімальна відстань генератора до маньяка, блоків
     * @param relaxationStep  множник послаблення за прохід (0 &lt; step &lt; 1)
     * @param maxPasses       скільки разів послаблювати перед фінальним проходом без обмежень
     * @param distance        горизонтальна відстань між двома точками
     * @return рівно {@code desired} різних точок у випадковому порядку
     * @throws IllegalArgumentException якщо кандидатів менше, ніж треба
     */
    public static <T> List<T> select(List<T> candidates, int desired, T maniacAnchor,
                                     double minToPeer, double minToManiac,
                                     double relaxationStep, int maxPasses,
                                     ToDoubleBiFunction<T, T> distance, Random rng) {
        if (desired < 0) {
            throw new IllegalArgumentException("desired < 0: " + desired);
        }
        if (candidates.size() < desired) {
            throw new IllegalArgumentException("Кандидатів " + candidates.size()
                + ", а потрібно " + desired);
        }
        if (desired == 0) return new ArrayList<>();

        double peer = Math.max(0.0, minToPeer);
        double toManiac = Math.max(0.0, minToManiac);

        for (int pass = 0; pass <= maxPasses; pass++) {
            List<T> picked = attempt(candidates, desired, maniacAnchor, peer, toManiac, distance, rng);
            if (picked != null) return picked;
            peer *= relaxationStep;
            toManiac *= relaxationStep;
        }

        // Фінальний прохід без жодних дистанцій — гарантія результату.
        List<T> picked = attempt(candidates, desired, maniacAnchor, 0.0, 0.0, distance, rng);
        // attempt з нульовими дистанціями не може провалитись: кандидатів
        // достатньо, а «вже обрана» точка виключена за ідентичністю.
        return picked;
    }

    /**
     * Одна спроба: випадковий порядок + жадібне приймання за дистанціями.
     * {@code null}, якщо набрати потрібну кількість не вдалось.
     */
    private static <T> List<T> attempt(List<T> candidates, int desired, T maniacAnchor,
                                       double minToPeer, double minToManiac,
                                       ToDoubleBiFunction<T, T> distance, Random rng) {
        List<T> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled, rng);

        List<T> picked = new ArrayList<>(desired);
        for (T candidate : shuffled) {
            if (picked.size() == desired) break;

            if (maniacAnchor != null && distance.applyAsDouble(candidate, maniacAnchor) < minToManiac) {
                continue;
            }
            boolean tooClose = false;
            for (T chosen : picked) {
                if (distance.applyAsDouble(candidate, chosen) < minToPeer) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;

            picked.add(candidate);
        }
        return picked.size() == desired ? picked : null;
    }
}

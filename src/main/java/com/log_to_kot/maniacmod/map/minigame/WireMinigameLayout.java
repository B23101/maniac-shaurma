package com.log_to_kot.maniacmod.map.minigame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Розкладка міні-гри "дроти" (скріншот дизайну: 4 кольорові контакти
 * зліва, 4 справа, дроти навмисно перехрещені/під'єднані НЕправильно
 * на старті).
 *
 * ── Індекси кольорів ─────────────────────────────────────────────────
 * Кожен лівий контакт із індексом {@code i} (0..3) має "правильний"
 * правий контакт — теж {@code i} (лівий контакт кольору RED завжди
 * шукає правий контакт кольору RED, незалежно від того, як вони
 * намальовані на екрані). {@link #rightSlotFor(int)} — де НАРАЗІ
 * (з'єднано неправильно на старті) стоїть дріт лівого контакту
 * {@code i}; це рандомна перестановка БЕЗ жодної нерухомої точки
 * (жоден дріт не може вже стояти на своєму правильному місці —
 * інакше міні-гра була б тривіальною одразу).
 *
 * Сама модель — чисті дані. Малювання ліній, перетягування мишею,
 * підсвітка активного дроту — усе це клієнтський UI шар
 * (custom Screen), якого немає в цьому пакеті навмисно: сервер має
 * знати лише "яке з'єднання правильне" і "яке з'єднано зараз", щоб
 * перевірити результат, а не як це виглядає.
 */
public final class WireMinigameLayout {

    /** Скільки кольорових контактів по кожній стороні. Дизайн: 4. */
    public static final int SLOT_COUNT = 4;

    private final int[] initialRightSlotForLeft;

    private WireMinigameLayout(int[] initialRightSlotForLeft) {
        this.initialRightSlotForLeft = initialRightSlotForLeft;
    }

    /**
     * Генерує випадкову НЕправильну перестановку (derangement) —
     * жоден лівий контакт {@code i} не з'єднаний на старті з правим
     * контактом {@code i}. Для 4 елементів кількість дерейнджментів
     * невелика (9 з 24 перестановок), тому просте "перегенеруй, якщо
     * випадково вийшла нерухома точка" не деградує в довгий цикл.
     */
    public static WireMinigameLayout random(Random rng) {
        int[] perm;
        do {
            perm = shuffledIndices(rng);
        } while (hasFixedPoint(perm));
        return new WireMinigameLayout(perm);
    }

    private static int[] shuffledIndices(Random rng) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) pool.add(i);
        int[] result = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            result[i] = pool.remove(rng.nextInt(pool.size()));
        }
        return result;
    }

    private static boolean hasFixedPoint(int[] perm) {
        for (int i = 0; i < perm.length; i++) {
            if (perm[i] == i) return true;
        }
        return false;
    }

    /** З яким правим контактом дріт лівого контакту {@code left} з'єднаний ЗАРАЗ на старті. */
    public int initialRightSlotForLeft(int left) {
        return initialRightSlotForLeft[left];
    }

    /** Чи є {@code rightSlot} правильним для лівого контакту {@code left} (той самий колір/індекс). */
    public boolean isCorrectPair(int left, int rightSlot) {
        return left == rightSlot;
    }

    public int slotCount() {
        return SLOT_COUNT;
    }
}

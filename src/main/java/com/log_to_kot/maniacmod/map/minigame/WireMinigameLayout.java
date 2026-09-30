package com.log_to_kot.maniacmod.map.minigame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Розкладка міні-гри "дроти": 4 кольорові контакти зліва, 4 справа,
 * дроти на старті під'єднані НЕправильно, а самі кольори правих
 * контактів ПЕРЕМІШАНІ відносно лівих.
 *
 * ── Два незалежні перемішування ──────────────────────────────────────
 * 1) {@link #rightColorAt(int)} — який колір має правий контакт у
 *    рядку {@code slot}. Це випадкова перестановка БЕЗ нерухомої
 *    точки: жоден правий контакт не стоїть навпроти лівого контакту
 *    свого кольору. Тому правильну пару НІКОЛИ не можна з'єднати прямою
 *    горизонталлю — раніше кольори на обох колодках ішли в тому самому
 *    порядку, і кожен дріт завжди тягнули строго по прямій.
 * 2) {@link #initialRightSlotForLeft(int)} — до якого правого контакту
 *    дріт лівого контакту {@code i} під'єднаний на старті. Це
 *    перестановка контактів, у якій жоден дріт не стоїть на
 *    контакті свого кольору (інакше міні-гра була б тривіальною
 *    одразу).
 *
 * Правильна пара: лівий контакт кольору {@code i} ↔ правий контакт,
 * чий колір теж {@code i} (де саме він стоїть — {@link #correctRightSlotFor}).
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

    /** rightColorAtSlot[slot] = колір (індекс 0..3) правого контакту в рядку slot. */
    private final int[] rightColorAtSlot;
    /** initialRightSlotForLeft[left] = правий контакт, на якому дріт лівого закінчується на старті. */
    private final int[] initialRightSlotForLeft;

    private WireMinigameLayout(int[] rightColorAtSlot, int[] initialRightSlotForLeft) {
        this.rightColorAtSlot = rightColorAtSlot;
        this.initialRightSlotForLeft = initialRightSlotForLeft;
    }

    /**
     * Генерує розкладку: перемішані кольори правої колодки (без
     * прямих пар) і стартове НЕправильне під'єднання дротів.
     *
     * Для 4 елементів дерейнджментів 9 з 24 перестановок, тож
     * "перегенеруй, якщо вийшла нерухома точка" не деградує в довгий цикл.
     */
    public static WireMinigameLayout random(Random rng) {
        int[] rightColors = randomDerangement(rng);

        // Де стоїть правий контакт кожного кольору: rightSlotOfColor[c] = slot.
        int[] rightSlotOfColor = new int[SLOT_COUNT];
        for (int slot = 0; slot < SLOT_COUNT; slot++) rightSlotOfColor[rightColors[slot]] = slot;

        // Стартове під'єднання: initial[i] = rightSlotOfColor[d[i]], де d — дерейнджмент.
        // Тоді initial[i] != rightSlotOfColor[i] ⇔ d[i] != i, тобто жоден дріт
        // не стартує на своєму правильному контакті, а всі контакти різні.
        int[] d = randomDerangement(rng);
        int[] initial = new int[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) initial[i] = rightSlotOfColor[d[i]];

        return new WireMinigameLayout(rightColors, initial);
    }

    private static int[] randomDerangement(Random rng) {
        int[] perm;
        do {
            perm = shuffledIndices(rng);
        } while (hasFixedPoint(perm));
        return perm;
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

    /** Колір (індекс) правого контакту в рядку {@code slot}. */
    public int rightColorAt(int slot) {
        return rightColorAtSlot[slot];
    }

    /** Правий контакт, на якому стоїть колір {@code left} — куди треба довести дріт лівого контакту {@code left}. */
    public int correctRightSlotFor(int left) {
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (rightColorAtSlot[slot] == left) return slot;
        }
        throw new IllegalArgumentException("немає правого контакту кольору " + left);
    }

    /** Чи є правий контакт {@code rightSlot} правильним для лівого контакту {@code left} (той самий колір). */
    public boolean isCorrectPair(int left, int rightSlot) {
        return rightColorAtSlot[rightSlot] == left;
    }

    public int slotCount() {
        return SLOT_COUNT;
    }
}

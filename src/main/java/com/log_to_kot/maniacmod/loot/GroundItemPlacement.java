package com.log_to_kot.maniacmod.loot;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.Random;

/**
 * Опис одного предмета, який спавниться на карті.
 *
 * ── Поворот ──────────────────────────────────────────────────────────
 *   rotationX — випадковий 0–360. Саме він не дає двом однаковим
 *               предметам поруч виглядати копіями.
 *   rotationY — завжди 0.
 *
 * Створюється в {@link GroundItemSpawner#spawnOnMap} — ЄДИНЕ місце,
 * де рахується випадковий поворот при спавні на карті (кидок гравця
 * через {@link GroundItemSpawner#throwFrom} рахує свій кут окремо: там
 * позиція й швидкість визначаються дугою польоту, а не розміткою
 * точки, тому спільна абстракція із цим класом лише заплутала б код).
 * Якщо колись знадобиться інша поведінка повороту при спавні на
 * карті — правка одна, у цьому класі.
 */
public final class GroundItemPlacement {

    /** Поворот по Y для лежачого предмета. Завжди 0. */
    public static final float ROTATION_Y = 0f;

    private final Item item;
    private final BlockPos pos;
    private final float rotationX;

    private GroundItemPlacement(Item item, BlockPos pos, float rotationX) {
        this.item = item;
        this.pos = pos;
        this.rotationX = rotationX;
    }

    /** Стандартне розміщення: випадковий X, нульовий Y. */
    public static GroundItemPlacement of(Item item, BlockPos pos, Random rng) {
        return new GroundItemPlacement(item, pos, rng.nextFloat() * 360f);
    }

    public Item item()        { return item; }
    public BlockPos pos()     { return pos; }
    public float rotationX()  { return rotationX; }
    public float rotationY()  { return ROTATION_Y; }
}

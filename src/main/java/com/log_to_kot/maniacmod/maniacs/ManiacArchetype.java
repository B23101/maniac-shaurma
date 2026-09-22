package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.traps.TrapArchetype;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Базовий контракт маньяка.
 *
 * ── Жорсткі межі, які НЕ налаштовуються ──────────────────────────────
 * У маньяка немає інвентаря, тому клавіші 1–4 вільні:
 *   1 / 2 / 3 — здібності (максимум {@link #MAX_ABILITIES})
 *   5 / 6 / 7 — пастки (максимум {@link #MAX_TRAP_SLOTS})
 *   ЛКМ      — удар
 * Ці числа — константи, а не конфіг: збільшити їх нікуди, бо клавіш
 * рівно стільки. Архетип, що віддасть більше, падає одразу при
 * реєстрації, а не мовчки загубить четверту здібність у грі.
 *
 * ── Що архетип задає, а що успадковує ────────────────────────────────
 * Підклас задає дані (габарити, зброю, здібності, пастки) і за потреби
 * перевизначає {@link #attackRangeBlocks()} чи
 * {@link #attackCooldownTicks()}. Якщо не перевизначає — працює
 * базове значення з конфігу, тобто одна правка у yml змінює всіх
 * маньяків одразу.
 */
public abstract class ManiacArchetype {

    /** Здібності: клавіші 1, 2, 3. */
    public static final int MAX_ABILITIES = 3;

    /** Пастки: клавіші 5, 6, 7. */
    public static final int MAX_TRAP_SLOTS = 3;

    /** У маньяка немає інвентаря — звідси й вільні клавіші 1–4. */
    public static final int HOTBAR_SLOTS = 0;

    protected final String id;
    protected final String displayName;
    protected final float  hitboxWidth;
    protected final float  hitboxHeight;
    protected final float  eyeHeight;
    protected final float  fovMultiplier;
    protected final float  modelScale;

    protected ManiacArchetype(String id, String displayName,
                              float hitboxWidth, float hitboxHeight, float eyeHeight,
                              float fovMultiplier, float modelScale) {
        this.id = id;
        this.displayName = displayName;
        this.hitboxWidth = hitboxWidth;
        this.hitboxHeight = hitboxHeight;
        this.eyeHeight = eyeHeight;
        this.fovMultiplier = fovMultiplier;
        this.modelScale = modelScale;
    }

    // ── Контракт підкласу ────────────────────────────────────────────────

    /** Предмет зброї цього маньяка. */
    public abstract Item weaponItem();

    /** Здібності на клавіші 1, 2, 3 — рівно в цьому порядку. */
    public abstract List<Ability> abilities();

    /** Пастки, з яких маньяк обирає до {@code trapsPerMatch} у меню; клавіші 5, 6, 7. */
    public abstract List<TrapArchetype> traps();

    // ── Спільна логіка ───────────────────────────────────────────────────

    /**
     * Дальність удару в блоках. За замовчуванням — базове значення з
     * конфігу; довгорукий маньяк перевизначає цей метод.
     */
    public double attackRangeBlocks() {
        return ManiacConfigs.get(ConfigSchema.ATTACK_RANGE_BLOCKS);
    }

    /** Перезарядка удару в тіках. */
    public int attackCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.ATTACK_COOLDOWN_TICKS);
    }

    /** Шкода за удар. */
    public int attackDamage() {
        return ManiacConfigs.get(ConfigSchema.ATTACK_DAMAGE);
    }

    /**
     * Множник швидкості ходьби відносно ЗВИЧАЙНОЇ ходьби гравця. Маньяк
     * не має спринту взагалі: це і є його «біг» (1.2 = на 20% швидше).
     * За замовчуванням — з конфігу, тож одна правка змінює всіх;
     * швидший чи повільніший маньяк перевизначає цей метод.
     */
    public double speedMultiplier() {
        return ManiacConfigs.get(ConfigSchema.MANIAC_SPEED_MULTIPLIER);
    }

    /**
     * Здібність за номером клавіші 1–3. null, якщо слот порожній —
     * маньяк може мати й одну здібність.
     */
    public final Ability abilityAt(int slot) {
        List<Ability> list = abilities();
        return slot >= 0 && slot < list.size() ? list.get(slot) : null;
    }

    /** Пастка за номером слота 0–2 (5, 6, 7). null, якщо слот порожній. */
    public final TrapArchetype trapAt(int slot) {
        List<TrapArchetype> list = traps();
        return slot >= 0 && slot < list.size() ? list.get(slot) : null;
    }

    /** Перевірка меж. Викликається реєстром при реєстрації. */
    final void validate() {
        if (abilities().size() > MAX_ABILITIES) {
            throw new IllegalStateException(id + ": здібностей "
                + abilities().size() + ", а клавіш лише " + MAX_ABILITIES + " (1/2/3).");
        }
        if (traps().size() > MAX_TRAP_SLOTS) {
            throw new IllegalStateException(id + ": пасток "
                + traps().size() + ", а клавіш лише " + MAX_TRAP_SLOTS + " (5/6/7).");
        }
    }

    public final String id()            { return id; }
    public final String displayName()   { return displayName; }
    public final float  hitboxWidth()   { return hitboxWidth; }
    public final float  hitboxHeight()  { return hitboxHeight; }
    public final float  eyeHeight()     { return eyeHeight; }
    public final float  fovMultiplier() { return fovMultiplier; }
    public final float  modelScale()    { return modelScale; }
}

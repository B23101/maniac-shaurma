package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Базовий контракт для будь-якого типу пастки маньяка.
 *
 * Кожна конкретна пастка (BearTrap, ElectricWire, RopeBind, Mine,
 * майбутні...) — підклас, що задає лише дані (кулдаун розміщення,
 * тривалість ефекту) і реалізує unikальний ефект застосування на
 * гравця. Спільні правила розміщення (не можна під гравцем, не
 * можна ближче N блоків — див. TrapPlacementRules) лежать окремо
 * і застосовуються до ВСІХ пасток однаково.
 *
 * ЗМІНА ТУТ → впливає одразу на всі пастки. Наприклад, якщо
 * знадобиться спільний модифікатор тривалості ефекту (складність
 * гри), це один метод тут.
 */
public abstract class TrapArchetype {

    protected final String id;               // "bear_trap", "electric_wire", ...
    protected final String displayName;

    protected TrapArchetype(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    // ── Контракт, який кожна пастка МУСИТЬ реалізувати ──────────────────────

    /** Ефект на гравця, що потрапив у пастку (уповільнення, урон, зв'язування тощо). */
    public abstract void applyEffect(ServerPlayer victim, BlockPos trapPos);

    /** Чи спрацьовує пастка для цього гравця прямо зараз (напр. виживший, не маньяк). */
    public abstract boolean shouldTrigger(ServerPlayer candidate);

    // ── Спільна логіка (ОДНА реалізація для всіх пасток) ────────────────────

    /**
     * Кулдаун розміщення. За замовчуванням — спільне значення з
     * конфігу; пастка, якій потрібен свій, перевизначає цей метод.
     *
     * Окремого поля немає навмисно: інакше число жило б і в конфігу,
     * і в конструкторі кожної пастки, і рано чи пізно розійшлося б.
     */
    public int placementCooldownTicks() {
        return ManiacConfigs.get(ConfigSchema.TRAP_PLACE_COOLDOWN_TICKS);
    }

    public final String id()          { return id; }
    public final String displayName() { return displayName; }
}

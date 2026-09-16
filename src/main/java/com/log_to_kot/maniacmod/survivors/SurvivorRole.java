package com.log_to_kot.maniacmod.survivors;

/**
 * Базовий контракт ролі виживого. Зараз є лише один тип —
 * звичайний виживий (SurvivorRole.DEFAULT) — але структура готова
 * до додавання інших ролей у майбутньому (напр. "медик" зі
 * швидшим лікуванням, "розвідник" з довшою підсвіткою), без
 * переписування SurvivorState чи ManiacGameManager.
 *
 * Дані з презентації "Виживші":
 *   - Система хп: 100 хп на початку гри
 *   - Стаміна: тратиться при бігу/стрибку
 *   - Макс. слотів у інвентарі: 4
 *   - Сила підсвітки: перезарядка 30с
 *
 * v3-еквівалент: game/SurvivorData.java мав спрощену модель
 * (lives 1-3, без стаміни/підсвітки) — ця роль її замінює.
 */
public abstract class SurvivorRole {

    protected final String id;
    protected final String displayName;
    protected final int    maxHp;
    protected final int    maxInventorySlots;
    protected final int    flashlightCooldownTicks;

    protected SurvivorRole(String id, String displayName, int maxHp,
                            int maxInventorySlots, int flashlightCooldownTicks) {
        this.id = id;
        this.displayName = displayName;
        this.maxHp = maxHp;
        this.maxInventorySlots = maxInventorySlots;
        this.flashlightCooldownTicks = flashlightCooldownTicks;
    }

    // ── Контракт для конкретної ролі ────────────────────────────────────────

    /** Швидкість витрати стаміни при бігу (одиниць/тік) — роль може мати свою. */
    public abstract float staminaDrainRate();

    /** Швидкість відновлення стаміни в стані спокою. */
    public abstract float staminaRegenRate();

    // ── Спільні геттери ──────────────────────────────────────────────────────

    public final String id()                      { return id; }
    public final String displayName()              { return displayName; }
    public final int    maxHp()                    { return maxHp; }
    public final int    maxInventorySlots()         { return maxInventorySlots; }
    public final int    flashlightCooldownTicks()   { return flashlightCooldownTicks; }
}

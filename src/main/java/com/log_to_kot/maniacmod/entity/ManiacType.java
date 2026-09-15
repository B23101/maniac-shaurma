package com.log_to_kot.maniacmod.entity;

import com.log_to_kot.maniacmod.items.ModItems;
import net.minecraft.world.item.Item;

/**
 * Defines every playable maniac character.
 *
 * Кожен маньяк має:
 *  - Свою унікальну зброю (weaponItem)
 *  - Своє кулдаун між ударами (attackCooldownTicks)
 *  - Свої фізичні параметри (hitbox, fov, scale)
 *
 * Пастки (капкан, дріт, мотузка, міна) — однакові для всіх маньяків.
 */
public enum ManiacType {

    // ── Чакі ─────────────────────────────────────────────────────────────────
    // Маленький, швидкий, ніж — короткий кулдаун
    CHUCKY(
        /* hitboxWidth       */ 0.6f,
        /* hitboxHeight      */ 0.5f,
        /* eyeHeight         */ 0.25f,
        /* fovMultiplier     */ 0.90f,
        /* displayName       */ "Чакі",
        /* modelScale        */ 0.278f,
        /* weaponKey         */ "chucky_knife",
        /* attackCooldownTicks*/ 120   // 6 секунд
    ),

    // ── Слендермен ───────────────────────────────────────────────────────────
    // Великий, повільний, довгі руки — більший кулдаун
    SLENDERMAN(
        /* hitboxWidth       */ 0.6f,
        /* hitboxHeight      */ 4.0f,
        /* eyeHeight         */ 3.6f,
        /* fovMultiplier     */ 1.40f,
        /* displayName       */ "Слендермен",
        /* modelScale        */ 2.222f,
        /* weaponKey         */ "slender_tentacle",
        /* attackCooldownTicks*/ 200   // 10 секунд
    );

    // ─────────────────────────────────────────────────────────────────────────

    public final float  hitboxWidth;
    public final float  hitboxHeight;
    public final float  eyeHeight;
    public final float  fovMultiplier;
    public final String displayName;
    public final float  modelScale;
    /** Registry key для зброї цього маньяка */
    public final String weaponKey;
    /** Кулдаун між ударами в тіках */
    public final int    attackCooldownTicks;

    ManiacType(float hitboxWidth, float hitboxHeight, float eyeHeight,
               float fovMultiplier, String displayName, float modelScale,
               String weaponKey, int attackCooldownTicks) {
        this.hitboxWidth         = hitboxWidth;
        this.hitboxHeight        = hitboxHeight;
        this.eyeHeight           = eyeHeight;
        this.fovMultiplier       = fovMultiplier;
        this.displayName         = displayName;
        this.modelScale          = modelScale;
        this.weaponKey           = weaponKey;
        this.attackCooldownTicks = attackCooldownTicks;
    }

    /**
     * Повертає предмет зброї для цього маньяка.
     * Викликається при видачі предметів на старті гри.
     */
    public Item getWeaponItem() {
        return switch (this) {
            case CHUCKY    -> ModItems.CHUCKY_KNIFE.get();
            case SLENDERMAN -> ModItems.SLENDER_TENTACLE.get();
        };
    }

    public ManiacEntity.ManiacScale toRenderScale() {
        if (hitboxHeight <= 0.6f) return ManiacEntity.ManiacScale.SMALL;
        if (hitboxHeight <= 2.0f) return ManiacEntity.ManiacScale.NORMAL;
        if (hitboxHeight <= 3.0f) return ManiacEntity.ManiacScale.TALL;
        return ManiacEntity.ManiacScale.GIANT;
    }
}

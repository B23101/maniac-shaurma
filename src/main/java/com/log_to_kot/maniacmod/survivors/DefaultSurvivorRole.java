package com.log_to_kot.maniacmod.survivors;

/**
 * Звичайний виживий — єдина роль на зараз. Дані з презентації
 * "Виживші": 100 хп, 4 слоти інвентаря, підсвітка з перезарядкою 30с.
 */
public class DefaultSurvivorRole extends SurvivorRole {

    public static final String ID = "default";

    public DefaultSurvivorRole() {
        super(
            /* id                        */ ID,
            /* displayName               */ "Виживий",
            /* maxHp                     */ 100,
            /* maxInventorySlots         */ 4,
            /* flashlightCooldownTicks   */ 600 // 30с
        );
    }

    @Override
    public float staminaDrainRate() {
        // TODO: перенести реальне число зі схеми "При русі гравця чи
        // стрибка тратиться стаміна" — точне число не було зафіксовано
        // в v3 коді (система стаміни там взагалі відсутня, лише lives).
        return 1.0f;
    }

    @Override
    public float staminaRegenRate() {
        // TODO: реальне число регенерації стаміни в стані спокою
        return 0.5f;
    }
}

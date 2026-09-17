package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;

/**
 * Звичайний виживий — єдина роль на зараз. Дані з презентації
 * "Виживші": 100 хп, 4 слоти інвентаря, підсвітка з перезарядкою 30с.
 *
 * Значення читаються з {@link ConfigSchema} щоразу при створенні ролі
 * (одна роль на матч на гравця — див. {@code SurvivorRegistry.defaultRole()}),
 * а не захардкоджені в конструкторі: адмін, що змінив maxHp/inventorySlots
 * у survivors.yml, мусить побачити ефект без перекомпіляції.
 */
public class DefaultSurvivorRole extends SurvivorRole {

    public static final String ID = "default";

    public DefaultSurvivorRole() {
        super(
            /* id                        */ ID,
            /* displayName               */ "Виживий",
            /* maxHp                     */ ManiacConfigs.get(ConfigSchema.SURVIVOR_MAX_HP),
            /* maxInventorySlots         */ ManiacConfigs.get(ConfigSchema.SURVIVOR_SLOTS),
            /* flashlightCooldownTicks   */ ManiacConfigs.get(ConfigSchema.FLASHLIGHT_COOLDOWN_TICKS)
        );
    }

    @Override
    public float staminaDrainRate() {
        return (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_DRAIN_PER_TICK);
    }

    @Override
    public float staminaRegenRate() {
        return (float) (double) ManiacConfigs.get(ConfigSchema.STAMINA_REGEN_PER_TICK);
    }
}

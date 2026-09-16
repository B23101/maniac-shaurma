package com.log_to_kot.maniacmod.abilities;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Базова реалізація Ability зі спільним обліком кулдауну per-гравець
 * (на випадок кількох маньяків одночасно в майбутніх режимах, або
 * просто щоб не дублювати Map<UUID, Long> у кожній здібності).
 *
 * Конкретна здібність (DashAbility, InvisibilityAbility...) реалізує
 * лише doActivate(...) — суто ігровий ефект; перевірку "чи готовий
 * кулдаун" і його запуск бере на себе цей клас.
 */
public abstract class AbstractAbility implements Ability {

    private final Map<UUID, Long> lastUsedTick = new HashMap<>();
    private final int cooldownTicks;

    protected AbstractAbility(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }

    @Override
    public final int cooldownTicks() {
        return cooldownTicks;
    }

    @Override
    public final boolean activate(ServerPlayer maniacPlayer) {
        long now = maniacPlayer.getServer().getTickCount();
        Long last = lastUsedTick.get(maniacPlayer.getUUID());
        if (last != null && now - last < cooldownTicks) {
            return false; // кулдаун ще не минув
        }
        boolean success = doActivate(maniacPlayer);
        if (success) {
            lastUsedTick.put(maniacPlayer.getUUID(), now);
        }
        return success;
    }

    /** Конкретний ігровий ефект здібності — реалізує підклас. */
    protected abstract boolean doActivate(ServerPlayer maniacPlayer);
}

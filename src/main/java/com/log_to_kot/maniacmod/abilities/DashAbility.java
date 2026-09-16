package com.log_to_kot.maniacmod.abilities;

import net.minecraft.server.level.ServerPlayer;

/**
 * ПРИКЛАД (заглушка) — рвучкий ривок уперед. Показує форму реальної
 * здібності: кулдаун і логіку обробляє AbstractAbility, тут лише
 * ігровий ефект.
 *
 * Не підключена до жодного маньяка (ManiacArchetype.abilities()
 * повертає List.of() поки що) — приклад для наступного кроку
 * перенесення логіки.
 */
public class DashAbility extends AbstractAbility {

    private final double distanceBlocks;

    public DashAbility(double distanceBlocks, int cooldownTicks) {
        super(cooldownTicks);
        this.distanceBlocks = distanceBlocks;
    }

    @Override
    public String id() {
        return "dash";
    }

    @Override
    protected boolean doActivate(ServerPlayer maniacPlayer) {
        // TODO: реальна логіка ривка (векторний поштовх/телепорт по
        // напрямку погляду на distanceBlocks) — переноситься окремим
        // кроком, коли каркас затверджено.
        return true;
    }
}

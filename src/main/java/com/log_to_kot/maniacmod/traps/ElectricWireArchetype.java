package com.log_to_kot.maniacmod.traps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Щіпи (дріт) — накидають при попаданні, не дає вибратись без
 * ножиць. Кулдаун повторного спрацювання (з v3 ElectricWireState):
 * 10 секунд (200 тіків) після звільнення.
 */
public class ElectricWireArchetype extends TrapArchetype {

    public ElectricWireArchetype() {
        super("electric_wire", "Щіпи");
    }

    @Override
    public void applyEffect(ServerPlayer victim, BlockPos trapPos) {
        // TODO: перенести з v3 ElectricWireState (ловля, знерухомлення до ножиць)
        throw new UnsupportedOperationException("applyEffect() ще не перенесено з v3");
    }

    @Override
    public boolean shouldTrigger(ServerPlayer candidate) {
        // TODO: перенести перевірку "виживший, не маньяк" з v3
        return true;
    }

    // ── Ще не реалізовано ───────────────────────────────────────────────
    // Форма розміщення й тіло пастки з'являться разом із самою пасткою.
    // Пастка НЕ зареєстрована в TrapRegistry, тож до гравця ці методи не
    // дійдуть; кидаємо явну помилку, а не мовчки «нічого не робимо».

    @Override
    public PlacementShape placementShape() {
        throw new UnsupportedOperationException("ElectricWireArchetype: розміщення ще не реалізовано");
    }

    @Override
    public PlacementResult validatePlacement(net.minecraft.world.level.BlockGetter level, BlockPos floor) {
        throw new UnsupportedOperationException("ElectricWireArchetype: розміщення ще не реалізовано");
    }

    @Override
    public net.minecraft.world.entity.Entity spawn(net.minecraft.server.level.ServerLevel level, BlockPos floor, ServerPlayer placer) {
        throw new UnsupportedOperationException("ElectricWireArchetype: розміщення ще не реалізовано");
    }
}

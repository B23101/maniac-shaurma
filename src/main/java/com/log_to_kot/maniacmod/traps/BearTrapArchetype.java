package com.log_to_kot.maniacmod.traps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Капкан — ставить і підсікає гравця, знижує -10 хп. Виживається
 * ломом. Кулдаун повторного спрацювання (з v3 BearTrapState):
 * 8 секунд (160 тіків) після звільнення.
 */
public class BearTrapArchetype extends TrapArchetype {

    public BearTrapArchetype() {
        super("bear_trap", "Капкан");
    }

    @Override
    public void applyEffect(ServerPlayer victim, BlockPos trapPos) {
        // TODO: перенести з v3 BearTrapState (-10хп, знерухомлення, ловля)
        throw new UnsupportedOperationException("applyEffect() ще не перенесено з v3");
    }

    @Override
    public boolean shouldTrigger(ServerPlayer candidate) {
        // TODO: перенести перевірку "виживший, не маньяк" з v3
        return true;
    }
}

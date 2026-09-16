package com.log_to_kot.maniacmod.traps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Розтяжка (міна) — невидима для виживших без викрутки в руці.
 * При спрацюванні: -1 ❤ + сповільнення 3 секунди (з v3 MineState).
 * Знешкодження: ПКМ з викруткою 2 секунди (40 тіків).
 */
public class MineArchetype extends TrapArchetype {

    public MineArchetype() {
        super("mine", "Розтяжка");
    }

    @Override
    public void applyEffect(ServerPlayer victim, BlockPos trapPos) {
        // TODO: перенести з v3 MineState (-1❤, сповільнення 3с, знешкодження викруткою)
        throw new UnsupportedOperationException("applyEffect() ще не перенесено з v3");
    }

    @Override
    public boolean shouldTrigger(ServerPlayer candidate) {
        // TODO: перенести перевірку "виживший, не маньяк" з v3
        return true;
    }
}

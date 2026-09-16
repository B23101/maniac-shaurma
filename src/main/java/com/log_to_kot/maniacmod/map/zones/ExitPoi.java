package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;

/**
 * Двері виходу — v3-еквівалент game/ExitState.java.
 * Фіксований час розблокування: 5 секунд (100 тіків).
 */
public class ExitPoi extends PointOfInterestArchetype {

    private static final int UNLOCK_TICKS_NEEDED = 100; // 5 секунд

    public ExitPoi(BlockPos pos) {
        super(pos);
    }

    @Override
    protected int ticksNeeded() {
        return UNLOCK_TICKS_NEEDED;
    }
}

package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import java.util.UUID;

/**
 * Electric wire state — cooldown after catching + after being cut.
 * Catch cooldown: 10 seconds (200 ticks).
 */
public class ElectricWireState {

    private static final int CATCH_COOLDOWN_TICKS = 200;

    private final BlockPos pos;
    private UUID caughtUUID    = null;
    private boolean destroyed  = false;
    private int cooldownTicks  = 0;

    public ElectricWireState(BlockPos pos) { this.pos = pos; }

    public BlockPos getPos()       { return pos; }
    public boolean isDestroyed()   { return destroyed; }
    public boolean isActive()      { return !destroyed && caughtUUID == null && cooldownTicks == 0; }
    public boolean hasCaught()     { return caughtUUID != null; }
    public UUID getCaughtUUID()    { return caughtUUID; }

    public boolean catchSurvivor(UUID uuid) {
        if (caughtUUID != null || cooldownTicks > 0) return false;
        caughtUUID = uuid;
        return true;
    }

    public void tick() {
        if (cooldownTicks > 0) cooldownTicks--;
    }

    public void freeWithScissors() {
        caughtUUID    = null;
        cooldownTicks = CATCH_COOLDOWN_TICKS;
    }
}

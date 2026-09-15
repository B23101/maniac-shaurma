package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;

/**
 * Tracks unlocking progress for an exit door.
 */
public class ExitState {

    private static final int UNLOCK_TICKS_NEEDED = 100; // 5 seconds

    private final BlockPos pos;
    private int unlockProgress = 0;
    private boolean unlocked   = false;

    public ExitState(BlockPos pos) { this.pos = pos; }

    public BlockPos getPos()    { return pos; }
    public boolean isUnlocked() { return unlocked; }
    public int getProgress()    { return unlockProgress * 100 / UNLOCK_TICKS_NEEDED; }

    public boolean addProgress(int ticks) {
        if (unlocked) return false;
        unlockProgress += ticks;
        if (unlockProgress >= UNLOCK_TICKS_NEEDED) {
            unlocked = true;
            return true;
        }
        return false;
    }
}

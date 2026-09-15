package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bear trap state — cooldown before it can catch again after being freed.
 * Cooldown: 8 seconds (160 ticks) after being sprung.
 */
public class BearTrapState {

    private static final int CATCH_COOLDOWN_TICKS = 160;
    private static final int CROWBAR_TICKS_NEEDED = 60; // 3 seconds to pry open

    private final BlockPos pos;
    private UUID caughtUUID   = null;
    private boolean broken    = false;
    private int cooldownTicks = 0;

    // Per-helper prying progress
    private UUID   pryingHelperUUID = null;
    private int    pryingTicks      = 0;

    public BearTrapState(BlockPos pos) { this.pos = pos; }

    public BlockPos getPos() { return pos; }

    public boolean isBroken() { return broken; }

    /** True = no one caught, not on cooldown → can catch */
    public boolean isEmpty() { return caughtUUID == null && cooldownTicks == 0; }

    public boolean hasCaught() { return caughtUUID != null; }

    public UUID getCaughtUUID() { return caughtUUID; }

    /** Catches a survivor — returns true on first catch */
    public boolean catchSurvivor(UUID uuid) {
        if (caughtUUID != null || cooldownTicks > 0) return false;
        caughtUUID = uuid;
        return true;
    }

    /** Tick cooldown (called every game tick) */
    public void tick() {
        if (cooldownTicks > 0) cooldownTicks--;
    }

    // ── Crowbar prying ───────────────────────────────────────────────────────

    public UUID getPryingHelperUUID() { return pryingHelperUUID; }

    public int getPryingProgress() {
        return pryingTicks * 100 / CROWBAR_TICKS_NEEDED;
    }

    /**
     * Called every tick while a helper is holding crowbar near trap.
     * @return true when trap is fully pried open (survivor freed)
     */
    public boolean tickCrowbar(UUID helperUUID) {
        if (caughtUUID == null) return false;
        pryingHelperUUID = helperUUID;
        pryingTicks++;
        if (pryingTicks >= CROWBAR_TICKS_NEEDED) {
            caughtUUID       = null;
            pryingHelperUUID = null;
            pryingTicks      = 0;
            cooldownTicks    = CATCH_COOLDOWN_TICKS;
            return true;
        }
        return false;
    }

    public void cancelPrying(UUID helperUUID) {
        if (helperUUID.equals(pryingHelperUUID)) {
            pryingHelperUUID = null;
            pryingTicks      = 0;
        }
    }
}

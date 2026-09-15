package com.log_to_kot.maniacmod.game;

import java.util.UUID;

/**
 * Rope bind state.
 * After being freed the bind is simply removed (no reuse).
 */
public class RopeBindState {

    private final UUID   boundUUID;
    private final double x, y, z;
    private boolean freed = false;

    public RopeBindState(UUID boundUUID, double x, double y, double z) {
        this.boundUUID = boundUUID;
        this.x = x; this.y = y; this.z = z;
    }

    public UUID    getBoundUUID() { return boundUUID; }
    public double  getX()        { return x; }
    public double  getY()        { return y; }
    public double  getZ()        { return z; }
    public boolean isFreed()     { return freed; }

    public void freeWithScissors() { freed = true; }
}

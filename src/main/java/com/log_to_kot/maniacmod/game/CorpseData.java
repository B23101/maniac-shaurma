package com.log_to_kot.maniacmod.game;

import com.log_to_kot.maniacmod.config.ManiacConfig;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/**
 * Represents a dead survivor's corpse.
 *
 * The corpse persists for REVIVE_WINDOW_SECONDS after death.
 * A 3D lying player model (CorpseEntity / ArmorStand) is spawned
 * at the death location to represent it visually.
 *
 * Revival: another survivor right-clicks with Defibrillator within 2.5 blocks.
 * Cost: reviver loses 1 HP. Revived gets 1 HP.
 * Window: 3 minutes.
 */
public class CorpseData {

    /** How long (seconds) a corpse can be revived — taken from config. */
    public static int REVIVE_WINDOW_SECONDS = 180; // overridden by ManiacConfig at game start

    private final UUID   deadUUID;
    private final String deadName;
    private final Vec3   position;
    private final float  yRot;
    private final long   deathTimestamp;

    /** UUID of the ArmorStand / CorpseEntity spawned in the world */
    private UUID corpseEntityUUID = null;

    public CorpseData(UUID deadUUID, String deadName, Vec3 position, float yRot) {
        this.deadUUID       = deadUUID;
        this.deadName       = deadName;
        this.position       = position;
        this.yRot           = yRot;
        this.deathTimestamp = System.currentTimeMillis();
    }

    public UUID   getDeadUUID()         { return deadUUID; }
    public String getDeadName()         { return deadName; }
    public Vec3   getPosition()         { return position; }
    public float  getYRot()             { return yRot; }
    public UUID   getCorpseEntityUUID() { return corpseEntityUUID; }

    public void setCorpseEntityUUID(UUID uuid) { this.corpseEntityUUID = uuid; }

    /** True while revival is still possible. */
    public boolean canBeRevived() {
        long elapsed = (System.currentTimeMillis() - deathTimestamp) / 1000L;
        return elapsed < ManiacConfig.getReviveWindow();
    }

    /** Remaining seconds in the revival window. */
    public int secondsRemaining() {
        long elapsed = (System.currentTimeMillis() - deathTimestamp) / 1000L;
        return (int) Math.max(0, ManiacConfig.getReviveWindow() - elapsed);
    }
}

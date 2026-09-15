package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * A square escape zone defined by centre + half-side (radius).
 * Any survivor who steps inside this zone escapes and wins.
 * The zone is (2*radius+1) × (2*radius+1) blocks.
 */
public class EscapeZone {

    private final BlockPos centre;
    private final int radius;

    public EscapeZone(BlockPos centre, int radius) {
        this.centre = centre;
        this.radius = radius;
    }

    /**
     * Returns true if the player's current block position is inside this zone.
     * Checks X and Z only (ignores Y) so tall/short players all trigger it.
     */
    public boolean contains(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        int dx = Math.abs(pos.getX() - centre.getX());
        int dz = Math.abs(pos.getZ() - centre.getZ());
        return dx <= radius && dz <= radius;
    }

    public BlockPos getCentre() { return centre; }
    public int getRadius()      { return radius; }

    public String info() {
        int side = radius * 2 + 1;
        return "центр " + centre.toShortString()
            + " розмір §f" + side + "×" + side + " §7бл";
    }
}

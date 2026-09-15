package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * A square zone where the maniac is teleported at game start.
 * Picks a random solid-surface position inside the zone, same validation as ItemSpawnZone.
 */
public class ManiacSpawnZone {

    private final BlockPos centre;
    private final int radius;
    private static final Random RNG = new Random();
    private static final int MAX_ATTEMPTS = 50;

    public ManiacSpawnZone(BlockPos centre, int radius) {
        this.centre = centre;
        this.radius = radius;
    }

    /**
     * Teleports the maniac to a random valid surface position inside the zone.
     * Validates: solid block below, air block at player level.
     */
    public void spawnManiac(ServerLevel level, ServerPlayer maniac) {
        BlockPos spawnPos = null;
        int side = radius * 2 + 1;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int dx = RNG.nextInt(side) - radius;
            int dz = RNG.nextInt(side) - radius;
            int x  = centre.getX() + dx;
            int z  = centre.getZ() + dz;

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos groundPos = new BlockPos(x, surfaceY - 1, z);
            BlockPos airPos    = new BlockPos(x, surfaceY,     z);

            if (!level.getBlockState(groundPos).isSolid()) continue;
            if (!level.getBlockState(airPos).isAir())      continue;

            spawnPos = groundPos;
            break;
        }

        // Fallback: centre of zone
        if (spawnPos == null) {
            int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                centre.getX(), centre.getZ());
            spawnPos = new BlockPos(centre.getX(), cy - 1, centre.getZ());
        }

        maniac.teleportTo(
            spawnPos.getX() + 0.5,
            spawnPos.getY() + 1.0,
            spawnPos.getZ() + 0.5
        );
    }

    public BlockPos getCentre() { return centre; }
    public int getRadius()      { return radius; }

    public String info() {
        int side = radius * 2 + 1;
        return "центр " + centre.toShortString()
            + " розмір §f" + side + "×" + side + " §7бл";
    }
}

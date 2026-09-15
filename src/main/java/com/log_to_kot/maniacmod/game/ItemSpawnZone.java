package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Random;

/**
 * Square item spawn zone.
 * Defined by a centre block + half-side (radius) in blocks.
 *
 * Optional Y-range (minY / maxY): when set, items spawn on solid ground
 * within that Y range. Useful for basements (low Y) or rooftops (high Y).
 *
 * Items spawn ONCE — only at game start. Subsequent calls to spawnItems()
 * are silently ignored if items have already been spawned.
 */
public class ItemSpawnZone {

    private final BlockPos centre;
    private final int radius;

    /** Optional Y constraints. -1 means "no constraint". */
    private int minY = -1;
    private int maxY = -1;

    /** Guard: prevent double-spawn */
    private boolean spawned = false;

    private static final Random RNG = new Random();

    public ItemSpawnZone(BlockPos centre, int radius) {
        this.centre = centre;
        this.radius = radius;
    }

    /** Set a Y range so items only appear between minY and maxY (inclusive). */
    public ItemSpawnZone withYRange(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
        return this;
    }

    public BlockPos getCentre() { return centre; }
    public int getRadius()      { return radius; }
    public boolean hasYRange()  { return minY != -1 && maxY != -1; }

    /**
     * Spawns all loot entries on the ground within the zone.
     * Called only once per game — subsequent calls are no-ops.
     */
    public void spawnItems(ServerLevel level, List<ItemSpawnZone.LootEntry> lootTable) {
        if (spawned) return;
        spawned = true;

        for (LootEntry entry : lootTable) {
            if (RNG.nextFloat() > entry.chance) continue;

            BlockPos spawnPos = hasYRange()
                ? randomSurfacePosInRange(level)
                : randomSurfacePos(level);
            if (spawnPos == null) continue;

            ItemEntity itemEntity = new ItemEntity(
                level,
                spawnPos.getX() + 0.5,
                spawnPos.getY() + 1.0,
                spawnPos.getZ() + 0.5,
                new ItemStack(entry.item, entry.count)
            );
            itemEntity.setDeltaMovement(
                (RNG.nextDouble() - 0.5) * 0.15,
                0.12,
                (RNG.nextDouble() - 0.5) * 0.15
            );
            itemEntity.setPickUpDelay(20);
            level.addFreshEntity(itemEntity);
        }
    }

    /** Reset the spawn guard (called on game reset so zones can be reused). */
    public void resetSpawn() { spawned = false; }

    // ── Surface search helpers ───────────────────────────────────────────────

    private static final int MAX_SURFACE_ATTEMPTS = 30;

    private BlockPos randomSurfacePos(ServerLevel level) {
        int side = radius * 2 + 1;
        for (int attempt = 0; attempt < MAX_SURFACE_ATTEMPTS; attempt++) {
            int dx = RNG.nextInt(side) - radius;
            int dz = RNG.nextInt(side) - radius;
            int x  = centre.getX() + dx;
            int z  = centre.getZ() + dz;

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos groundPos = new BlockPos(x, surfaceY - 1, z);
            BlockPos airPos    = new BlockPos(x, surfaceY,     z);

            if (!level.getBlockState(groundPos).isSolid()) continue;
            if (!level.getBlockState(airPos).isAir())      continue;
            return groundPos;
        }
        return null;
    }

    /**
     * Y-constrained surface search.
     * Scans downward from maxY to minY for the first solid+air pair.
     * Handles basements (low Y) and rooftops (high Y).
     */
    private BlockPos randomSurfacePosInRange(ServerLevel level) {
        int side = radius * 2 + 1;
        for (int attempt = 0; attempt < MAX_SURFACE_ATTEMPTS; attempt++) {
            int dx = RNG.nextInt(side) - radius;
            int dz = RNG.nextInt(side) - radius;
            int x  = centre.getX() + dx;
            int z  = centre.getZ() + dz;

            for (int y = maxY; y >= minY; y--) {
                BlockPos groundPos = new BlockPos(x, y, z);
                BlockPos airPos    = new BlockPos(x, y + 1, z);
                if (!level.getBlockState(groundPos).isSolid()) continue;
                if (!level.getBlockState(airPos).isAir())      continue;
                return groundPos;
            }
        }
        return null;
    }

    public void spawnSurvivors(ServerLevel level,
                               java.util.List<net.minecraft.server.level.ServerPlayer> survivors) {
        for (net.minecraft.server.level.ServerPlayer p : survivors) {
            BlockPos pos = hasYRange()
                ? randomSurfacePosInRange(level)
                : randomSurfacePos(level);
            if (pos == null) continue;
            p.teleportTo(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                p.getYRot(), p.getXRot());
        }
    }

    public String info() {
        int side = radius * 2 + 1;
        String base = "центр " + centre.toShortString() + " розмір " + side + "×" + side + " бл"
            + " (" + (side * side) + " бл²)";
        if (hasYRange()) base += " [Y: " + minY + "–" + maxY + "]";
        return base;
    }

    public static class LootEntry {
        public final net.minecraft.world.item.Item item;
        public final int   count;
        public final float chance;

        public LootEntry(net.minecraft.world.item.Item item, int count, float chance) {
            this.item   = item;
            this.count  = count;
            this.chance = chance;
        }
    }
}

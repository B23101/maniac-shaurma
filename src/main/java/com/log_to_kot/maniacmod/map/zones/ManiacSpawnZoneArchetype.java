package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Зона, куди телепортується маньяк на старті гри.
 * v3-еквівалент: game/ManiacSpawnZone.java (тепер лише семантика
 * "це зона маньяка" — пошук позиції успадкований з ZoneArchetype).
 */
public class ManiacSpawnZoneArchetype extends ZoneArchetype {

    public ManiacSpawnZoneArchetype(BlockPos centre, int radius) {
        super(centre, radius);
    }

    /** Випадкова точка спавну для маньяка всередині зони. */
    public BlockPos pickSpawnPos(ServerLevel level) {
        return randomSurfacePos(level);
    }
}

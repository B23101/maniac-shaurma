package com.log_to_kot.maniacmod.map.zones;

import dev.shaurmalib.forge.teleport.TeleportReason;
import dev.shaurmalib.forge.teleport.TeleportService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Зона спавну виживших (+ лут-предметів навколо). Успадковує пошук
 * позиції й перевірку "чи гравець всередині" з ZoneArchetype;
 * додає лише опційне Y-обмеження (minY/maxY) для карт із кількома
 * поверхами, де випадкова поверхнева точка може вийти на дах.
 *
 * v3-еквівалент: game/ItemSpawnZone.java — там spawnSurvivors() уже
 * використовує dev.shaurmalib.forge.teleport.TeleportService (див.
 * попередній крок інтеграції shaurma-lib), randomSurfacePos і
 * contains були власними копіями — тепер успадковані.
 */
public class ItemSpawnZoneArchetype extends ZoneArchetype {

    private final Integer minY; // null = без обмеження
    private final Integer maxY;

    public ItemSpawnZoneArchetype(BlockPos centre, int radius) {
        this(centre, radius, null, null);
    }

    public ItemSpawnZoneArchetype(BlockPos centre, int radius, Integer minY, Integer maxY) {
        super(centre, radius);
        this.minY = minY;
        this.maxY = maxY;
    }

    @Override
    protected boolean isValidSurface(ServerLevel level, BlockPos pos) {
        if (minY != null && pos.getY() < minY) return false;
        if (maxY != null && pos.getY() > maxY) return false;
        return super.isValidSurface(level, pos);
    }

    /** Телепортує виживших на випадкові точки всередині зони (через shaurma-lib TeleportService). */
    public void spawnSurvivors(ServerLevel level, List<ServerPlayer> survivors) {
        for (ServerPlayer p : survivors) {
            BlockPos pos = randomSurfacePos(level);
            if (pos == null) continue;
            TeleportService.teleport(p, level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                p.getYRot(), p.getXRot(), TeleportReason.CUSTOM, "maniac_survivor_spawn");
        }
    }

    /** TODO: перенести з v3 ItemSpawnZone.resetSpawn() — скидання заспавненого луту між іграми. */
    public void resetSpawn() {
        throw new UnsupportedOperationException("resetSpawn() ще не перенесено з v3");
    }
}

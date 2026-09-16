package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Базовий клас для квадратних зон карти, заданих центром і
 * радіусом (розмір (2*radius+1) × (2*radius+1) блоків).
 *
 * v3 мав ТРИ окремі, майже ідентичні класи з такою самою формою
 * (centre + radius, contains(player), пошук випадкової поверхні):
 *   - game/ManiacSpawnZone.java  (зона спавну маньяка)
 *   - game/EscapeZone.java       (зона втечі виживших)
 *   - game/ItemSpawnZone.java    (зона спавну виживших + лут)
 * Кожна дублювала свою версію randomSurfacePos(...) і contains(...).
 * Тепер це одна реалізація тут — конкретний підклас лише задає
 * СЕМАНТИКУ зони (що відбувається, коли гравець туди заходить).
 */
public abstract class ZoneArchetype {

    private static final Random RNG = new Random();
    private static final int MAX_SURFACE_SEARCH_ATTEMPTS = 50;

    protected final BlockPos centre;
    protected final int radius;

    protected ZoneArchetype(BlockPos centre, int radius) {
        this.centre = centre;
        this.radius = radius;
    }

    /**
     * true якщо гравець (за X/Z, ігноруючи Y — щоб працювало для
     * гравців на різній висоті) зараз усередині зони.
     */
    public final boolean contains(ServerPlayer player) {
        BlockPos p = player.blockPosition();
        return Math.abs(p.getX() - centre.getX()) <= radius
            && Math.abs(p.getZ() - centre.getZ()) <= radius;
    }

    /**
     * Випадкова позиція на поверхні всередині зони (для спавну
     * гравців/предметів). Спільна для всіх типів зон — конкретний
     * підклас (ItemSpawnZoneArchetype тощо) не переписує цей пошук.
     */
    protected final BlockPos randomSurfacePos(ServerLevel level) {
        for (int i = 0; i < MAX_SURFACE_SEARCH_ATTEMPTS; i++) {
            int x = centre.getX() + RNG.nextInt(radius * 2 + 1) - radius;
            int z = centre.getZ() + RNG.nextInt(radius * 2 + 1) - radius;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (isValidSurface(level, candidate)) return candidate;
        }
        return null; // не знайдено за MAX_SURFACE_SEARCH_ATTEMPTS спроб
    }

    /** Перевірка придатності знайденої позиції — підклас може посилити (напр. Y-діапазон). */
    protected boolean isValidSurface(ServerLevel level, BlockPos pos) {
        return true; // базова перевірка; TODO перенести реальну з v3 (не в лаві/повітрі тощо)
    }

    public final BlockPos centre() { return centre; }
    public final int radius()      { return radius; }
}

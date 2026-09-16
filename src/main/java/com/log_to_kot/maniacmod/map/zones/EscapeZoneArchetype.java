package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;

/**
 * Зона втечі — виживий, що зайшов сюди, виграє. Уся перевірка
 * "чи гравець всередині" успадкована з ZoneArchetype.contains(...);
 * тут лишається тільки семантика (що робити, коли contains()==true —
 * реалізує ManiacGameManager, не сам клас зони).
 * v3-еквівалент: game/EscapeZone.java.
 */
public class EscapeZoneArchetype extends ZoneArchetype {

    public EscapeZoneArchetype(BlockPos centre, int radius) {
        super(centre, radius);
    }
}

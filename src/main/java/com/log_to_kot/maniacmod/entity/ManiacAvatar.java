package com.log_to_kot.maniacmod.entity;

import dev.shaurmalib.forge.offline.OfflineAvatarBase;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * Тіло гравця, що вийшов посеред матчу. Уся поведінка (знімок, шкіра, нік,
 * удар, смерть, дроп) живе в {@link OfflineAvatarBase}; режим лише реєструє
 * власний тип сутності й атрибути (див. {@code ManiacMod#onEntityAttributes}).
 */
public final class ManiacAvatar extends OfflineAvatarBase {

    public ManiacAvatar(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }
}

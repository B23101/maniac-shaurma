package com.log_to_kot.maniacmod.core.match;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Реєстр тимчасових сутностей матчу.
 *
 * Реєстрація дублюється persistent-маркером у NBT сутності. Це важливо:
 * після крашу Java-об'єкт матчу зникає, але маркер залишається у збереженому
 * світі й дозволяє прибрати залишки при наступному запуску сервера.
 */
public final class MatchRuntimeRegistry {

    private static final String MATCH_ENTITY_KEY = "maniacmod_match_entity";

    private MatchRuntimeRegistry() {}

    /**
     * Реєструє тимчасову сутність матчу. Гравців навмисно не можна
     * зареєструвати, щоб аварійне очищення ніколи не зачепило їх.
     */
    public static void register(Entity entity) {
        if (entity.level().isClientSide || entity instanceof ServerPlayer) return;
        entity.getPersistentData().putBoolean(MATCH_ENTITY_KEY, true);
    }

    /** Чи була сутність створена як тимчасова сутність матчу. */
    public static boolean isRegistered(Entity entity) {
        return entity.getPersistentData().getBoolean(MATCH_ENTITY_KEY);
    }

    /**
     * Прибирає всі зареєстровані сутності з усіх завантажених вимірів.
     * Метод безпечний для повторного виклику.
     */
    public static int cleanup(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!isRegistered(entity) || entity instanceof ServerPlayer) continue;
                entity.discard();
                removed++;
            }
        }
        return removed;
    }
}

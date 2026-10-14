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
        // null приходить із подій (EntityJoinLevelEvent від чужих модів) і з
        // обходу світу — раніше це був NPE, що валив цикл тіків сервера.
        if (entity == null) return false;
        return entity.getPersistentData().getBoolean(MATCH_ENTITY_KEY);
    }

    /**
     * Прибирає всі зареєстровані сутності з усіх завантажених вимірів.
     * Метод безпечний для повторного виклику.
     */
    public static int cleanup(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            // discard() видаляє сутність із тієї самої колекції, яку ми
            // перебираємо, — це давало ConcurrentModificationException
            // «іноді» (залежно від того, чи був після видаленої ще хтось
            // в ітераторі) і ламало /maniac stop. Спершу збираємо список.
            java.util.List<Entity> toRemove = new java.util.ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity == null || !isRegistered(entity) || entity instanceof ServerPlayer) continue;
                toRemove.add(entity);
            }
            for (Entity entity : toRemove) {
                try {
                    entity.discard();
                    removed++;
                } catch (Exception e) {
                    com.log_to_kot.maniacmod.ManiacMod.LOGGER.error(
                        "[ManiacMod] не вдалося прибрати сутність матчу {}", entity, e);
                }
            }
        }
        return removed;
    }
}

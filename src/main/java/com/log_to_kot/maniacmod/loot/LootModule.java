package com.log_to_kot.maniacmod.loot;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.spawn.SpawnPoint;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Розкладає предмети по задіяних ITEM-точках карти.
 *
 * <h3>Чому це не {@code PhaseListener}</h3>
 * Спавн луту — не самостійна механіка з власним життєвим циклом, а
 * частина <em>застосування плану розкидання</em>
 * ({@code MatchOrchestrator.applySpawnPlan}), точно як спавн
 * генераторів. Списку задіяних точок немає ні в кого, крім плану, а
 * план — приватний стан оркестратора; тож оркестратор віддає сюди готовий
 * список, а не модуль лізе у нього сам (правило пакетної приватності з
 * {@code AI_CODE_GUIDE.md}, розділ 2).
 *
 * <h3>Прибирання — не тут</h3>
 * Кожна сутність реєструється в {@code MatchRuntimeRegistry} при
 * створенні ({@link com.log_to_kot.maniacmod.entity.GroundItemEntity}),
 * тож {@code reset()} оркестратора вже знищує всі лежачі предмети.
 * Друге прибирання тут було б другим джерелом правди.
 */
public final class LootModule {

    private final Random rng;

    public LootModule(Random rng) {
        this.rng = rng;
    }

    /**
     * Кладе по одному предмету на кожну задіяну точку.
     *
     * @param level  рівень матчу; {@code null} → нічого не робимо (той
     *               самий теоретичний випадок «немає жодного гравця», що й
     *               для генераторів)
     * @param points задіяні ITEM-точки з плану
     * @return скільки предметів реально з'явилось у світі
     */
    public int spawnOnPoints(ServerLevel level, List<SpawnPoint> points) {
        if (level == null || points.isEmpty()) return 0;

        LootTable table = LootTables.itemPoints();
        int placed = 0;
        for (SpawnPoint point : points) {
            ItemStack stack = table.roll(rng);
            if (GroundItemSpawner.spawnOnMap(level, point.pos(), stack, rng)) {
                placed++;
            }
        }

        if (placed < points.size()) {
            ManiacMod.LOGGER.warn("[loot] розкладено {} з {} предметів — решту світ не прийняв",
                placed, points.size());
        } else {
            ManiacMod.LOGGER.info("[loot] розкладено {} предметів", placed);
        }
        return placed;
    }
}

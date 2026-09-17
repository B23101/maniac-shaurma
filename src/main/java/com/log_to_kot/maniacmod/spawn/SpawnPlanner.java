package com.log_to_kot.maniacmod.spawn;

import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Роздає точки спавну ПЕРЕД тим, як когось телепортувати.
 *
 * Ключова ідея: план будується повністю в пам'яті й лише потім
 * застосовується. Якщо план побудувати не вдалось — жодного гравця
 * ще не зрушили з місця, тож матч можна скасувати чисто, без
 * половини команди, розкиданої по карті.
 *
 * v3 робив навпаки: ItemSpawnZone.spawnSurvivors() телепортував
 * гравців по одному, і якщо для когось не знаходилась поверхня —
 * той просто лишався де був (continue), мовчки.
 *
 * Алгоритм:
 *   1. Обираємо стартову точку маньяка з точок ЙОГО архетипу.
 *   2. Перемішуємо SURVIVOR-точки.
 *   3. Жадібно роздаємо: точка підходить, якщо вона не зайнята,
 *      далі MIN_SURVIVOR_TO_MANIAC від маньяка і далі
 *      MIN_SURVIVOR_TO_SURVIVOR від усіх уже розданих.
 *   4. Якщо комусь не вистачило — послаблюємо обидві дистанції на
 *      RELAXATION_STEP і пробуємо ще раз (до MAX_RELAXATION_PASSES).
 *      Умова "1 точка = 1 гравець" НІКОЛИ не послаблюється.
 */
public final class SpawnPlanner {

    private final Random rng;

    public SpawnPlanner(Random rng) {
        this.rng = rng;
    }

    /** Готовий, ще не застосований план розкидання. */
    public static final class SpawnPlan {
        private final SpawnPoint maniacPoint;
        private final Map<UUID, SpawnPoint> survivorPoints;
        private final List<SpawnPoint> engagedItemPoints;
        private final List<SpawnPoint> generatorPoints;

        SpawnPlan(SpawnPoint maniacPoint,
                  Map<UUID, SpawnPoint> survivorPoints,
                  List<SpawnPoint> engagedItemPoints,
                  List<SpawnPoint> generatorPoints) {
            this.maniacPoint = maniacPoint;
            this.survivorPoints = survivorPoints;
            this.engagedItemPoints = engagedItemPoints;
            this.generatorPoints = generatorPoints;
        }

        public SpawnPoint maniacPoint()                 { return maniacPoint; }
        public Map<UUID, SpawnPoint> survivorPoints()   { return Map.copyOf(survivorPoints); }
        public List<SpawnPoint> engagedItemPoints()     { return List.copyOf(engagedItemPoints); }
        public List<SpawnPoint> generatorPoints()       { return List.copyOf(generatorPoints); }
    }

    /** Кидається, коли карта розмічена так, що коректний план неможливий. */
    public static final class SpawnPlanFailure extends RuntimeException {
        public SpawnPlanFailure(String message) { super(message); }
    }

    /**
     * Будує план. Нікого не телепортує — лише рахує.
     *
     * @param allPoints   уся розмітка карти
     * @param maniacId    id архетипу маньяка ("chucky") — визначає його точки
     * @param survivorIds UUID усіх виживих
     * @param numManiacs  скільки маньяків у цьому матчі (зараз завжди 1 —
     *                    параметр існує заздалегідь, щоб підтримку 2+
     *                    маньяків можна було увімкнути, змінивши лише
     *                    виклик цього методу, а не формулу генераторів)
     */
    public SpawnPlan plan(List<SpawnPoint> allPoints, String maniacId, List<UUID> survivorIds,
                          int numManiacs) {
        List<SpawnPoint> maniacPoints = filter(allPoints, SpawnPointKind.MANIAC, maniacId);
        if (maniacPoints.isEmpty()) {
            throw new SpawnPlanFailure(
                "Немає жодної стартової точки для маньяка '" + maniacId + "'. "
              + "Додай її командою /maniac addpoint maniac " + maniacId);
        }

        List<SpawnPoint> survivorPool = filter(allPoints, SpawnPointKind.SURVIVOR, null);
        if (survivorPool.size() < survivorIds.size()) {
            throw new SpawnPlanFailure(
                "Точок для виживих " + survivorPool.size() + ", а гравців " + survivorIds.size()
              + ". Одна точка не може вмістити двох — додай ще точок.");
        }

        SpawnPoint maniacPoint = maniacPoints.get(rng.nextInt(maniacPoints.size()));

        // Дистанції беруться з конфігу. Окремих констант у коді немає
        // навмисно: одне число — одне джерело правди.
        double minToManiac   = ManiacConfigs.get(ConfigSchema.MIN_SURVIVOR_TO_MANIAC);
        double minToSurvivor = ManiacConfigs.get(ConfigSchema.MIN_SURVIVOR_TO_PEER);

        int maxPasses = ManiacConfigs.get(ConfigSchema.MAX_RELAXATION_PASSES);
        double step = ManiacConfigs.get(ConfigSchema.RELAXATION_STEP);

        for (int pass = 0; pass <= maxPasses; pass++) {
            Map<UUID, SpawnPoint> assigned =
                tryAssign(survivorPool, survivorIds, maniacPoint, minToManiac, minToSurvivor);

            if (assigned != null) {
                return new SpawnPlan(maniacPoint, assigned, pickItemPoints(allPoints),
                    pickGeneratorPoints(allPoints, maniacPoint, numManiacs));
            }

                    minToManiac   *= step;
                    minToSurvivor *= step;
        }

        throw new SpawnPlanFailure(
            "Не вдалося рознести гравців навіть із послабленими дистанціями. "
          + "Точки виживих стоять надто щільно або надто близько до маньяка.");
    }

    /**
     * Скільки точок генераторів реально заспавнити цього раунду.
     *
     * generatorsRequired — скільки треба ПОЛАГОДИТИ, це число не
     * залежить від кількості маньяків. Понад нього додається
     * bonusGeneratorsPerManiac × numManiacs "зайвих" точок — вони
     * теж генератори, але їх лагодити не обов'язково; вони існують,
     * щоб виживі не могли впевнено закемпити відомий набір з
     * generatorsRequired точок. Чим більше маньяків, тим більше
     * бонусних точок і тим важче вгадати, які з них — справжні.
     */
    private List<SpawnPoint> pickGeneratorPoints(List<SpawnPoint> allPoints,
                                                  SpawnPoint maniacPoint, int numManiacs) {
        List<SpawnPoint> pool = new ArrayList<>(filter(allPoints, SpawnPointKind.GENERATOR, null));
        int required = ManiacConfigs.get(ConfigSchema.GENERATORS_REQUIRED);
        int bonusPerManiac = ManiacConfigs.get(ConfigSchema.BONUS_GENERATORS_PER_MANIAC);
        int desired = required + bonusPerManiac * Math.max(0, numManiacs);
        Collections.shuffle(pool, rng);
        if (pool.size() < desired) {
            throw new SpawnPlanFailure("Потрібно щонайменше " + desired
                + " точок генераторів (" + required + " на ремонт + "
                + bonusPerManiac + "×" + numManiacs + " бонусних), а налаштовано "
                + pool.size() + ".");
        }

        List<SpawnPoint> selected = new ArrayList<>();
        selected.add(pool.remove(rng.nextInt(pool.size())));
        while (selected.size() < desired) {
            SpawnPoint best = null;
            double bestDistance = -1;
            for (SpawnPoint candidate : pool) {
                double nearest = candidate.horizontalDistanceTo(maniacPoint);
                for (SpawnPoint chosen : selected) {
                    nearest = Math.min(nearest, candidate.horizontalDistanceTo(chosen));
                }
                if (nearest > bestDistance) {
                    best = candidate;
                    bestDistance = nearest;
                }
            }
            selected.add(best);
            pool.remove(best);
        }
        return selected;
    }

    /**
     * Одна спроба роздачі. Повертає null, якщо комусь не вистачило
     * точки — тоді викликач послаблює дистанції й пробує знову.
     */
    private Map<UUID, SpawnPoint> tryAssign(List<SpawnPoint> pool, List<UUID> survivors,
                                            SpawnPoint maniacPoint,
                                            double minToManiac, double minToSurvivor) {
        List<SpawnPoint> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, rng);

        Map<UUID, SpawnPoint> result = new LinkedHashMap<>();
        List<SpawnPoint> taken = new ArrayList<>();

        for (UUID survivor : survivors) {
            SpawnPoint chosen = null;

            for (SpawnPoint candidate : shuffled) {
                // 1 точка = 1 гравець. Це правило не послаблюється ніколи.
                if (taken.contains(candidate)) continue;
                if (candidate.horizontalDistanceTo(maniacPoint) < minToManiac) continue;

                boolean tooCloseToPeer = false;
                for (SpawnPoint other : taken) {
                    if (candidate.horizontalDistanceTo(other) < minToSurvivor) {
                        tooCloseToPeer = true;
                        break;
                    }
                }
                if (tooCloseToPeer) continue;

                chosen = candidate;
                break;
            }

            if (chosen == null) return null; // цей прохід провалився
            taken.add(chosen);
            result.put(survivor, chosen);
        }

        return result;
    }

    /**
     * Обирає підмножину ITEM-точок, які задіються цього матчу.
     * З ТЗ: "деякі задіяні, деякі не задіяні".
     */
    private List<SpawnPoint> pickItemPoints(List<SpawnPoint> allPoints) {
        List<SpawnPoint> itemPoints = new ArrayList<>(filter(allPoints, SpawnPointKind.ITEM, null));
        Collections.shuffle(itemPoints, rng);
        int engage = (int) Math.round(itemPoints.size() * ManiacConfigs.get(ConfigSchema.ITEM_POINT_ENGAGE_RATIO));
        return new ArrayList<>(itemPoints.subList(0, Math.min(engage, itemPoints.size())));
    }

    private static List<SpawnPoint> filter(List<SpawnPoint> points, SpawnPointKind kind, String ownerId) {
        List<SpawnPoint> out = new ArrayList<>();
        for (SpawnPoint p : points) {
            if (p.kind() != kind) continue;
            if (ownerId != null && !ownerId.equals(p.ownerId())) continue;
            out.add(p);
        }
        return out;
    }

    /** Діагностика для /maniac points check — чи карта взагалі придатна. */
    public static Map<SpawnPointKind, Integer> countByKind(List<SpawnPoint> points) {
        Map<SpawnPointKind, Integer> counts = new HashMap<>();
        for (SpawnPointKind kind : SpawnPointKind.values()) counts.put(kind, 0);
        for (SpawnPoint p : points) counts.merge(p.kind(), 1, Integer::sum);
        return counts;
    }

    /** Лишається для майбутньої перевірки "чи точка всередині 300×300". */
    public static boolean isInsideMap(BlockPos pos, BlockPos mapCentre) {
        int half = ManiacConfigs.get(ConfigSchema.MAP_SIZE_BLOCKS) / 2;
        return Math.abs(pos.getX() - mapCentre.getX()) <= half
            && Math.abs(pos.getZ() - mapCentre.getZ()) <= half;
    }
}

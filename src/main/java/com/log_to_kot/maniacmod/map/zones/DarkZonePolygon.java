package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Зона "темна зона" — гнучка N-кутна область за точками, які адмін
 * ставить командою на своїй поточній позиції (не вручну координатами).
 * Y ІГНОРУЄТЬСЯ навмисно (як і {@link ZoneArchetype#contains}): темрява
 * діє по всій висоті стовпця XZ, тому перевірка "гравець всередині"
 * дивиться лише на X/Z, незалежно від поверху/глибини.
 * <p>
 * ── Чому НЕ підклас {@link ZoneArchetype} ────────────────────────────
 * {@code ZoneArchetype} — центр+радіус (квадрат, фіксована форма,
 * успадкований пошук випадкової поверхні для спавну). Темна зона за
 * дизайном — довільний N-кутник із точок гравця, без спавну всередині
 * і без форми "центр+радіус" — спільного коду з квадратними зонами
 * рівно нуль, тому окремий клас чесніший, ніж підклас, що перевизначає
 * геометрію батька.
 * <p>
 * ── Правило кількості точок (за дизайном) ────────────────────────────
 * <ul>
 *   <li>1 точка — квадрат {@link #SINGLE_POINT_HALF_SIZE} навколо неї
 *       (щоб команда "постав хоч одну точку" давала якусь зону, а не
 *       нульову площу);</li>
 *   <li>2 точки — прямокутник, де ці дві точки є протилежними кутами
 *       по діагоналі (як просив дизайн: "якщо 2 точки то це квадрат
 *       буде діагоналі");</li>
 *   <li>3+ точок — точний полігон у тому порядку, в якому їх додавали
 *       (point-in-polygon, ray casting).</li>
 * </ul>
 */
public final class DarkZonePolygon {

    /** Половина сторони квадрата, коли зона має лише 1 точку. */
    public static final int SINGLE_POINT_HALF_SIZE = 8;

    private final String name;
    private final List<BlockPos> points;

    public DarkZonePolygon(String name, List<BlockPos> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Темна зона '" + name + "' без жодної точки.");
        }
        this.name = name;
        this.points = List.copyOf(points);
    }

    public String name() { return name; }
    public List<BlockPos> points() { return points; }

    public boolean contains(ServerPlayer player) {
        BlockPos p = player.blockPosition();
        return contains(p.getX(), p.getZ());
    }

    public boolean contains(double x, double z) {
        return switch (points.size()) {
            case 1 -> containsSquare(x, z);
            case 2 -> containsRectangle(x, z);
            default -> containsPolygon(x, z);
        };
    }

    private boolean containsSquare(double x, double z) {
        BlockPos c = points.get(0);
        return Math.abs(x - c.getX()) <= SINGLE_POINT_HALF_SIZE
            && Math.abs(z - c.getZ()) <= SINGLE_POINT_HALF_SIZE;
    }

    /** 2 точки = протилежні кути прямокутника по діагоналі. */
    private boolean containsRectangle(double x, double z) {
        BlockPos a = points.get(0);
        BlockPos b = points.get(1);
        double minX = Math.min(a.getX(), b.getX());
        double maxX = Math.max(a.getX(), b.getX());
        double minZ = Math.min(a.getZ(), b.getZ());
        double maxZ = Math.max(a.getZ(), b.getZ());
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /**
     * Точка-в-полігоні через ray casting (парність перетинів променя
     * вправо від точки з ребрами полігону) — стандартний алгоритм,
     * коректний для будь-якого простого (без самоперетинів) N-кутника,
     * опуклого чи ні.
     */
    private boolean containsPolygon(double x, double z) {
        boolean inside = false;
        int n = points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = points.get(i).getX();
            double zi = points.get(i).getZ();
            double xj = points.get(j).getX();
            double zj = points.get(j).getZ();

            boolean crosses = ((zi > z) != (zj > z))
                && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (crosses) inside = !inside;
        }
        return inside;
    }

    /** Зручність для команд: додає нову точку в кінець і повертає нову зону (immutable). */
    public DarkZonePolygon withPoint(BlockPos point) {
        List<BlockPos> updated = new ArrayList<>(points);
        updated.add(point);
        return new DarkZonePolygon(name, updated);
    }
}

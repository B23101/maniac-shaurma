package com.log_to_kot.maniacmod.map.zones;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Галявина — прямокутна зона, де можна запустити сигнальну ракету.
 *
 * <p>Задається ДВОМА кутами (діагональ прямокутника) і точкою зависання вертольота
 * всередині. Висота кутів не враховується: зона — стовпець по XZ, а її візуалізація
 * (жовтий контур) лягає на рельєф сама, колонка за колонкою
 * ({@code ClearingZoneRenderer}).</p>
 *
 * <p>Незмінний клас: команди створюють нову версію ({@link #withCorner}, {@link #withHeli}),
 * а не мутують стару — снапшот конфігу можна безпечно роздавати будь-кому.</p>
 *
 * <p>Зона може існувати НЕПОВНОЮ (створена, але без другого кута чи точки вертольота) —
 * так адмін добудовує її кількома командами. Неповна зона в матчі не бере участі
 * ({@link #isComplete()}).</p>
 */
public final class ClearingZone {

    private final String name;
    private final List<BlockPos> corners;   // 0..2
    private final BlockPos heli;            // null, поки не задано

    public ClearingZone(String name, List<BlockPos> corners, BlockPos heli) {
        this.name = name;
        this.corners = List.copyOf(corners.size() > 2 ? corners.subList(0, 2) : corners);
        this.heli = heli;
    }

    public String name()               { return name; }
    public List<BlockPos> corners()    { return corners; }
    /** Точка, над якою висить вертоліт; Y = рівень землі під мотузкою. Може бути null. */
    public BlockPos heli()             { return heli; }

    public boolean hasBothCorners()    { return corners.size() == 2; }
    public boolean isComplete()        { return hasBothCorners() && heli != null; }

    public ClearingZone withCorner(BlockPos corner) {
        if (corners.size() >= 2) return this;
        List<BlockPos> next = new java.util.ArrayList<>(corners);
        next.add(corner);
        return new ClearingZone(name, next, heli);
    }

    public ClearingZone withHeli(BlockPos point) {
        return new ClearingZone(name, corners, point);
    }

    public int minX() { return hasBothCorners() ? Math.min(corners.get(0).getX(), corners.get(1).getX()) : 0; }
    public int maxX() { return hasBothCorners() ? Math.max(corners.get(0).getX(), corners.get(1).getX()) : 0; }
    public int minZ() { return hasBothCorners() ? Math.min(corners.get(0).getZ(), corners.get(1).getZ()) : 0; }
    public int maxZ() { return hasBothCorners() ? Math.max(corners.get(0).getZ(), corners.get(1).getZ()) : 0; }

    /** Чи точка (за X/Z; Y ігнорується) всередині зони. Межа включно. */
    public boolean contains(double x, double z) {
        if (!hasBothCorners()) return false;
        return x >= minX() && x < maxX() + 1 && z >= minZ() && z < maxZ() + 1;
    }

    /** Чи точка (за X/Z) всередині зони. Межа включно. */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX() + 0.5, pos.getZ() + 0.5);
    }
}

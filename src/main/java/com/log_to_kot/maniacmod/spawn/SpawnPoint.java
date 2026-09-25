package com.log_to_kot.maniacmod.spawn;

import net.minecraft.core.BlockPos;

/**
 * Одна заздалегідь розмічена точка на карті.
 *
 * v3-еквівалент: точок як окремої сутності НЕ БУЛО — були зони
 * (ItemSpawnZone/ManiacSpawnZone) і всередині них рандомний пошук
 * поверхні. Через це не можна було гарантувати "1 точка = 1 гравець"
 * і "виживший не ближче N блоків до маньяка": рандом міг двічі
 * видати одну й ту саму позицію.
 *
 * Тепер розмітка явна: точки задаються командою (/maniac addpoint ...),
 * зберігаються в конфіг світу, і SpawnPlanner роздає їх БЕЗ повторів.
 */
public final class SpawnPoint {

    private final BlockPos pos;
    private final float yaw;
    private final SpawnPointKind kind;

    /** Для MANIAC — id архетипу ("test_maniac"); для решти null. */
    private final String ownerId;

    /** Чи точка задіяна в поточному матчі (для ITEM частина навмисно порожня). */
    private boolean engaged = false;

    public SpawnPoint(BlockPos pos, float yaw, SpawnPointKind kind, String ownerId) {
        this.pos = pos;
        this.yaw = yaw;
        this.kind = kind;
        this.ownerId = ownerId;
    }

    public BlockPos pos()          { return pos; }
    public float yaw()             { return yaw; }
    public SpawnPointKind kind()   { return kind; }
    public String ownerId()        { return ownerId; }

    public boolean isEngaged()     { return engaged; }
    public void engage()           { this.engaged = true; }
    public void release()          { this.engaged = false; }

    /** Відстань у блоках по горизонталі (Y ігнорується — поверхи не рахуються). */
    public double horizontalDistanceTo(SpawnPoint other) {
        double dx = pos.getX() - other.pos.getX();
        double dz = pos.getZ() - other.pos.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double horizontalDistanceTo(BlockPos other) {
        double dx = pos.getX() - other.getX();
        double dz = pos.getZ() - other.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}

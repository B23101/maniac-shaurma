package com.log_to_kot.maniacmod.game;

import net.minecraft.core.BlockPos;
import java.util.UUID;

/**
 * Міна маньяка.
 *
 * Невидима для виживаючих (рендериться тільки якщо у гравця є викрутка в руці).
 * При спрацюванні: -1 ❤ + сповільнення 3 секунди.
 * Знешкодження: виживаючий тримає ПКМ з викруткою 2 секунди (40 тіків).
 *
 * Кулдаун встановлення: 20 секунд (400 тіків) — спільний з іншими пастками.
 */
public class MineState {

    private static final int DEFUSE_TICKS_NEEDED = 40; // 2 секунди

    private final BlockPos pos;
    private boolean triggered  = false;
    private boolean defused    = false;

    private UUID   defusingUUID  = null;
    private int    defuseTicks   = 0;

    public MineState(BlockPos pos) { this.pos = pos; }

    public BlockPos getPos()      { return pos; }
    public boolean isTriggered()  { return triggered; }
    public boolean isDefused()    { return defused; }
    public boolean isGone()       { return triggered || defused; }

    /** Returns true on first trigger */
    public boolean trigger(UUID uuid) {
        if (triggered || defused) return false;
        triggered = true;
        return true;
    }

    /** Called every tick while survivor holds screwdriver near mine */
    public boolean tickDefuse(UUID helper) {
        if (isGone()) return false;
        defusingUUID = helper;
        defuseTicks++;
        if (defuseTicks >= DEFUSE_TICKS_NEEDED) {
            defused = true;
            return true;
        }
        return false;
    }

    public void cancelDefuse() {
        defusingUUID = null;
        defuseTicks  = 0;
    }

    public int getDefuseProgress() {
        return defuseTicks * 100 / DEFUSE_TICKS_NEEDED;
    }

    public UUID getDefusingUUID() { return defusingUUID; }
}

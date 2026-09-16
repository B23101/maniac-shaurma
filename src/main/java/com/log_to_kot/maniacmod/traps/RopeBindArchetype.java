package com.log_to_kot.maniacmod.traps;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * Мотузка — підвішує гравця, коли він стає на неї. Підсічку можна
 * прибрати лише ножицями (з v3 RopeBindState — після звільнення
 * прив'язка просто видаляється, без повторного використання).
 */
public class RopeBindArchetype extends TrapArchetype {

    public RopeBindArchetype() {
        super("rope_bind", "Мотузка");
    }

    @Override
    public void applyEffect(ServerPlayer victim, BlockPos trapPos) {
        // TODO: перенести з v3 RopeBindState (підвіс, звільнення ножицями)
        throw new UnsupportedOperationException("applyEffect() ще не перенесено з v3");
    }

    @Override
    public boolean shouldTrigger(ServerPlayer candidate) {
        // TODO: перенести перевірку "виживший, не маньяк" з v3
        return true;
    }
}

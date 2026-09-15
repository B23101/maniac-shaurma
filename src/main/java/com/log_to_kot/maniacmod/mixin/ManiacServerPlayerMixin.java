package com.log_to_kot.maniacmod.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Hitbox override moved to ServerEventHandler.onEntitySize (EntityEvent.Size).
 * This Mixin is kept empty to avoid removing it from the mixin config.
 */
@Mixin(ServerPlayer.class)
public abstract class ManiacServerPlayerMixin {
}

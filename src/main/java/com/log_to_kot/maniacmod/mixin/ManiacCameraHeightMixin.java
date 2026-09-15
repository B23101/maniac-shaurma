package com.log_to_kot.maniacmod.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Eye height override moved to ManiacFOVHandler.onEntitySizeClient (EntityEvent.Size).
 * This Mixin is kept empty to avoid removing it from the mixin config.
 */
@Mixin(LocalPlayer.class)
public abstract class ManiacCameraHeightMixin {
}

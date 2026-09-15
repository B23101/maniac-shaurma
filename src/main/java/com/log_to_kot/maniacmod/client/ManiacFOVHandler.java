package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT)
public class ManiacFOVHandler {

    @SubscribeEvent
    public static void onComputeFov(ComputeFovModifierEvent event) {
        if (!ManiacClientState.isLocalManiac()) return;
        float multiplier = ManiacClientState.getLocalType().fovMultiplier;
        event.setNewFovModifier(event.getNewFovModifier() * multiplier);
    }

    /**
     * Client-side hitbox + eye-height override for the local player.
     * Mirrors the server-side EntityEvent.Size handler so prediction matches.
     */
    @SubscribeEvent
    public static void onEntitySizeClient(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof LocalPlayer)) return;
        if (!ManiacClientState.isLocalManiac()) return;
        var type = ManiacClientState.getLocalType();
        if (type == null) return;

        event.setNewSize(EntityDimensions.scalable(type.hitboxWidth, type.hitboxHeight));
        event.setNewEyeHeight(type.eyeHeight);
    }
}

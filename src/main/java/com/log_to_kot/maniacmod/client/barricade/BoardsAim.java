package com.log_to_kot.maniacmod.client.barricade;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.entity.BarricadeEntity;
import com.log_to_kot.maniacmod.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Клієнтський «чи дивлюсь я на барикаду» — лише для підказки; рішення про виламування приймає сервер. */
@OnlyIn(Dist.CLIENT)
final class BoardsAim {

    /** Дзеркало дефолту barricades.crowbarRangeBlocks: конфіг сервера клієнт не читає. */
    private static final double REACH = 4.5;

    private BoardsAim() {}

    static boolean lookingAtBarricade(Minecraft mc) {
        if (mc.level == null || mc.player == null) return false;
        Vec3 eye = VrServerCompat.aimOrigin(mc.player);   // VR: контролер
        Vec3 look = VrServerCompat.aimDir(mc.player);

        double blockDist = REACH;
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            blockDist = hit.getLocation().distanceTo(eye);
            if (blockDist <= REACH && mc.level.getBlockState(hit.getBlockPos()).is(ModBlocks.BOARDS.get())) {
                return true;
            }
        }
        double maxLen = Math.min(REACH, blockDist + 0.05);
        for (BarricadeEntity b : mc.level.getEntitiesOfClass(BarricadeEntity.class,
                mc.player.getBoundingBox().inflate(REACH + 16.0))) {
            if (b.rayDistance(eye, look, maxLen) >= 0) return true;
        }
        return false;
    }
}

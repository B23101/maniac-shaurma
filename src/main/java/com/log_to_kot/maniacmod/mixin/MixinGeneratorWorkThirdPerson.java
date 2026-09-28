package com.log_to_kot.maniacmod.mixin;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.client.HandPoseTracks;
import com.log_to_kot.maniacmod.client.ItemPickupPose;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Пози рук: ремонт генератора, залив бензину, підбір предмета.
 *
 * Той самий підхід, що й у ліхтарика ({@code MixinFlashlightHoldUpThirdPerson}):
 * запис кутів прямо в {@code PlayerModel.setupAnim TAIL}. PAL-шар для цих
 * поз не спрацьовував, а цей шлях працює завжди й однаково для всіх глядачів
 * (ремонт/залив — стан з {@code GeneratorInteractionPacket}, підбір — з
 * {@code ItemPickupPacket}).
 *
 * Кути — НЕ вигадані: це ключові кадри з Blockbench-кліпів
 * ({@link HandPoseTracks}). Фаза циклу береться з {@code ageInTicks}, тож
 * ремонт виглядає однаково в усіх, хто бачить гравця.
 *
 * Пріоритет: підбір (короткий жест) > ремонт/залив. Лежачий гравець
 * ({@code MixinDownedPoseThirdPerson}) перекриває все, бо він не працює.
 *
 * require = 0 — якщо мапінг розійдеться, інжект мовчки не застосується.
 */
@Mixin(PlayerModel.class)
public abstract class MixinGeneratorWorkThirdPerson {

    @Inject(method = "setupAnim", at = @At("TAIL"), require = 0)
    private void maniacmod$applyHandPoses(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                          float ageInTicks, float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (ClientMatchState.isProne(player.getUUID())) return;

        PlayerModel<?> m = (PlayerModel<?>) (Object) this;
        float[] v = new float[3];
        boolean changed = false;

        // 1. Підбір предмета — праворука, короткий жест поверх усього.
        float t = ItemPickupPose.elapsedSeconds(player.getUUID(), 0f);
        if (t >= 0f) {
            HandPoseTracks.sample(HandPoseTracks.PICKUP_R_ROT, t, HandPoseTracks.PICKUP_LEN, false, v);
            setRot(m.rightArm, v);
            HandPoseTracks.sample(HandPoseTracks.PICKUP_R_POS, t, HandPoseTracks.PICKUP_LEN, false, v);
            addPos(m.rightArm, v);
            changed = true;
        } else {
            // 2. Ремонт / залив.
            int action = ClientMatchState.generatorInteractionOf(player.getUUID());
            float time = ageInTicks / 20f;
            if (action == ClientMatchState.GENERATOR_ACTION_REPAIR) {
                float len = HandPoseTracks.REPAIR_LEN;
                HandPoseTracks.sample(HandPoseTracks.REPAIR_R_ROT, time, len, true, v);
                setRot(m.rightArm, v);
                HandPoseTracks.sample(HandPoseTracks.REPAIR_R_POS, time, len, true, v);
                addPos(m.rightArm, v);
                HandPoseTracks.sample(HandPoseTracks.REPAIR_L_ROT, time, len, true, v);
                setRot(m.leftArm, v);
                HandPoseTracks.sample(HandPoseTracks.REPAIR_L_POS, time, len, true, v);
                addPos(m.leftArm, v);
                changed = true;
            } else if (action == ClientMatchState.GENERATOR_ACTION_REFUEL) {
                float len = HandPoseTracks.REFUEL_LEN;
                HandPoseTracks.sample(HandPoseTracks.REFUEL_R_ROT, time, len, true, v);
                setRot(m.rightArm, v);
                HandPoseTracks.sample(HandPoseTracks.REFUEL_R_POS, time, len, true, v);
                addPos(m.rightArm, v);
                // Ліва рука в кліпі без ключів — лишається ванільною.
                changed = true;
            }
        }

        if (changed) {
            m.rightSleeve.copyFrom(m.rightArm);
            m.leftSleeve.copyFrom(m.leftArm);
        }
    }

    /** Градуси з файлу → радіани ModelPart. */
    private static void setRot(ModelPart part, float[] deg) {
        part.xRot = HandPoseTracks.rad(deg[0]);
        part.yRot = HandPoseTracks.rad(deg[1]);
        part.zRot = HandPoseTracks.rad(deg[2]);
    }

    /** Зсув у пікселях моделі поверх базової позиції руки. */
    private static void addPos(ModelPart part, float[] px) {
        part.x += px[0];
        part.y += px[1];
        part.z += px[2];
    }
}

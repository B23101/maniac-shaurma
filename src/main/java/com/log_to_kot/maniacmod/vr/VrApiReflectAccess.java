package com.log_to_kot.maniacmod.vr;

import net.minecraft.world.phys.Vec3;

/**
 * Публічні двері до пакетного {@code VrApiReflect} для клієнтського пакета
 * {@code client.vr}. Лише переадресація.
 */
public final class VrApiReflectAccess {

    private VrApiReflectAccess() {}

    public static boolean isVrActive() { return VrApiReflect.isVrActive(); }

    public static void haptic(boolean mainHand, float seconds, float amplitude) {
        VrApiReflect.haptic(mainHand, seconds, amplitude);
    }

    /** Чи малюється вигляд очей (а не дзеркало/третя особа); без даних — true. */
    public static boolean isFirstPersonPass() { return VrApiReflect.isFirstPersonPass(); }

    public static Vec3 clientMainHandPos() { return VrApiReflect.clientMainHandPos(); }

    public static Vec3 clientMainHandDir() { return VrApiReflect.clientMainHandDir(); }
}

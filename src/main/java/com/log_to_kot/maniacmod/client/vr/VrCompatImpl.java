package com.log_to_kot.maniacmod.client.vr;

import com.log_to_kot.maniacmod.vr.VrApiReflectAccess;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Клієнтська обв'язка над API Vivecraft. Прямих посилань на {@code org.vivecraft.*}
 * тут НЕМАЄ (див. {@code VrApiReflect}), тож клас безпечно вантажиться й без мода.
 * Викликати лише через {@link VrCompat}.
 */
@OnlyIn(Dist.CLIENT)
final class VrCompatImpl {

    private VrCompatImpl() {}

    static boolean isVrActive() {
        return VrApiReflectAccess.isVrActive();
    }

    static void haptic(VrCompat.Hand hand, float seconds, float amplitude) {
        VrApiReflectAccess.haptic(hand == VrCompat.Hand.MAIN, seconds, amplitude);
    }

    static Vec3 mainHandPos() {
        return VrApiReflectAccess.clientMainHandPos();
    }

    static Vec3 mainHandDir() {
        return VrApiReflectAccess.clientMainHandDir();
    }
}

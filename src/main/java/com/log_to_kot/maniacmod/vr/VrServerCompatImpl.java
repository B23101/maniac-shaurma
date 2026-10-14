package com.log_to_kot.maniacmod.vr;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Спільна (клієнт + сервер) обв'язка над API Vivecraft. Прямих посилань на
 * {@code org.vivecraft.*} тут немає (див. {@link VrApiReflect}). Див. {@link VrServerCompat}.
 */
final class VrServerCompatImpl {

    private VrServerCompatImpl() {}

    static boolean isVrPlayer(Player player) {
        return VrApiReflect.isVrPlayer(player);
    }

    static Vec3 handPos(Player player) {
        return VrApiReflect.playerHandPos(player);
    }

    static Vec3 handDir(Player player) {
        return VrApiReflect.playerHandDir(player);
    }
}

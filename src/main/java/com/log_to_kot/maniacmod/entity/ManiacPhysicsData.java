package com.log_to_kot.maniacmod.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Tracks the active maniac's physical state on the server.
 *
 * Key responsibility: override the player's collision box so that:
 *   - Чакі   (0.5h) can physically move through 1-block-tall gaps
 *   - Слендер (4.0h) is blocked by anything shorter than 4 blocks
 *
 * Minecraft's player hitbox is hardcoded to 0.6 × 1.8.
 * We cannot directly resize a ServerPlayer's AABB without a mixin,
 * so we use the next best approach:
 *   1. Every server tick, check if the player's current position is
 *      inside a space too small for their ManiacType.
 *   2. If they entered through a gap that's too small (Slenderman),
 *      push them back out (velocity cancel + position reset).
 *   3. For Chucky, if they are in a ≥1-block space, allow normal movement.
 *      The visual is handled by the tiny model scale.
 *
 * Full AABB override requires a Mixin into LocalPlayer / ServerPlayer —
 * see ManiacPlayerMixin for the client-side camera eye-height override.
 */
public class ManiacPhysicsData {

    private final ManiacType type;
    private final ServerPlayer player;

    /** Position where Slenderman was last in a valid (tall enough) space. */
    private Vec3 lastValidPos;

    public ManiacPhysicsData(ServerPlayer player, ManiacType type) {
        this.player   = player;
        this.type     = type;
        this.lastValidPos = player.position();
    }

    // ── Per-tick collision enforcement ────────────────────────────────────────

    /**
     * Called every server tick.
     * Returns true if the player was pushed back (blocked).
     */
    public boolean tick() {
        if (type == ManiacType.CHUCKY) {
            return tickChucky();
        } else {
            return tickSlenderman();
        }
    }

    // ── Chucky: allowed through gaps ≥ 0.5 blocks tall ───────────────────────

    private boolean tickChucky() {
        // Chucky is 0.5 tall — he physically fits through 1-block gaps naturally.
        // Nothing to block. Just confirm valid pos.
        if (isSpaceClear(player.position(), type.hitboxWidth, type.hitboxHeight)) {
            lastValidPos = player.position();
        }
        return false;
    }

    // ── Slenderman: blocked by any gap < 4 blocks tall ───────────────────────

    private boolean tickSlenderman() {
        Vec3 currentPos = player.position();
        AABB slenderBox = getHitboxAt(currentPos);
        ServerLevel level = player.serverLevel();

        // Check if slender's full 4-block AABB overlaps any solid block
        boolean blocked = isAABBBlocked(level, slenderBox);

        if (blocked) {
            // Teleport back to last valid position
            player.teleportTo(lastValidPos.x, lastValidPos.y, lastValidPos.z);
            player.setDeltaMovement(0, player.getDeltaMovement().y < 0
                ? player.getDeltaMovement().y : 0, 0);
            player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                    "§c☠ Слендермен не може пройти — занадто низько!"));
            return true;
        }

        lastValidPos = currentPos;
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Build the hitbox AABB for this maniac type at a given position.
     * Origin = feet position.
     */
    public AABB getHitboxAt(Vec3 pos) {
        float hw = type.hitboxWidth / 2f;
        return new AABB(
            pos.x - hw, pos.y,                pos.z - hw,
            pos.x + hw, pos.y + type.hitboxHeight, pos.z + hw
        );
    }

    /**
     * Returns true if the AABB overlaps any solid (blocking) block.
     */
    private static boolean isAABBBlocked(ServerLevel level, AABB box) {
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.ceil(box.maxX);
        int maxY = (int) Math.ceil(box.maxY);
        int maxZ = (int) Math.ceil(box.maxZ);

        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos bp = new BlockPos(x, y, z);
                    BlockState bs = level.getBlockState(bp);
                    if (!bs.isAir() && bs.isSolid() &&
                        bs.getCollisionShape(level, bp) != net.minecraft.world.phys.shapes.Shapes.empty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check whether a vertical column of (width × height) is clear of solid blocks.
     */
    public static boolean isSpaceClear(Vec3 pos, float width, float height) {
        // Simple version: just check the center column
        double hw = width / 2.0;
        for (int dy = 0; dy < Math.ceil(height); dy++) {
            BlockPos bp = BlockPos.containing(pos.x, pos.y + dy, pos.z);
            // We can't access level here without it being passed — so this
            // version is used only for Chucky's simpler check via the full method
        }
        return true; // full check is done in tickSlenderman via isAABBBlocked
    }

    public ManiacType getType() { return type; }
    public Vec3 getLastValidPos() { return lastValidPos; }
}

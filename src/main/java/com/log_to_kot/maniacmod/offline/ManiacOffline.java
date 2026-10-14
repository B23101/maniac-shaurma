package com.log_to_kot.maniacmod.offline;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.loot.GroundItemSpawner;
import com.log_to_kot.maniacmod.registry.ModEntityTypes;
import dev.shaurmalib.common.offline.OfflineDamagePolicy;
import dev.shaurmalib.common.offline.OfflineListener;
import dev.shaurmalib.common.offline.OfflineLootPolicy;
import dev.shaurmalib.common.offline.OfflineRecord;
import dev.shaurmalib.common.offline.OfflineSpec;
import dev.shaurmalib.forge.offline.OfflineActors;
import dev.shaurmalib.forge.offline.OfflinePresenceConfig;
import dev.shaurmalib.forge.offline.OfflinePresenceModule;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.shaurmalib.forge.offline.OfflineAvatarBase;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Єдина точка зв'язку Маніяка з офлайн-присутністю shaurma-lib.
 *
 * <p>Правила режиму:
 * <ul>
 *   <li>Тіло з'являється лише в ігровій фазі (HUNT/POWERED/FINALE) для маньяка й
 *       для виживого в БУДЬ-ЯКОМУ стані. Тіло повторює стан власника: лежить
 *       (CRAWLING), повзе (UNCONSCIOUS), має таймер кровотечі, його піднімають
 *       союзники, ловлять пастки.</li>
 *   <li>Ванільний урон по тілу ігнорується (як по онлайн-гравцю). HP знімає лише
 *       удар маньяка або пастка, через {@link MatchOrchestrator#damageSurvivor}.
 *       HP = 0 валить тіло (UNCONSCIOUS); вмирає воно, коли вичерпано таймер.</li>
 *   <li>Лут вбитого тіла лягає навколо нього як {@code GroundItemEntity}.</li>
 * </ul>
 */
public final class ManiacOffline {

    private ManiacOffline() {}

    // ── Конфігурація для ShaurmaLib.Builder#withOfflinePresence ───────────

    public static OfflinePresenceConfig config(String nameTagHideTeamId) {
        return OfflinePresenceConfig.builder()
            .entityType(() -> ModEntityTypes.OFFLINE_AVATAR.get())
            .spawnWhen(ManiacOffline::specFor)
            // Усі правила блокування — у власному owner-правилі ManiacMod
            // (match_damage_owner); HP-модель тут не потрібна: ванільний урон
            // тіло не отримує взагалі, а удар маньяка іде окремим шляхом.
            .damagePolicy(OfflineDamagePolicy.mirrorPlayerRules((record, source, amount) -> false))
            .lootPolicy(new BodyLoot())
            .listener(new Listener())
            .nameTagHideTeamId(nameTagHideTeamId)
            .build();
    }

    private static OfflineSpec specFor(ServerPlayer player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.phases().isGameplay()) return OfflineSpec.none();
        UUID id = player.getUUID();
        if (match.isManiac(id)) return OfflineSpec.avatar();
        if (match.isSurvivor(id)) {
            return OfflineSpec.avatar();
        }
        return OfflineSpec.none();
    }

    // ── Доступ для решти мода (усе null-безпечне) ─────────────────────────

    /** Модуль офлайн-присутності або {@code null}, якщо lib ще/вже не живий. */
    public static OfflinePresenceModule module() {
        try {
            return ManiacMod.lib().offlinePresenceModule();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Тіло власника стоїть живим: він «присутній» у матчі, хоч і не онлайн. */
    public static boolean hasBody(UUID owner) {
        OfflinePresenceModule module = module();
        return module != null && module.hasAvatar(owner);
    }

    /** Гравець онлайн або його живе тіло: те, чим можна вдарити/що можна підсвітити. */
    public static Optional<LivingEntity> actor(MinecraftServer server, UUID owner) {
        if (server == null) return Optional.empty();
        return OfflineActors.resolve(server, owner);
    }

    /** Кастомні пози тіла (синхронізуються клієнтам; малює {@code ManiacAvatarRenderer}). */
    public static final int POSE_NONE = 0;
    /** Лежить після падіння (CRAWLING). */
    public static final int POSE_LIE = 1;
    /** Повзе, непритомний (UNCONSCIOUS). */
    public static final int POSE_CRAWL = 2;

    /**
     * UUID учасника матчу для гравця АБО його тіла (у тіла власний UUID сутності інший).
     * {@code null}: це не учасник (мob, предмет тощо).
     */
    public static UUID idOf(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof ServerPlayer player) return player.getUUID();
        return OfflineActors.ownerOf(entity).orElse(null);
    }

    /**
     * Усі, хто може наступити на пастку/зачепити дріт у боксі: онлайн-гравці та ЖИВІ тіла.
     * Вбите чи прибране тіло (запис не STANDING) сюди не потрапляє.
     */
    public static List<LivingEntity> inBox(Level level, AABB box) {
        return level.getEntitiesOfClass(LivingEntity.class, box, e -> {
            if (e instanceof ServerPlayer) return true;
            UUID owner = OfflineActors.ownerOf(e).orElse(null);
            return owner != null && hasBody(owner);
        });
    }

    /** Ставить гравця АБО тіло в точку (для тіла {@code ServerPlayer#teleportTo} недоступний). */
    public static void placeAt(LivingEntity entity, ServerLevel level, double x, double y, double z,
                               float yaw, float pitch) {
        if (entity instanceof ServerPlayer player) {
            player.teleportTo(level, x, y, z, yaw, pitch);
        } else {
            entity.moveTo(x, y, z, yaw, pitch);
        }
    }

    /**
     * Виставляє позу тіла (лежить/повзе/стоїть). Нічого не робить, якщо поза вже та сама:
     * зайві зміни synced-даних шлють пакети.
     */
    public static void applyBodyPose(OfflineAvatarBase body, int poseId) {
        if (body.getCustomPoseId() != poseId) body.setCustomPoseId(poseId);
        // Лежачий має низький хітбокс (0.6×0.6), як гравець у позі плавання. setPose
        // викликаємо щоразу: рівне значення синхронізації не шле, а якщо ваніль скинула
        // позу, хітбокс не лишиться «стоячим» під лежачою моделлю.
        Pose wanted = poseId == POSE_NONE ? Pose.STANDING : Pose.SWIMMING;
        if (body.getPose() != wanted) body.setPose(wanted);
    }

    // ── Лут ───────────────────────────────────────────────────────────────

    private static final class BodyLoot implements OfflineLootPolicy {
        @Override
        public void drop(ServerLevel level, Vec3 pos, java.util.List<ItemStack> items) {
            for (ItemStack stack : items) {
                if (stack.isEmpty()) continue;
                if (!GroundItemSpawner.dropAround(level, pos, stack)) {
                    // Світ не прийняв сутність: предмет усе одно не губиться.
                    ItemEntity fallback = new ItemEntity(level, pos.x, pos.y, pos.z, stack);
                    fallback.setDefaultPickUpDelay();
                    level.addFreshEntity(fallback);
                }
            }
        }
    }

    // ── Події тіла → матч ─────────────────────────────────────────────────

    private static final class Listener implements OfflineListener {
        @Override
        public void onAvatarSpawned(OfflineRecord record) {
            MatchOrchestrator match = ManiacMod.match();
            if (match != null) match.onOfflineBodySpawned(record.owner(), record.ownerName());
        }

        @Override
        public void onAvatarKilled(OfflineRecord record, Entity killer) {
            MatchOrchestrator match = ManiacMod.match();
            if (match != null) match.onOfflineBodyKilled(record.owner(), record.ownerName());
        }
    }
}

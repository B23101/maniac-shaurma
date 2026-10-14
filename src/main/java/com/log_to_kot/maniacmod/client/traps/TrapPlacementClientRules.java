package com.log_to_kot.maniacmod.client.traps;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.traps.ElectricWireArchetype;
import com.log_to_kot.maniacmod.traps.TrapArchetype.PlacementResult;
import com.log_to_kot.maniacmod.traps.TrapPlacementRules;
import com.log_to_kot.maniacmod.traps.WireGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Клієнтське ДЗЕРКАЛО серверних правил «людей і пасток навколо» —
 * {@code TrapPlacementRules} (гравець у клітинці, гравець на дроті) і
 * {@code TrapModule#isOccupied} (там уже стоїть пастка).
 *
 * ── Навіщо (БАГ: причину відмови маньяк дізнавався лише ПІСЛЯ ПКМ) ────
 * Раніше клієнт перевіряв тільки геометрію блоків, а все, що залежить від
 * гравців і вже поставлених пасток, лишав серверові: квадрат світився
 * зеленим, маньяк тиснув ПКМ, чекав пакет туди-назад і лише тоді бачив
 * «Тут стоїть гравець». Тепер ті самі перевірки йдуть на клієнті щотіка,
 * квадрат стає червоним одразу, а причина пишеться на екрані
 * ({@link TrapPlacementHintOverlay}), ще до натискання.
 *
 * ── Про «радар крізь стіни», через який це раніше не робилось ─────────
 * Клієнт і так має позиції всіх гравців у радіусі відстеження (це ж
 * сутності світу), а перевірки стосуються лише клітинки/відрізка, у який
 * маньяк САМ цілиться зі своєї видимої позиції. Нового знання, якого б
 * не було в гравця, що дивиться на цей самий блок, вони не дають.
 *
 * ── Сервер лишається арбітром ────────────────────────────────────────
 * Ці функції — лише підказка: {@code TrapModule#onPlaceConfirmed}
 * перераховує все сам і може відмовити (гонка, застарілі дані).
 * Порядок причин той самий, що в клієнта раніше: спершу геометрія, далі
 * дальність, далі люди, далі зайняте місце.
 */
@OnlyIn(Dist.CLIENT)
final class TrapPlacementClientRules {

    private TrapPlacementClientRules() {}

    /** Гравець-виживий стоїть у клітинці над {@code floor}? (дзеркало {@code isTooCloseToAnySurvivor}) */
    static PlacementResult checkPlayers(ClientLevel level, BlockPos floor) {
        for (Player survivor : activeSurvivors(level)) {
            if (TrapPlacementRules.occupiesCell(floor, survivor.getBoundingBox())) {
                return PlacementResult.TOO_CLOSE_TO_PLAYER;
            }
        }
        return PlacementResult.OK;
    }

    /** На цьому блоці (або на лінії дроту біля нього) уже є пастка? (дзеркало {@code isOccupied}) */
    static PlacementResult checkOccupied(ClientLevel level, BlockPos floor) {
        AABB box = new AABB(floor.above());
        for (Entity e : level.getEntities((Entity) null, box.inflate(0.3),
                x -> x instanceof BearTrapEntity || x instanceof RopeTrapEntity
                    || x instanceof com.log_to_kot.maniacmod.entity.SmokeMineEntity
                    || x instanceof com.log_to_kot.maniacmod.entity.SpikeTrapEntity)) {
            if (e.isAlive()) return PlacementResult.OCCUPIED;
        }

        // Дріт має крихітний бокс у центрі, тож міряємо відстань до відрізка.
        Vec3 probe = ElectricWireArchetype.wireAnchor(level, floor);
        double limitSqr = 0.6 * 0.6;
        for (ElectricWireEntity wire : level.getEntitiesOfClass(ElectricWireEntity.class,
                box.inflate(32.0))) {
            if (!wire.isAlive()) continue;
            if (WireGeometry.distanceToSegmentSqr(probe, wire.anchorA(), wire.anchorB()) < limitSqr) {
                return PlacementResult.OCCUPIED;
            }
        }
        return PlacementResult.OK;
    }

    /** Виживий перетинає відрізок дроту a→b? Такий гравець — «стіна». */
    static PlacementResult checkWireSegment(ClientLevel level, BlockPos a, BlockPos b) {
        Vec3 wa = ElectricWireArchetype.wireAnchor(level, a);
        Vec3 wb = ElectricWireArchetype.wireAnchor(level, b);
        for (Player survivor : activeSurvivors(level)) {
            if (WireGeometry.segmentTouchesBox(wa, wb, survivor.getBoundingBox())) {
                return PlacementResult.OBSTRUCTED;
            }
        }
        return PlacementResult.OK;
    }

    /**
     * Виживі, що ще в грі (не ELIMINATED/ESCAPED), крім самого маньяка, —
     * ті самі, кого сервер бере з {@code match.survivorIds()}. Ростер
     * порожній (ще не прийшов) → нікого: сервер перевірить сам.
     */
    private static java.util.List<Player> activeSurvivors(ClientLevel level) {
        Set<UUID> ids = new HashSet<>();
        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            if (entry.role() == RoleSyncPacket.Role.SURVIVOR && !entry.state().isTerminal()) {
                ids.add(entry.playerId());
            }
        }
        if (ids.isEmpty()) return java.util.List.of();

        Minecraft mc = Minecraft.getInstance();
        java.util.List<Player> result = new java.util.ArrayList<>();
        for (Player p : level.players()) {
            if (p == mc.player || !ids.contains(p.getUUID())) continue;
            result.add(p);
        }
        return result;
    }
}

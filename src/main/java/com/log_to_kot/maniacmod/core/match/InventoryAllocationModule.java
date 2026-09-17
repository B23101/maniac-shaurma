package com.log_to_kot.maniacmod.core.match;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import dev.shaurmalib.forge.inventory.InventorySlotAllocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Хто скільки слотів hotbar бачить — лобі й обидві ролі матчу.
 *
 * ── Чому тут, а не в survivors/ чи maniacs/ ──────────────────────────
 * Обидва предметні пакети (виживий — 4 слоти, маньяк — 0) мають своє
 * власне число слотів, але САМЕ ПРИЗНАЧЕННЯ слотів гравцю — це рішення
 * рівня матчу: воно залежить від фази (LOBBY/ROLE_REVEAL/HUNT/RESET) і
 * від того, ЯКА роль у гравця в ЦЬОМУ матчі, а не від внутрішньої
 * логіки самої ролі. Тому модуль живе поруч з PhaseNetworkSync і
 * MatchStartCoordinator у core.match — так само, як вони, він
 * координує, а не реалізує окрему механіку.
 *
 * ── Правило "0 слотів у лобі" ─────────────────────────────────────────
 * Лобі — це технічна фаза без ролей: жоден гравець ще не виживий і не
 * маньяк, тому інвентар нікому не потрібен узагалі.
 *
 * ── Креатив/глядач: правила до них НЕ застосовуються ────────────────
 * Раніше мод звільняв CREATIVE/SPECTATOR "вручну" (не викликав
 * setHotbarSlotCount), але сама бібліотека на зміні режиму все одно
 * пересилала збережений Allocation — тому перехід у креатив посеред
 * гри лишав гравця з обмеженням слотів і прихованим хотбаром. Тепер це
 * робить бібліотечна політика
 * {@link InventorySlotAllocation.CreativePolicy#EXEMPT}: обмеження не
 * застосовуються і знімаються на зміні режиму. Значення дає конфіг
 * ({@link ConfigSchema#INVENTORY_BYPASS_CREATIVE}); вимкнення повертає
 * правила для всіх режимів.
 *
 * ── Правило "4 у виживого, 0 у маньяка" під час матчу ────────────────
 * Застосовується на вході в ROLE_REVEAL (одразу після SCATTER, коли
 * ролі вже призначені й гравці телепортовані) і лишається на всі
 * ігрові фази. RESET знову зводить усіх до 0 — так наступний LOBBY
 * не встигає показати старий інвентар на кадр раніше, ніж дозволи
 * оновляться самі.
 */
public final class InventoryAllocationModule implements PhaseListener {

    private final Supplier<MatchOrchestrator> matchSupplier;

    public InventoryAllocationModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() {
        return "inventory-allocation";
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        applyCreativePolicy();
        switch (phase) {
            case LOBBY, RESET -> {
                for (ServerPlayer player : players) lockToZero(player);
            }
            case ROLE_REVEAL -> applyMatchAllocation(players);
            default -> { /* HUNT/POWERED/FINALE/ENDING успадковують ROLE_REVEAL, CINEMATIC/SCATTER нікого не чіпають */ }
        }
    }

    /**
     * Викликається також при вході гравця в гру (реконект посеред
     * матчу) — інакше гравець, що перезайшов у фазі HUNT, отримав би
     * дефолтні 9 слотів hotbar до першого переходу фази.
     */
    public void applyOnJoin(ServerPlayer player) {
        applyCreativePolicy();
        MatchOrchestrator match = matchSupplier.get();
        GamePhase phase = match.phases().current();
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET
            || phase == GamePhase.CINEMATIC || phase == GamePhase.SCATTER) {
            lockToZero(player);
            return;
        }
        applyMatchAllocation(player, match);
    }

    private void applyMatchAllocation(List<ServerPlayer> players) {
        MatchOrchestrator match = matchSupplier.get();
        for (ServerPlayer player : players) applyMatchAllocation(player, match);
    }

    private void applyMatchAllocation(ServerPlayer player, MatchOrchestrator match) {
        if (match.isManiac(player.getUUID())) {
            InventorySlotAllocation.setHotbarSlotCount(player, ManiacArchetype.HOTBAR_SLOTS);
            return;
        }
        if (match.isSurvivor(player.getUUID())) {
            int slots = ManiacConfigs.get(ConfigSchema.SURVIVOR_SLOTS);
            InventorySlotAllocation.setHotbarSlotCount(player, slots);
            return;
        }
        // Глядач (реконект після вибуття/втечі, чи приєднався посеред
        // матчу) — теж без слотів: спостерігати нема чим користуватись.
        lockToZero(player);
    }

    private void lockToZero(ServerPlayer player) {
        InventorySlotAllocation.setHotbarSlotCount(player, 0);
    }

    /**
     * Передає бібліотеці поточне значення конфігу. Читається перед
     * кожним застосуванням, тому {@code /maniac reload} міняє поведінку
     * одразу, без рестарту.
     */
    private void applyCreativePolicy() {
        InventorySlotAllocation.setCreativePolicy(
            ManiacConfigs.get(ConfigSchema.INVENTORY_BYPASS_CREATIVE)
                ? InventorySlotAllocation.CreativePolicy.EXEMPT
                : InventorySlotAllocation.CreativePolicy.APPLY);
    }
}

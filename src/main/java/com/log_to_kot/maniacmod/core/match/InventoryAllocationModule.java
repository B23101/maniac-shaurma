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
     *
     * ── Виняток для лобі-morph ────────────────────────────────────────
     * У фазі LOBBY гравці зазвичай без ролі (0 слотів), АЛЕ команда
     * /maniac morph призначає роль (survivor/maniac) саме в LOBBY і
     * одразу викликає цей метод. Раніше тут була безумовна lockToZero
     * для LOBBY/RESET/CINEMATIC/SCATTER — вона стирала щойно видані
     * 4 слоти виживого (маньяка це маскувало, бо в нього й так 0).
     * Тому в LOBBY тепер теж дивимось, чи є в гравця роль у контексті
     * матчу, і застосовуємо її розподіл; немає ролі — лишається 0.
     */
    public void applyOnJoin(ServerPlayer player) {
        applyCreativePolicy();
        MatchOrchestrator match = matchSupplier.get();
        GamePhase phase = match.phases().current();
        if (phase == GamePhase.LOBBY) {
            if (match.isManiac(player.getUUID()) || match.isSurvivor(player.getUUID())) {
                applyMatchAllocation(player, match);
            } else {
                lockToZero(player);
            }
            return;
        }
        if (phase == GamePhase.RESET || phase == GamePhase.CINEMATIC || phase == GamePhase.SCATTER) {
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
            allowItemDrop(player);
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
     * Дозволяє виживому кидати предмет клавішею Q.
     *
     * <p>Бібліотека за замовчуванням створює {@code Allocation} із
     * {@code blockItemDrop = true}: клієнтський міксин
     * ({@code MixinLocalPlayerInventoryDrop}) тоді гасить Q ще до
     * відправки пакета. Кожен виклик {@code setHotbarSlotCount} будує
     * НОВИЙ {@code Allocation} і скидає цей прапорець назад у
     * {@code true} — тому знімати його треба СРАЗУ ПІСЛЯ кожного
     * призначення слотів, і саме тут, де воно єдине для всіх шляхів
     * (старт фази, реконект, лобі-morph). Окремий хук «не встиг би»:
     * гонка з наступним {@code setHotbarSlotCount}.</p>
     *
     * <p>Маньяку й глядачу Q лишається заблокованою — у них 0 слотів,
     * кидати нічого.</p>
     */
    private static void allowItemDrop(ServerPlayer player) {
        InventorySlotAllocation.setItemDropBlocked(player, false);
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

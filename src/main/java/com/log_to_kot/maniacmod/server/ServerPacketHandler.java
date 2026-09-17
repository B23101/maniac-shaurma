package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.abilities.Ability;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.server.level.ServerPlayer;

/**
 * Обробка намірів, надісланих клієнтом.
 *
 * ── Головне правило ──────────────────────────────────────────────────
 * Клієнт надсилає «я натиснув», а не «я зробив». Кожен метод тут
 * заново перевіряє роль, фазу й стан — навіть якщо клієнт уже
 * перевірив те саме. Клієнт може бути модифікований; сервер — ні.
 *
 * Перевірки навмисно однакової форми: спершу матч, потім роль, потім
 * дозвіл фази, потім стан. Якщо колись знадобиться лог «чому дію
 * відхилено», його додають в одному місці, а не в п'яти.
 */
public final class ServerPacketHandler {

    private ServerPacketHandler() {}

    // ── Маньяк ───────────────────────────────────────────────────────────

    /**
     * Здібність за номером клавіші 1–3.
     *
     * Слот поза межами — модифікований клієнт. Мовчки ігноруємо:
     * кидати виняток означало б дати спосіб засмічувати лог сервера.
     */
    public static void onAbilityActivate(ServerPlayer player, int slot) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (slot < 0 || slot >= ManiacArchetype.MAX_ABILITIES) return;
        if (!match.isManiac(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.ABILITIES)) return;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return;

        Ability ability = archetype.abilityAt(slot);
        if (ability == null) return; // слот порожній — у маньяка менше здібностей

        // TODO(міграція abilities): перевірити кулдаун слота й викликати
        // ability.activate(player); кулдаун відповісти
        // AbilityCooldownPacket.abilityId(slot) ОДИН раз.
    }

    /** Пастка за номером слота Z/X/C. */
    public static void onTrapPlace(ServerPlayer player, int slot) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (slot < 0 || slot >= ManiacArchetype.MAX_TRAP_SLOTS) return;
        if (!match.isManiac(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.TRAPS)) return;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return;
        if (archetype.trapAt(slot) == null) return;

        // TODO(міграція traps): порахувати позицію по погляду гравця,
        // перевірити TrapPlacementRules і кулдаун слота.
        // Позиція навмисно рахується тут, а не приходить у пакеті.
    }

    public static void onManiacSelected(ServerPlayer player, String maniacId) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        // Невідомий id — клієнт модифікований або застарів. Мовчки
        // ігноруємо: кидати виняток тут означало б дати будь-кому
        // спосіб засмічувати лог сервера.
        if (!ManiacRegistry.exists(maniacId)) return;

        match.onManiacChosen(player, ManiacRegistry.get(maniacId));
    }

    // ── Виживі ───────────────────────────────────────────────────────────

    public static void onHighlightRequest(ServerPlayer player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.HUD)) return;

        // TODO(міграція survivors): перевірити кулдаун підсвітки (30 с)
        // — сам кулдаун належить ролі виживого, не генераторам.
        match.generatorModule().sendHighlight(player);
    }

    public static void onStandUpAttempt(ServerPlayer player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;

        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.MOVEMENT)) return;
        // Поза станом «збитий з ніг» пакет безглуздий — і саме тут
        // гаситься спам пробілом.
        if (match.survivorStateOf(player.getUUID()) != SurvivorState.CRAWLING) return;

        match.survivors().onStandUpAttempt(player);
    }

    public static void onRescueHold(ServerPlayer player, boolean holding) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return;
        if (!match.isSurvivor(player.getUUID())) return;
        if (!match.phases().allows(PhaseRule.RESCUE)) return;

        match.survivors().onRescueHold(player, holding);
    }
}

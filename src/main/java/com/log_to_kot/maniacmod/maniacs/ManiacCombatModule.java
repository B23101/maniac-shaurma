package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import dev.shaurmalib.common.lock.LockType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.function.Supplier;

/**
 * Удар маньяка.
 *
 * ── Як це працює ─────────────────────────────────────────────────────
 * Удар — ЛКМ по гравцю. Після влучання починається перезарядка, і на
 * її час:
 *   • сервер лочить {@code LockType.ATTACK} через InteractionLock
 *     shaurma-lib — тобто {@code AttackEntityEvent} скасовується
 *     бібліотекою, а не власним обробником;
 *   • клієнт глушить саме натискання ЛКМ міксином, щоб не було ні
 *     замаху, ні звуку — інакше гравець бачить анімацію удару, якого
 *     не сталося.
 *
 * Дві сторони навмисно незалежні: міксин — це зручність, а не захист.
 * Зняти його з клієнта можна, і тоді удар усе одно не пройде, бо
 * сервер тримає лок.
 *
 * ── Дальність ────────────────────────────────────────────────────────
 * Ванільна дальність атаки не підходить: маньяки різного зросту й
 * довжини рук. Дальність бере архетип ({@code attackRangeBlocks}),
 * базове значення — з конфігу.
 *
 * ── Чому Supplier<MatchOrchestrator>, а не Supplier<MatchContext> ────
 * MatchContext package-private у пакеті core.match — цей клас живе в
 * maniacs/ і фізично не може його імпортувати. Усі запити до стану
 * матчу йдуть через вузькі фасадні методи оркестратора.
 */
public final class ManiacCombatModule implements PhaseListener {

    /** Причина локу — рядок, за яким лок знімається саме цей, а не чужий. */
    private static final String LOCK_REASON = "maniac_attack_cooldown";

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** Тіків перезарядки, що лишилось. 0 = можна бити. */
    private int cooldownTicks = 0;

    public ManiacCombatModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() {
        return "maniac-combat";
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        if (!phase.isGameplay()) return;
        cooldownTicks = 0;
        for (ServerPlayer player : players) unlock(player);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (cooldownTicks <= 0) return;
        if (--cooldownTicks > 0) return;

        // Перезарядка добігла — знімаємо лок і кажемо клієнту.
        ServerPlayer maniac = maniacOf(players);
        if (maniac == null) return;
        unlock(maniac);
        ModNetwork.toPlayer(maniac, AbilityCooldownPacket.ready(AbilityCooldownPacket.ATTACK_ID));
    }

    /**
     * Спроба удару. Викликається з обробника {@code AttackEntityEvent}.
     *
     * @return true, якщо удар зарахований — викликач скасовує ванільну
     *         подію в будь-якому разі, бо ванільна шкода тут не діє
     */
    public boolean onAttack(ServerPlayer attacker, Entity target) {
        MatchOrchestrator match = matchSupplier.get();
        if (!match.isManiac(attacker.getUUID())) return false;
        if (!(target instanceof ServerPlayer victim)) return false;
        if (!match.isSurvivor(victim.getUUID())) return false;
        if (cooldownTicks > 0) return false;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return false;

        double range = archetype.attackRangeBlocks();
        if (attacker.distanceTo(victim) > range) return false;

        match.damageSurvivor(victim.getUUID(), archetype.attackDamage());
        startCooldown(attacker, archetype.attackCooldownTicks());
        return true;
    }

    /** Чи маньяк зараз на перезарядці — для HUD і діагностики. */
    public boolean onCooldown() {
        return cooldownTicks > 0;
    }

    private void startCooldown(ServerPlayer maniac, int ticks) {
        cooldownTicks = ticks;
        ManiacMod.lib().interactionLockModule()
            .lock(maniac.getUUID(), LockType.ATTACK, LOCK_REASON);
        ModNetwork.toPlayer(maniac,
            new AbilityCooldownPacket(AbilityCooldownPacket.ATTACK_ID, ticks));
    }

    private void unlock(ServerPlayer player) {
        ManiacMod.lib().interactionLockModule()
            .unlock(player.getUUID(), LockType.ATTACK, LOCK_REASON);
    }

    private ServerPlayer maniacOf(List<ServerPlayer> players) {
        MatchOrchestrator match = matchSupplier.get();
        for (ServerPlayer player : players) {
            if (match.isManiac(player.getUUID())) return player;
        }
        return null;
    }

    /** Чи фаза взагалі дозволяє бити. Винесено, щоб хук не знав деталей. */
    public static boolean damageAllowed() {
        return com.log_to_kot.maniacmod.core.phase.Phases.allows(PhaseRule.DAMAGE);
    }
}

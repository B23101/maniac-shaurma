package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ManiacStunPacket;
import com.log_to_kot.maniacmod.registry.ModSounds;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Стан «оглушений маньяк» — покарання виживого за влучний удар ломом
 * ПО МАНЬЯКУ (на відміну від {@link com.log_to_kot.maniacmod.traps.TrapModule},
 * де лом звільняє з капкана).
 *
 * ── Що саме блокується під час стану ──────────────────────────────────
 * За дизайном (підтверджено користувачем): маньяк «нічого не може» —
 * рух, стрибок, поворот камери, удар (ЛКМ), здібності (1/2/3) і
 * розміщення пасток (5/6/7). Реалізовано трьома незалежними шарами,
 * той самий підхід, що вже є в проєкті для «лежачого» й «трапнутого»:
 *   1. {@link LockType#MOVEMENT} і {@link LockType#ATTACK} через
 *      {@code InteractionLockModule} — справжній захист, працює навіть
 *      якщо клієнтський мод змінено чи міксини не спрацювали.
 *   2. Клієнтські міксини (рух — {@code MixinKeyboardInputManiacStunned},
 *      камера — {@code MixinMouseHandlerManiacStunned},
 *      атака — {@code ManiacAttackGuardMixin} вже сам звіряє
 *      {@code ClientMatchState.isManiacStunned()}) — зручність,
 *      миттєва реакція без чекання ресинку.
 *   3. Здібності й пастки НЕ мають {@code LockType} у бібліотеці (лише
 *      {@code MOVEMENT}/{@code ATTACK} реально використовуються в
 *      проєкті), тому їхнє блокування — прямий прапор
 *      {@link #isStunned(UUID)}, який мають звіряти
 *      {@code ServerPacketHandler.onAbilityActivate} і
 *      {@code TrapModule#place} на самому початку. **Якщо ці методи
 *      цю перевірку ще не мають — це залишок роботи, не забути.**
 *
 * ── Хто вирішує «оглушений чи ні» ─────────────────────────────────────
 * Лише сервер, тут-таки, лічильником {@link #stunTicksLeft}. Клієнт
 * (свій і чужі) лише відображає те, що сервер уже вирішив — той самий
 * принцип «довіри серверу», що й в решті модулів.
 *
 * ── Чому не UNCONSCIOUS/SurvivorState ─────────────────────────────────
 * {@code SurvivorState} — стан ВИЖИВОГО (нога, притомність). Маньяк
 * ніколи не носить цей enum: у нього своя, окрема шкала здоров'я і
 * власний набір модулів ({@link ManiacCombatModule},
 * {@link ManiacSpeedModule}, тепер і цей). Стан оглушення живе поруч
 * із ними, а не всередині survivors-пакета.
 */
public final class ManiacStunModule implements PhaseListener {

    private static final String MOVEMENT_LOCK_REASON = "maniac_stun_movement";
    private static final String ATTACK_LOCK_REASON = "maniac_stun_attack";

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** Тіків оглушення, що лишилось. 0 = маньяк вільний. */
    private int stunTicksLeft = 0;

    /** UUID маньяка, що зараз оглушений (для {@link #isStunned}, коли гравці ще онлайн). */
    private UUID stunnedManiacId = null;

    public ManiacStunModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private MatchOrchestrator match() {
        return matchSupplier.get();
    }

    @Override
    public String id() {
        return "maniac-stun";
    }

    // ── Життєвий цикл фаз ────────────────────────────────────────────────

    /**
     * Свіжий матч не успадковує оглушення попереднього — та сама
     * причина, що {@code ManiacCombatModule#onPhaseEnter}: якщо маньяк
     * вийшов із сервера посеред оглушення, лишок стану й лока не
     * повинен пережити рестарт.
     */
    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase != GamePhase.HUNT) return;
        resetState(players);
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        resetState(players);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (stunTicksLeft <= 0) return;
        if (--stunTicksLeft > 0) return;

        // Щойно дотікало до нуля — звільняємо.
        endStun(players);
    }

    /** Гравець вийшов із сервера — якщо це був оголушений маньяк, звільняємо стан і локи. */
    public void onPlayerLeft(ServerPlayer player) {
        if (stunnedManiacId == null || !stunnedManiacId.equals(player.getUUID())) return;
        stunTicksLeft = 0;
        stunnedManiacId = null;
        unlockAndClearEffect(player);
        // Гравець уже відключається — сповіщати world-глядачів нема
        // сенсу (matchSupplier.get().onlinePlayers() однаково більше
        // не побачить цю сутність), тому широкомовний пакет тут не
        // шлемо, на відміну від endStun().
    }

    private void resetState(List<ServerPlayer> players) {
        stunTicksLeft = 0;
        UUID previous = stunnedManiacId;
        stunnedManiacId = null;
        if (previous == null) return;
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(previous)) {
                unlockAndClearEffect(player);
                break;
            }
        }
    }

    private void endStun(List<ServerPlayer> players) {
        UUID maniacId = stunnedManiacId;
        stunnedManiacId = null;
        if (maniacId == null) return;

        ServerPlayer maniac = findPlayer(players, maniacId);
        if (maniac != null) unlockAndClearEffect(maniac);

        ModNetwork.toPlayers(players, new ManiacStunPacket(maniacId, false, 0));
    }

    private void unlockAndClearEffect(ServerPlayer maniac) {
        ManiacMod.lib().interactionLockModule()
            .unlock(maniac.getUUID(), LockType.MOVEMENT, MOVEMENT_LOCK_REASON);
        ManiacMod.lib().interactionLockModule()
            .unlock(maniac.getUUID(), LockType.ATTACK, ATTACK_LOCK_REASON);
        maniac.removeEffect(MobEffects.CONFUSION);
    }

    private static ServerPlayer findPlayer(List<ServerPlayer> players, UUID id) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(id)) return player;
        }
        return null;
    }

    // ── Удар ─────────────────────────────────────────────────────────────

    /**
     * Виживий вдарив ломом ПО МАНЬЯКУ (ЛКМ). Викликається з хука
     * {@code ServerHooks#onAttackEntity}, той самий патерн, що
     * {@code TrapModule#onCrowbarHit}.
     *
     * @return true, якщо удар зараховано (навіть якщо лом зламаний чи
     *         на перезарядці — у цих випадках подію теж треба
     *         скасувати, інакше ванільний удар пройде поверх) —
     *         хук тоді скасовує ванільний {@code attack}; false — не
     *         наш випадок, подію не чіпаємо
     */
    public boolean onCrowbarHitManiac(ServerPlayer hitter, ServerPlayer maniac, ItemStack crowbar) {
        if (!(crowbar.getItem() instanceof CrowbarItem)) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.DAMAGE)) return false;
        if (!m.isSurvivor(hitter.getUUID())) return false;
        if (!m.isManiac(maniac.getUUID())) return false;

        // Зламаний лом і лом на перезарядці не працюють — той самий
        // порядок перевірок і ті самі повідомлення, що для капкана:
        // гравець має зрозуміти, ЧОМУ клік нічого не зробив.
        if (CrowbarItem.isBroken(crowbar)) {
            notify(hitter, ActionBarMessageType.ERROR, "maniacmod.trap.crowbar_broken");
            return true;
        }
        if (hitter.getCooldowns().isOnCooldown(crowbar.getItem())) {
            notify(hitter, ActionBarMessageType.COOLDOWN, "maniacmod.trap.crowbar_cooldown");
            return true;
        }

        double range = ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_RANGE_BLOCKS);
        if (hitter.getEyePosition().distanceToSqr(maniac.position().add(0, 0.9, 0)) > range * range) {
            return false;
        }

        // Маньяк уже оглушений (наприклад, другий виживий вдарив у той
        // самий момент) — не продовжуємо і не «освіжаємо» таймер: за
        // дизайном лом усе одно платить свою ціну (знос+кулдаун), щоб
        // не заохочувати спам ударів по вже безпечній цілі, але сам
        // стан не подовжується нескінченно груповими ударами.
        boolean alreadyStunned = stunnedManiacId != null && stunnedManiacId.equals(maniac.getUUID());

        int wearCost = ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_HIT_WEAR_PERCENT);
        int left = CrowbarItem.wear(crowbar, wearCost);
        hitter.getCooldowns().addCooldown(crowbar.getItem(),
            ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_COOLDOWN_TICKS));
        notify(hitter, left > 0 ? ActionBarMessageType.INFO : ActionBarMessageType.ERROR,
            left > 0 ? "maniacmod.stun.crowbar_used" : "maniacmod.trap.crowbar_broke",
            String.valueOf(left));

        // Звук удару ломом — той самий, що й по капкану: для вуха це одна
        // дія (залізо б'є по залізу). Грає з СЕРВЕРА й з місця маньяка,
        // тож чути всім поблизу — оглушеного маньяка потрібно помітити.
        hitter.level().playSound(null, maniac.blockPosition(),
            ModSounds.CROWBAR_HIT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);

        if (alreadyStunned) return true;

        beginStun(maniac);
        return true;
    }

    private void beginStun(ServerPlayer maniac) {
        int duration = ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_DURATION_TICKS);
        int nauseaAmplifier = ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_NAUSEA_AMPLIFIER);

        stunTicksLeft = duration;
        stunnedManiacId = maniac.getUUID();

        ManiacMod.lib().interactionLockModule()
            .lock(maniac.getUUID(), LockType.MOVEMENT, MOVEMENT_LOCK_REASON);
        ManiacMod.lib().interactionLockModule()
            .lock(maniac.getUUID(), LockType.ATTACK, ATTACK_LOCK_REASON);

        // false, false: не показувати частинки навколо гравця (вони й
        // так рясні від капкана/лома) і не показувати іконку ефекту в
        // інвентарі — HUD оглушення й так має власні 5 зірочок, ванільна
        // іконка нудоти лише задублювала б інформацію.
        maniac.addEffect(new MobEffectInstance(MobEffects.CONFUSION, duration, nauseaAmplifier, false, false));

        spawnHitParticles(maniac);

        MatchOrchestrator m = match();
        List<ServerPlayer> everyone = m != null ? m.onlinePlayers() : List.of(maniac);
        ModNetwork.toPlayers(everyone, new ManiacStunPacket(maniac.getUUID(), true, duration));
    }

    private static void spawnHitParticles(ServerPlayer maniac) {
        if (!(maniac.level() instanceof ServerLevel level)) return;
        level.sendParticles(ParticleTypes.CRIT,
            maniac.getX(), maniac.getY() + 1.0, maniac.getZ(),
            18, 0.35, 0.5, 0.35, 0.05);
    }

    private void notify(ServerPlayer player, ActionBarMessageType type, String key, String... args) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key, args));
    }

    // ── Читання стану ────────────────────────────────────────────────────

    /** Чи ЦЕЙ гравець зараз оглушений. Використовується для блокування здібностей/пасток. */
    public boolean isStunned(UUID playerId) {
        return stunTicksLeft > 0 && stunnedManiacId != null && stunnedManiacId.equals(playerId);
    }

    /** Чи ХТОСЬ зараз оглушений (для HUD/діагностики). */
    public boolean anyoneStunned() {
        return stunTicksLeft > 0;
    }

    public int stunTicksLeft() {
        return stunTicksLeft;
    }
}

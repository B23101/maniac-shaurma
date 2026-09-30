package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.items.BatItem;
import com.log_to_kot.maniacmod.items.CrowbarItem;
import com.log_to_kot.maniacmod.items.OwnCooldowns;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ManiacStunPacket;
import com.log_to_kot.maniacmod.registry.ModSounds;
import dev.shaurmalib.common.lock.LockType;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
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

    /**
     * На скільки тіків нудота ставиться ДОВШЕ за залишок стану (див.
     * {@link #keepNauseaAlive}). Це ж значення задає темп донакладання:
     * ефект тримається з запасом у 1 секунду, тож якщо клієнт втратив
     * його посеред стану, найгірша пауза без нудоти — цей самий запас.
     */
    private static final int NAUSEA_REFRESH_MARGIN_TICKS = 20;

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
        // Власні перезарядки предметів (біта) не переживають рестарт матчу.
        OwnCooldowns.clearAll(players);
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        resetState(players);
        OwnCooldowns.clearAll(players);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (stunTicksLeft <= 0) return;
        if (--stunTicksLeft > 0) {
            keepNauseaAlive(players);
            return;
        }

        // Щойно дотікало до нуля — звільняємо.
        endStun(players);
    }

    /**
     * Тримає нудоту прив'язаною до СТАНУ, а не до власного таймера
     * ефекту: за дизайном нудота має тривати рівно стільки, скільки
     * оглушення, а не «перші дві секунди».
     *
     * <p>Раніше ефект ставився один раз у {@link #beginStun} і більше
     * ніхто його не чіпав — якщо його щось прибирало чи скорочувало
     * (сторонній код, ресинк ефектів), нудота зникала, а стан
     * продовжувався. Тепер раз на {@link #NAUSEA_REFRESH_MARGIN_TICKS}
     * тіків перевіряємо й донакладаємо: донакладання з БІЛЬШОЮ
     * тривалістю змушує ванільний код надіслати клієнтам свіжий
     * {@code ClientboundUpdateMobEffectPacket}, тож нудить знову.</p>
     *
     * <p>Перевірка «лишилось менше за решту стану» (а не «донакладати
     * щотік») важлива саме заради мережі: щотікове переписування тієї
     * самої тривалості надсилало б гравцям пакет щотік без жодної
     * причини.</p>
     */
    private void keepNauseaAlive(List<ServerPlayer> players) {
        if (stunnedManiacId == null) return;
        ServerPlayer maniac = findPlayer(players, stunnedManiacId);
        if (maniac == null) return;

        MobEffectInstance current = maniac.getEffect(MobEffects.CONFUSION);
        if (current != null && current.getDuration() > stunTicksLeft) return;

        maniac.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
            stunTicksLeft + NAUSEA_REFRESH_MARGIN_TICKS,
            ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_NAUSEA_AMPLIFIER), false, false));
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
        // 0% — лом ламається повністю й зникає з руки.
        CrowbarItem.breakIfWornOut(hitter, crowbar);
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

    /**
     * Виживий вдарив БІТОЮ по маньяку (ЛКМ). Той самий стан оглушення, що від
     * лома ({@link #onCrowbarHitManiac}), але: довше ({@code batStunDurationTicks}),
     * 50% міцності за удар, ВЛАСНА перезарядка ({@link OwnCooldowns}) замість
     * ванільної — її малює бар під предметом.
     *
     * @return true, якщо удар оброблено (навіть якщо біта зламана чи на
     *         перезарядці — тоді ванільний удар теж треба скасувати); false —
     *         не наш випадок
     */
    public boolean onBatHitManiac(ServerPlayer hitter, ServerPlayer maniac, ItemStack bat) {
        if (!(bat.getItem() instanceof BatItem)) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.DAMAGE)) return false;
        if (!m.isSurvivor(hitter.getUUID())) return false;
        if (!m.isManiac(maniac.getUUID())) return false;

        if (BatItem.isBroken(bat)) {
            notify(hitter, ActionBarMessageType.ERROR, "maniacmod.stun.bat_broken");
            return true;
        }
        if (OwnCooldowns.isOnCooldown(hitter, BatItem.COOLDOWN_KEY)) {
            notify(hitter, ActionBarMessageType.COOLDOWN, "maniacmod.stun.bat_cooldown");
            return true;
        }

        double range = ManiacConfigs.get(ConfigSchema.BAT_STUN_RANGE_BLOCKS);
        if (hitter.getEyePosition().distanceToSqr(maniac.position().add(0, 0.9, 0)) > range * range) {
            return false;
        }

        // Уже оглушений маньяк не «освіжається», але біта платить повну ціну
        // (знос + перезарядка) — та сама логіка, що в лома.
        boolean alreadyStunned = stunnedManiacId != null && stunnedManiacId.equals(maniac.getUUID());

        int left = BatItem.wear(bat, ManiacConfigs.get(ConfigSchema.BAT_STUN_HIT_WEAR_PERCENT));
        BatItem.breakIfWornOut(hitter, bat);
        OwnCooldowns.start(hitter, BatItem.COOLDOWN_KEY, ManiacConfigs.get(ConfigSchema.BAT_STUN_COOLDOWN_TICKS));
        notify(hitter, left > 0 ? ActionBarMessageType.INFO : ActionBarMessageType.ERROR,
            left > 0 ? "maniacmod.stun.bat_used" : "maniacmod.stun.bat_broke",
            String.valueOf(left));

        // Окремого звуку біти ще нема — той самий «метал/дерево по цілі», що лом.
        hitter.level().playSound(null, maniac.blockPosition(),
            ModSounds.CROWBAR_HIT.get(), SoundSource.PLAYERS, 1.0f, 0.85f);

        if (alreadyStunned) return true;

        beginStun(maniac, ManiacConfigs.get(ConfigSchema.BAT_STUN_DURATION_TICKS));
        return true;
    }

    private void beginStun(ServerPlayer maniac) {
        beginStun(maniac, ManiacConfigs.get(ConfigSchema.CROWBAR_STUN_DURATION_TICKS));
    }

    /** Той самий стан для будь-якої зброї — різниться лише тривалість (лом 8 с, біта 12 с). */
    private void beginStun(ServerPlayer maniac, int duration) {
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
        maniac.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
            duration + NAUSEA_REFRESH_MARGIN_TICKS, nauseaAmplifier, false, false));

        spawnHitParticles(maniac);

        MatchOrchestrator m = match();
        List<ServerPlayer> everyone = m != null ? m.onlinePlayers() : List.of(maniac);
        ModNetwork.toPlayers(everyone, new ManiacStunPacket(maniac.getUUID(), true, duration));
    }

    // ── Дебаг ────────────────────────────────────────────────────────────

    /**
     * Дебаг-версія {@link #beginStun}: приголомшує маньяка тим самим
     * ефектом, що справжній удар ломом ({@code /maniac debug stun}), але
     * БЕЗ реального удару — без гравця-виживого поруч, без списання
     * міцності лома й без перевірки дальності. Той самий стан
     * ({@code stunTicksLeft}, лок руху/атаки, ефект нудоти, пакет
     * усім спостерігачам), що й {@link #onCrowbarHitManiac}, лише
     * запущений напряму, для тестування без другого гравця з ломом у
     * руці.
     *
     * Той самий "продовжити чи ні" підхід, що в бойовому шляху: другий
     * запуск команди на вже оглушеного маньяка НЕ подовжує стан — щоб
     * команду не використовували як спосіб тримати маньяка оглушеним
     * нескінченно, повторюючи її.
     *
     * @return true, якщо стан оглушення застосовано; false — маньяк уже
     *         був оглушений (стан не продовжено, але це не помилка)
     */
    public boolean debugStun(ServerPlayer maniac) {
        boolean alreadyStunned = stunnedManiacId != null && stunnedManiacId.equals(maniac.getUUID());
        if (alreadyStunned) return false;

        beginStun(maniac);
        return true;
    }

    private static void spawnHitParticles(ServerPlayer maniac) {
        if (!(maniac.level() instanceof ServerLevel level)) return;
        level.sendParticles(com.log_to_kot.maniacmod.registry.ModParticles.STUN_STAR.get(),
            maniac.getX(), maniac.getY() + 1.2, maniac.getZ(),
            10, 0.35, 0.45, 0.35, 0.03);
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

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
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * Удар маньяка.
 *
 * ── Як це працює ─────────────────────────────────────────────────────
 * Удар — ЛКМ маньяка. Клієнт шле {@code ManiacStrikePacket} ("я
 * тиснув ЛКМ"), а ХТО постраждав вирішує {@link #onAttack} на сервері:
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
 * ── Чому НЕ ванільний AttackEntityEvent ──────────────────────────────
 * Раніше ціль удару бралась із {@code event.getTarget()} — сутності,
 * яку ЗНАЙШОВ КЛІЄНТ своїм raytrace на екрані. Проблема: той raytrace
 * обмежений ванільним pick range гравця (~3 блоки у виживанні) — це
 * hardcoded число {@code GameMode.getPickRange()}, а не атрибут
 * сутності, і в MC 1.20.1 його не можна розширити стандартним
 * {@code AttributeModifier} (entity/block interaction range з'явились
 * лише в 1.20.5+). Тобто якщо адмін виставляв {@code attackRangeBlocks}
 * БІЛЬШЕ за ванільний pick range (конфіг дозволяє аж до 8 блоків),
 * клієнт просто ніколи не знаходив ціль під прицілом на такій
 * дистанції — {@code AttackEntityEvent} не виникала взагалі, і
 * дальність удару мовчки залишалась прив'язаною до ванільних ~3
 * блоків, скільки не міняй конфіг.
 *
 * Тепер сервер сам шукає ціль — {@link #findTarget} — точно в конусі
 * погляду маньяка на дистанції {@code archetype.attackRangeBlocks()},
 * тим самим підходом, що {@code GeneratorModule#canWork} (кут через
 * dot product). Клієнтський raytrace і ванільний pick range тут ні до
 * чого: обмеження працює РІВНО на те число, яке задає архетип/конфіг,
 * незалежно від того, більше воно за ванільний reach чи менше.
 *
 * ── Чому Supplier<MatchOrchestrator>, а не Supplier<MatchContext> ────
 * MatchContext package-private у пакеті core.match — цей клас живе в
 * maniacs/ і фізично не може його імпортувати. Усі запити до стану
 * матчу йдуть через вузькі фасадні методи оркестратора.
 */
public final class ManiacCombatModule implements PhaseListener {

    /** Причина локу — рядок, за яким лок знімається саме цей, а не чужий. */
    private static final String LOCK_REASON = "maniac_attack_cooldown";

    /**
     * Дистанції впритул кут дивитись безглуздо (той самий поріг, що
     * {@code GeneratorModule#canWork} для ремонту) — маньяк, що стоїть
     * майже в упор до жертви, не мусить цілитись точно в центр
     * хітбокса, щоб удар зарахувався.
     */
    private static final double POINT_BLANK_BLOCKS = 0.75;

    /**
     * Сила відкидання жертви — МАЛЕНЬКА підача (ванільний меч ≈ 0.4).
     * Було 1.1 — це відкидало гравця на десятки блоків. 0.25 ≈ відступ
     * на 1–2 блоки: удар відчутний, але жертву не «вистрілює».
     */
    private static final double KNOCKBACK_STRENGTH = 0.25;
    /** Мінімальний вертикальний імпульс, коли жертва не на землі. */
    private static final double KNOCKBACK_MIN_LIFT = 0.08;

    /** Шкода дебаг-удару, коли архетипу маньяка немає (одиночний дебаг). */
    private static final int DEBUG_FALLBACK_DAMAGE = 50;

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

    /**
     * Свіжий матч ніколи не успадковує перезарядку чи лок попереднього.
     *
     * ── Чому не лише {@link #onPhaseExit} ────────────────────────────
     * Лок {@code ATTACK} ставиться після удару й знімається, коли
     * {@code cooldownTicks} дотікає до нуля в {@link #onPhaseTick}. Але
     * якщо маньяк вийшов із сервера посеред перезарядки (тоді
     * {@code maniacOf} повертає null і {@code unlock} не викликається),
     * або матч урвано командою в технічній фазі, лок лишався в
     * статичному реєстрі бібліотеки НАЗАВЖДИ — і наступний матч маньяк
     * відкривав із забороненою атакою: клік проходив, замах був, а
     * сервер мовчки скасовував удар.
     *
     * Скидаємо на ВХОДІ в кожну ігрову фазу теж, а не лише на виході.
     */
    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (phase != GamePhase.HUNT) return;
        resetCooldownAndLocks(players);
    }

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // Будь-який вихід, не лише з ігрової фази: перезарядка й лок
        // не мають переживати перехід, після якого удар уже неможливий.
        resetCooldownAndLocks(players);
    }

    /**
     * Гравець вийшов із сервера — знімаємо його лок ЗА UUID.
     *
     * Списки {@code players} у {@link #onPhaseExit} містять лише тих,
     * хто онлайн, тож для вже відключеного маньяка {@code unlock} там не
     * викликається, а запис у статичному реєстрі lib лишається для UUID,
     * якого вже немає. Якщо цей же гравець зайде знову — він стартує з
     * чужим замком, якого сам не ставив.
     */
    public void onPlayerLeft(ServerPlayer player) {
        cooldownTicks = 0;
        unlock(player);
    }

    private void resetCooldownAndLocks(List<ServerPlayer> players) {
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
     * Спроба удару. Викликається з обробника {@code ManiacStrikePacket} —
     * "маньяк тиснув ЛКМ", БЕЗ жодної цілі від клієнта. Ціль сервер
     * шукає сам через {@link #findTarget}, тому дальність і кут
     * визначаються виключно серверними числами архетипу, а не тим, що
     * зміг "побачити" клієнтський raytrace у межах ванільного reach.
     *
     * @return true, якщо удар зарахований
     */
    public boolean onAttack(ServerPlayer attacker) {
        MatchOrchestrator match = matchSupplier.get();
        if (!match.isManiac(attacker.getUUID())) return false;
        if (!damageAllowed()) return false;
        if (cooldownTicks > 0) return false;
        // Оглушений ударом лома — «нічого не може» включає атаку.
        // LockType.ATTACK уже стоїть (ManiacStunModule.beginStun), але,
        // як і cooldownTicks вище, справжнє блокування тут — явна
        // перевірка стану, а не сам факт локу (лок — додатковий шар для
        // бібліотеки/HUD, не єдине джерело правди в цьому модулі).
        if (match.maniacStun().isStunned(attacker.getUUID())) return false;

        ManiacArchetype archetype = match.maniacArchetype();
        if (archetype == null) return false;

        ServerPlayer victim = findTarget(match, attacker, archetype.attackRangeBlocks());
        if (victim == null) return false;

        double yawRad = Math.toRadians(attacker.getYRot());
        resolveHit(match, victim, archetype.attackDamage(), Math.sin(yawRad), -Math.cos(yawRad));
        startCooldown(attacker, archetype.attackCooldownTicks());
        return true;
    }

    /**
     * Спільний шлях влучання — і для справжнього удару ({@link #onAttack}),
     * і для дебаг-команди: шкода (якщо {@code damage >= 0}), стан жертви,
     * віддача та екранний ефект. Кулдаун сюди НЕ входить — його ставить
     * лише справжній удар.
     *
     * @param damage шкода в хп; від'ємне — без шкоди (лише віддача й ефект)
     * @return true, якщо удар збив жертву з ніг
     */
    private boolean resolveHit(MatchOrchestrator match, ServerPlayer victim, int damage,
                               double dirX, double dirZ) {
        boolean downed = false;
        if (damage >= 0) {
            downed = match.damageSurvivor(victim.getUUID(), damage);
            if (downed) {
                // Удар збив із ніг — жертва непритомна, адреналін їй не потрібен.
                match.survivors().onSurvivorDowned(victim);
            } else {
                // Живий після удару отримує швидкість I і повну стаміну —
                // шанс утекти (див. SurvivorModule.onManiacHit).
                match.survivors().onManiacHit(victim);
            }
        }
        applyHitRecoil(victim, dirX, dirZ);
        ModNetwork.toPlayer(victim,
            new com.log_to_kot.maniacmod.net.s2c.notify.ManiacHitPacket(downed));
        return downed;
    }

    /**
     * Дебаг-удар ({@code /maniac debug strike}): те саме влучання, що й
     * справжнє, але без цілі-пошуку, дальності, кулдауну й перевірки фази.
     *
     * @param attacker маньяк, від якого рахується напрямок віддачі; може
     *                 бути {@code null} (одиночний дебаг без маньяка) —
     *                 тоді жертву відкидає назад від її власного погляду
     * @param withDamage true — реальна шкода (лише для виживого), false —
     *                   тільки віддача й екранний ефект
     * @return true, якщо удар збив жертву з ніг
     */
    public boolean debugStrike(ServerPlayer victim, ServerPlayer attacker, boolean withDamage) {
        MatchOrchestrator match = matchSupplier.get();

        double dirX, dirZ;
        if (attacker != null && attacker != victim) {
            double yaw = Math.toRadians(attacker.getYRot());
            dirX = Math.sin(yaw);
            dirZ = -Math.cos(yaw);
        } else {
            double yaw = Math.toRadians(victim.getYRot());
            dirX = -Math.sin(yaw);
            dirZ = Math.cos(yaw);
        }

        int damage = -1;
        if (withDamage && match.isSurvivor(victim.getUUID())
            && match.survivorStateOf(victim.getUUID())
                != com.log_to_kot.maniacmod.survivors.SurvivorState.UNCONSCIOUS) {
            ManiacArchetype archetype = match.maniacArchetype();
            damage = archetype != null ? archetype.attackDamage() : DEBUG_FALLBACK_DAMAGE;
        }
        return resolveHit(match, victim, damage, dirX, dirZ);
    }

    /**
     * Реальна віддача удару: жертву відкидає у напрямку удару
     * ({@code dirX/dirZ} — одиничний вектор «від маньяка»), з невеликим
     * підкиданням, а сама швидкість одразу синхронізується з клієнтом
     * жертви — інакше сервер відкинув би її, а клієнт цього б не побачив
     * (гравець керує рухом сам). Плюс ванільна анімація/звук отримання шкоди.
     */
    private void applyHitRecoil(ServerPlayer victim, double dirX, double dirZ) {
        victim.knockback(KNOCKBACK_STRENGTH, dirX, dirZ);
        // Ванільний knockback підкидає лише з землі; додаємо мінімальний
        // підйом, щоб відкидання було помітним і в повітрі, і на слизькому.
        if (!victim.onGround() && victim.getDeltaMovement().y < KNOCKBACK_MIN_LIFT) {
            victim.setDeltaMovement(victim.getDeltaMovement().x, KNOCKBACK_MIN_LIFT,
                                    victim.getDeltaMovement().z);
        }
        victim.hurtMarked = true;
        victim.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(victim));
        // Подія 2 = «отримав удар»: червоний спалах моделі + звук болю.
        victim.level().broadcastEntityEvent(victim, (byte) 2);
    }

    /**
     * Шукає найближчого виживого, придатного під удар: у межах
     * {@code range} блоків від очей маньяка й не далі за
     * {@code REPAIR}-подібний конус погляду (тут — фіксовано 60° від
     * напрямку камери, того самого порядку, що ванільний
     * pick-по-сутностях). Той самий прийом, що
     * {@code GeneratorModule#canWork}: кут через dot product, а
     * впритул ({@code POINT_BLANK_BLOCKS}) кут не перевіряється взагалі,
     * бо напрямок "на впритул ціль" хаотично стрибає від мікрорухів.
     *
     * Лежачого (UNCONSCIOUS) серед кандидатів немає: його доля
     * вирішується таймером чи підняттям союзником, а не повторним
     * ударом маньяка.
     *
     * Серед кількох придатних цілей у конусі обирається НАЙБЛИЖЧА —
     * так маньяк завжди б'є того, хто фактично під прицілом, а не
     * випадкового виживого з групи.
     */
    private ServerPlayer findTarget(MatchOrchestrator match, ServerPlayer attacker, double range) {
        Vec3 eye = attacker.getEyePosition();
        Vec3 look = attacker.getLookAngle();

        ServerPlayer best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (ServerPlayer candidate : attacker.getServer().getPlayerList().getPlayers()) {
            if (candidate == attacker) continue;
            if (candidate.level() != attacker.level()) continue;
            if (!match.isSurvivor(candidate.getUUID())) continue;
            if (match.survivorStateOf(candidate.getUUID())
                == com.log_to_kot.maniacmod.survivors.SurvivorState.UNCONSCIOUS) continue;

            Vec3 toCandidate = candidate.getEyePosition().subtract(eye);
            double distSq = toCandidate.lengthSqr();
            if (distSq > range * range) continue;

            double distance = Math.sqrt(distSq);
            if (distance > POINT_BLANK_BLOCKS) {
                double cos = look.dot(toCandidate) / distance;
                // 60° конус: досить широкий, щоб не вимагати піксель-
                // точного прицілювання, і досить вузький, щоб не бити
                // когось збоку.
                if (cos < Math.cos(Math.toRadians(60))) continue;
            }

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
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

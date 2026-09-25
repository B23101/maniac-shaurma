package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Швидкість руху маньяка: підвищена ходьба й повна відсутність спринту.
 *
 * ── Дизайн ───────────────────────────────────────────────────────────
 * Маньяк НЕ має спринту взагалі. Його швидкість — це звичайна ходьба
 * гравця, помножена на множник із конфігу ЦЬОГО маньяка (за
 * замовчуванням 1.2, тобто на 20% швидше за ходьбу виживого). Це його
 * «біг».
 *
 * Два окремі механізми, бо одного недостатньо:
 *   1. Атрибутний модифікатор {@code MOVEMENT_SPEED} — підвищує ходьбу;
 *   2. Щотіка {@code setSprinting(false)} — знімає спринт. Без цього
 *      подвійне W дало б спринт ПОВЕРХ множника, і 1.2 «до ходьби»
 *      перетворилось би на ~1.56 «до ходьби» — не те, що задумано.
 *
 * ── Чому не в ManiacCombatModule ─────────────────────────────────────
 * Швидкість — окрема властивість маньяка, не бойова. Свій модуль
 * простіший за розмноження причин змінитись у боєвому.
 *
 * ── Де живе множник ──────────────────────────────────────────────────
 * {@link ManiacArchetype#speedMultiplier()} — це ЗАВЖДИ значення з
 * файла цього маньяка ({@code maniac_stats/<id>.yml}); свій характер
 * архетип задає через {@code declaredSpeedMultiplier()}, який стає
 * дефолтом у тому ж файлі. Один рядок у yml міняє швидкість одного
 * маньяка — і нічию більше.
 *
 * ── Відоме обмеження ─────────────────────────────────────────────────
 * Спринт скидається на СЕРВЕРІ. Клієнт може на кадр-два показати
 * спринтову анімацію/FOV, доки не прийде корекція. Для прибирання цього
 * потрібен клієнтський міксин (як {@code MixinLivingEntityStaminaSprint}
 * у lib для виживих); його не додано, бо його не можна перевірити без
 * запуску гри — див. звіт про роботу.
 */
public final class ManiacSpeedModule implements PhaseListener {

    /** Постійний id модифікатора: щоб зняти рівно свій, а не чужий. */
    private static final UUID SPEED_MODIFIER_ID =
        UUID.fromString("8c3e6a1f-2d47-4b9e-a5c0-71f4d2b8e963");

    private final Supplier<MatchOrchestrator> matchSupplier;

    public ManiacSpeedModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() {
        return "maniac-speed";
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        // Неігрова фаза (лобі, кінець, скидання) — маньяк ходить як усі.
        if (isActivePhase(phase)) return;
        for (ServerPlayer player : players) removeSpeed(player);
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        MatchOrchestrator match = matchSupplier.get();
        if (match == null || !isActivePhase(phase)) return;

        ManiacArchetype archetype = match.maniacArchetype();
        for (ServerPlayer player : players) {
            if (!match.isManiac(player.getUUID()) || archetype == null) {
                // Гравець втратив роль маньяка (morph) — знімаємо, інакше
                // модифікатор лишився б на ньому назавжди.
                removeSpeed(player);
                continue;
            }
            ensureSpeed(player, archetype.speedMultiplier());
            if (player.isSprinting()) player.setSprinting(false);
        }
    }

    /**
     * Швидкість діє протягом усієї гри, а не лише в HUNT: маньяк
     * ходить однаково під час розстановки й полювання. Фази без
     * руху ({@code MOVEMENT}) тут не перевіряються — заморозку рухів
     * тримає lib, а швидкість їй не заважає.
     */
    private static boolean isActivePhase(GamePhase phase) {
        return phase == GamePhase.HUNT || phase == GamePhase.SCATTER || phase == GamePhase.ROLE_REVEAL;
    }

    /**
     * Ставить модифікатор, а якщо конфіг змінився за час матчу —
     * знімає застарілий і ставить наново (як
     * {@code SurvivorModule#ensureCrawlSpeed}): порівнюємо ЗНАЧЕННЯ, а
     * не лише наявність, інакше старий множник приліпився б до атрибута.
     */
    private void ensureSpeed(ServerPlayer player, double multiplier) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        AttributeModifier existing = speed.getModifier(SPEED_MODIFIER_ID);
        double amount = multiplier - 1.0;
        if (existing != null) {
            if (existing.getAmount() == amount) return;
            speed.removeModifier(SPEED_MODIFIER_ID);
        }
        speed.addTransientModifier(new AttributeModifier(
            SPEED_MODIFIER_ID, "maniacmod_maniac_speed",
            amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    private void removeSpeed(ServerPlayer player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED_MODIFIER_ID);
    }

    /** Гравець вийшов — модифікатор транзієнтний і сам зникне, але знімаємо явно. */
    public void onPlayerLeft(ServerPlayer player) {
        removeSpeed(player);
    }
}

package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Серверна половина «тіла» маньяка: хітбокс і висота очей з архетипу.
 *
 * ── Чому подія, а не {@code PhaseListener} ───────────────────────────
 * Тут немає нічого, що треба рахувати щотіка: габарити потрібні РІВНО
 * тоді, коли їх питає рушій — а це і є {@code EntityEvent.Size} /
 * {@code EntityEvent.EyeHeight}. Модуль-слухач фаз лише дублював би
 * відповідь (і ризикував би розійтися з нею, якщо гравець змінив роль
 * посеред фази). Подія — найвужча точка входу, яка гарантує
 * узгодженість у кожному кадрі/тіку без жодного стану.
 *
 * ── Клієнт ───────────────────────────────────────────────────────────
 * Цей обробник свідомо пропускає клієнтську сторону
 * ({@code level().isClientSide()}) — там працює
 * {@code ClientManiacBodyEvents}, який читає той самий архетип із
 * ростеру. Два обробники не конфліктують, бо кожен діє на своїй стороні.
 *
 * ── Габарити ─────────────────────────────────────────────────────────
 * {@link EntityDimensions#scalable(float, float)} — той самий метод, що
 * використовує ванільний гравець, тож хітбокс коректно масштабується
 * рушієм (а не ламає колізії фіксованим розміром).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacBodyEvents {

    private ManiacBodyEvents() {}

    /**
     * Forge позначив {@code EntityEvent.Size} як {@code forRemoval} — але в
     * 1.20.1 він усе ще ЄДИНИЙ спосіб змінити габарити сутності, і
     * {@code ForgeEventFactory} його досі створює й розсилає. Придушення,
     * а не замовчування: коли з'явиться заміна, зміниться й це місце.
     */
    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onSize(EntityEvent.Size event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        ManiacArchetype archetype = archetypeOf(player);
        if (archetype == null) return;
        event.setNewSize(EntityDimensions.scalable(archetype.hitboxWidth(), archetype.hitboxHeight()));
        event.setNewEyeHeight(archetype.eyeHeight());
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onEyeHeight(EntityEvent.EyeHeight event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        ManiacArchetype archetype = archetypeOf(player);
        if (archetype == null) return;
        event.setNewEyeHeight(archetype.eyeHeight());
    }

    /** Архетип активного маньяка матчу, якщо цей гравець — саме він. */
    private static ManiacArchetype archetypeOf(Player player) {
        MatchOrchestrator match = ManiacMod.match();
        if (match == null || !match.isManiac(player.getUUID())) return null;
        return match.maniacArchetype();
    }
}

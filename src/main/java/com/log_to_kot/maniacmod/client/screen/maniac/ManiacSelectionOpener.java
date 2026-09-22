package com.log_to_kot.maniacmod.client.screen.maniac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Відкриває {@link ManiacSelectScreen}, коли клієнт бачить: я маньяк,
 * фаза ROLE_REVEAL, архетип ще НЕ обрано.
 *
 * ── Чому тік, а не мережевий пакет ────────────────────────────────────
 * На відміну від {@code TrapCatalogPacket} (де перелік ЗАЛЕЖИТЬ від
 * архетипу, і сервер мусить його порахувати), список маньяків —
 * {@code ManiacRegistry}, статичні дані мода, однакові на клієнті й
 * сервері без жодної мережі (див. {@code client/screen/maniac/README.md}).
 * Тому «час відкрити екран» — це не подія, яку шле сервер, а стан,
 * який клієнт бачить сам: {@code RoleSyncPacket} з порожнім
 * {@code archetypeId} — це і є сигнал «MENU-режим, обирай сам»
 * ({@link ManiacSelection.Choice#needsMenu()} на сервері перетворюється
 * рівно на такий порожній id).
 *
 * ── Чому не відкриває повторно ────────────────────────────────────────
 * Перевірка «чи вже відкритий {@link ManiacSelectScreen}» у циклі тіку
 * — інакше {@code setScreen(new ...)} виконувався б щотік, поки триває
 * ROLE_REVEAL, перебиваючи власний стан гравця (скрол/курсор) щоразу.
 *
 * ── Закриття ─────────────────────────────────────────────────────────
 * Явного forceClose немає: щойно {@code archetypeId()} стає непорожнім
 * (сервер прислав повторний {@code RoleSyncPacket} після
 * {@code onManiacChosen} — див. {@code MatchOrchestrator}), цей клас
 * сам не намагається переоткрити екран, а сам екран закриває себе після
 * відправки вибору (див. {@link ManiacSelectScreen#confirm}).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacSelectionOpener {

    private ManiacSelectionOpener() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        boolean needsMenu = ClientMatchState.isManiac()
            && ClientMatchState.phase() == GamePhase.ROLE_REVEAL
            && ClientMatchState.archetypeId().isEmpty();
        if (!needsMenu) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof ManiacSelectScreen) return; // вже відкритий
        if (mc.screen != null) return; // не перебиваємо чат/інвентар/інше меню гравця

        mc.setScreen(new ManiacSelectScreen());
    }
}

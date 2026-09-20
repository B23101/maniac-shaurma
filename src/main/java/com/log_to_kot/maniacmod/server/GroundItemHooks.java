package com.log_to_kot.maniacmod.server;

import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.core.phase.Phases;
import com.log_to_kot.maniacmod.loot.GroundItemSpawner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge-хуки системи предметів на землі.
 *
 * <h3>Кидок (Q)</h3>
 * Ванільний {@code Player.drop} спершу ЗНІМАЄ стек із інвентаря, створює
 * {@code ItemEntity} і лише тоді питає Forge через {@link ItemTossEvent}.
 * Тому тут ми:
 * <ol>
 *   <li>беремо стек із події (він уже вилучений — повертати в інвентар
 *       НЕ треба, інакше предмет задублюється);</li>
 *   <li>скасовуємо подію — ванільний {@code ItemEntity} у світ не
 *       потрапляє;</li>
 *   <li>створюємо {@link com.log_to_kot.maniacmod.entity.GroundItemEntity} з тим самим стеком (з NBT).</li>
 * </ol>
 * Якщо наша сутність не створилась — <b>НЕ</b> скасовуємо подію, щоб
 * ванільний предмет усе ж упав: краще ванільна сутність, що згодом
 * зникне, ніж предмет, зниклий назавжди без сліду.
 *
 * <h3>Чому тут немає пробудження від зламаного блока</h3>
 * {@code BlockEvent.BreakEvent} спрацьовує ДО того, як блок зникне, тож
 * прокинутий ним предмет побачив би опору, що ще є, і одразу заснув би.
 * Замість хука спляча сутність сама перевіряє опору раз на
 * {@code SUPPORT_RECHECK_TICKS} тіків — це самодостатній і надійніший
 * механізм (працює однаково для блока, поршня, вибуху, команди).
 */
public final class GroundItemHooks {

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        // Лише в ІГРОВИХ фазах (HUNT/POWERED/FINALE) І коли фаза дозволяє
        // предмети. У решті (LOBBY, ENDING...) лишаємо ванільну поведінку.
        //
        // Чому не достатньо ITEM_USE: LOBBY теж його має, а
        // ServerHooks.onEntityJoin знищує в LOBBY кожну сутність, зареєстровану
        // як «сутність матчу». Кинута там наша сутність зникла б, а стек
        // уже вилучено з інвентаря — предмет був би втрачений назавжди
        // (це реальний сценарій: /maniac morph survivor у лобі дає 4 слоти).
        if (!Phases.isGameplay() || !Phases.allows(PhaseRule.ITEM_USE)) return;

        ItemStack stack = event.getEntity().getItem();
        if (stack.isEmpty()) return;

        boolean created = GroundItemSpawner.throwFrom(player, stack);
        if (created) {
            event.setCanceled(true); // ванільний ItemEntity не потрібен
        }
        // created == false → подію НЕ чіпаємо: предмет упаде ванільно.
    }
}

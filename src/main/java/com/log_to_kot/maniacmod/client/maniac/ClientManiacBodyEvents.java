package com.log_to_kot.maniacmod.client.maniac;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Клієнтська половина «тіла» маньяка: хітбокс і висота очей.
 *
 * ── Чому це потрібно й на клієнті ────────────────────────────────────
 * {@code EntityEvent.Size} стріляє на ОБОХ сторонах, і клієнтський
 * хітбокс — це не косметика: по ньому рахуються колізії, рейкаст
 * прицілу, показ імені й позиція камери. Якби розмір мінявся лише на
 * сервері, чужий гравець бачив би триблочного маньяка «звичайним» і
 * бив би в порожнечу повз нього.
 *
 * ── Чому окремо від серверного обробника ─────────────────────────────
 * Серверний ({@code ManiacBodyEvents}) читає стан матчу
 * ({@code MatchOrchestrator}), якого на клієнті немає; клієнтський читає
 * ростер ({@code ClientManiacs}). Числа приходять із СЕРВЕРА в ростері
 * ({@code RosterSyncPacket.ManiacBody}) — і це принципово: габарити
 * маньяка налаштовуються у його власному yml, а файл є і на клієнті
 * теж, зі своїми дефолтами. Без синхронізації триблочний маньяк на
 * виділеному сервері виглядав би для чужого клієнта звичайним (і той
 * бив би повз нього). Локальний архетип лишається лише фолбеком на
 * час між появою маньяка й першим ростером.
 *
 * ── Присідання ───────────────────────────────────────────────────────
 * Висота НЕ зменшується на присідання: у маньяка «сидяча» поза — це
 * sneak-анімація моделі, а не зміна габаритів. Так хітбокс лишається
 * стабільним, і присідання не ламає рейкаст по маньяку.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientManiacBodyEvents {

    private ClientManiacBodyEvents() {}

    @SuppressWarnings("removal") // див. ManiacBodyEvents: у 1.20.1 іншого способу немає
    @SubscribeEvent
    public static void onSize(EntityEvent.Size event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ManiacArchetype archetype = ClientManiacs.archetypeOf(player.getUUID());
        if (archetype == null) return;
        RosterSyncPacket.ManiacBody body = ClientManiacs.bodyOf(player.getUUID());
        float width = body == null ? archetype.hitboxWidth() : body.width();
        float height = body == null ? archetype.hitboxHeight() : body.height();
        event.setNewSize(EntityDimensions.scalable(width, height));
        event.setNewEyeHeight(body == null ? archetype.eyeHeight() : body.eyeHeight());
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onEyeHeight(EntityEvent.EyeHeight event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ManiacArchetype archetype = ClientManiacs.archetypeOf(player.getUUID());
        if (archetype == null) return;
        RosterSyncPacket.ManiacBody body = ClientManiacs.bodyOf(player.getUUID());
        event.setNewEyeHeight(body == null ? archetype.eyeHeight() : body.eyeHeight());
    }
}

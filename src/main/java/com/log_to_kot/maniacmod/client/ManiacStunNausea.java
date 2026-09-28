package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Нудота оглушеного маньяка — ГАРАНТОВАНО на весь стан (ТЗ: нудота має
 * тривати весь стан, а не перші дві секунди).
 *
 * ── Навіщо клієнтський шар, якщо ефект і так ставить сервер ───────────
 * Ванільна нудота — це ефект {@code MobEffects.CONFUSION}, а ефекти
 * живуть за власним таймером, окремо від {@code stunTicksLeft}. Якщо
 * ефект із будь-якої причини зник (сторонній код, ресинк, повторний
 * вхід у світ) — стан оглушення продовжується, а нудити перестає.
 * Сервер це підстраховує донакладанням раз на секунду
 * ({@code ManiacStunModule.keepNauseaAlive}), але між донакладанням і
 * втратою ефекту лишається до секунди без нудоти.
 *
 * Цей клас прибирає й ту секунду: щотік звіряє, чи я оглушений, і якщо
 * ефект ось-ось дотікає — донакладає його ЛОКАЛЬНО. Локальне
 * донакладання не шле жодних пакетів (ефект уже наш, клієнтський), і
 * воно лише видовжує те, що вже показав сервер — жодного нового стану
 * клієнт собі не вигадує.
 *
 * ── Чому лише для СЕБЕ ───────────────────────────────────────────────
 * Нудота — це викривлення ЕКРАНА, його бачить лише сам маньяк. Інші
 * гравці бачать оглушеного за зірочками над головою
 * ({@code ManiacStunWorldMarker}), а не за нудотою, тож донакладати
 * щось чужим гравцям нема сенсу.
 *
 * ── Джерело стану ────────────────────────────────────────────────────
 * {@link ClientMatchState#isManiacStunned()} — те саме дзеркало, що
 * читають HUD і блокування камери. Ніяких власних таймерів тут немає:
 * щойно сервер каже «стан скінчився», донакладання припиняється одразу.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ManiacStunNausea {

    /**
     * Запас, з яким ефект тримається поперед стану. Достатньо, щоб нудота
     * не «блимала» між тіками, і мало, щоб після звільнення ефект зник
     * разом зі станом, а не висів зайву секунду.
     */
    private static final int NAUSEA_MARGIN_TICKS = 30;

    /**
     * Донакладаємо, коли лишилось менше за це число тіків: тобто раз на
     * ~1.5 с, а не щотік. Тримати довше нема сенсу — сервер усе одно
     * володіє станом і щойно скінчить його, ми перестанемо донакладати.
     */
    private static final int REFRESH_THRESHOLD_TICKS = 10;

    private ManiacStunNausea() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ClientMatchState.isManiacStunned()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        MobEffectInstance current = player.getEffect(MobEffects.CONFUSION);
        if (current != null && current.getDuration() > REFRESH_THRESHOLD_TICKS) return;

        // Амплітуду беремо з УЖЕ НАЯВНОГО ефекту, а не жорстко 0: ванільний
        // MobEffectInstance.update() видовжує тривалість лише коли новий
        // ефект має не меншу амплітуду. З нулем ми б мовчки не подовжили
        // нічого там, де сервер поставив рівень 1 (конфіг
        // crowbarStunNauseaAmplifier) — тобто весь цей клас був би no-op
        // саме в тому випадку, для якого його написано.
        int amplifier = current != null ? current.getAmplifier() : 0;

        // false, false — без частинок навколо гравця й без іконки ефекту в
        // інвентарі: цю роль уже виконують власний HUD зірочок і
        // підсвітка. Мета тут — рівно ВИКРИВЛЕННЯ ЕКРАНА (нудота).
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
            NAUSEA_MARGIN_TICKS, amplifier, false, false));
    }
}

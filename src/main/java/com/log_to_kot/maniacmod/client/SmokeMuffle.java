package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.ManiacMod;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Приглушення звуків у хмарі димової міни («глушить звуки навколо»).
 *
 * ── Як саме глушимо ──────────────────────────────────────────────────
 * Множимо ЗАГАЛЬНУ гучність ({@link SoundSource#MASTER}) через
 * {@code SoundManager#updateSourceVolume}: це змінює лише підсилення
 * слухача звукового рушія, тож приглушується все (світ, кроки, голоси,
 * власні звуки мода), а гучності з налаштувань гравця й самі звуки не
 * чіпаються — після хмари значення просто повертається з налаштувань.
 *
 * ── Чому підтримуємо щотіка ──────────────────────────────────────────
 * Якщо гравець посеред хмари крутить повзунок гучності, ваніль виставить
 * підсилення з нового значення без нашого множника. Щотіка повертаємо
 * приглушене значення, поки сервер не скаже «хмара скінчилась».
 *
 * ── Стан «я в газі» для екранних плям диму ───────────────────────────
 * Той самий пакет ({@code SmokeMufflePacket}) — єдиний сигнал клієнту «ти
 * в хмарі / вийшов із неї». Тому {@link #isActive()} читає і
 * {@code SmokeScreenOverlay} (плями диму на екрані). При множнику 1.0
 * (глушіння вимкнене в конфігу) гучність не чіпаємо, а стан лишається.
 *
 * ── Джерело правди ───────────────────────────────────────────────────
 * Сервер ({@code SmokeMufflePacket}): коли вмикати й з яким множником.
 * Власних таймерів тут немає; ми лише гарантовано знімаємо глушіння на
 * виході з гри й при скиданні стану матчу.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, value = Dist.CLIENT,
                        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SmokeMuffle {

    private static boolean active = false;
    private static float factor = 1.0f;
    /** Чи ми зараз тримаємо приглушене підсилення (щоб не чіпати рушій без потреби). */
    private static boolean applied = false;

    private SmokeMuffle() {}

    /** Команда сервера: ввімкнути (з множником 0..1) чи вимкнути глушіння. */
    public static void set(boolean on, float newFactor) {
        active = on;
        factor = Math.max(0.0f, Math.min(1.0f, newFactor));
        if (on) apply(); else restore();
    }

    /** Чи гравець зараз у газі (команда сервера). Читає екранний ефект диму. */
    public static boolean isActive() {
        return active;
    }

    /** Скидання стану: вихід із гри, кінець матчу. */
    public static void reset() {
        active = false;
        factor = 1.0f;
        restore();
        com.log_to_kot.maniacmod.client.overlay.effects.SmokeScreenOverlay.reset();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) return;
        apply();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    private static void apply() {
        // Множник 1.0 — глушіння вимкнене (smokeMufflePercent=0): рушій не чіпаємо.
        if (factor >= 0.999f) return;
        Minecraft mc = Minecraft.getInstance();
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, master * factor);
        applied = true;
    }

    private static void restore() {
        if (!applied) return;
        applied = false;
        Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().updateSourceVolume(SoundSource.MASTER,
            mc.options.getSoundSourceVolume(SoundSource.MASTER));
    }
}

package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Єдина точка, де серверні пакети торкаються клієнтського стану.
 *
 * НАВІЩО окремий клас: у v3 кожен пакет у своєму handle() напряму
 * смикав конкретний оверлей — тому оверлей не можна було замінити, не
 * правлячи пакети, а пакети не можна було тестувати без клієнта.
 * Тепер пакет знає лише про цей клас, а цей клас — про клієнтський
 * стан.
 *
 * Клас @OnlyIn(CLIENT): на виділеному сервері його немає в рантаймі,
 * тому пакети звертаються сюди ТІЛЬКИ через DistExecutor.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandler {

    private ClientPacketHandler() {}

    public static void onPhase(GamePhase phase) {
        ClientMatchState.setPhase(phase);
    }

    public static void onRole(RoleSyncPacket.Role role, String archetypeId) {
        ClientMatchState.setRole(role, archetypeId);
    }

    public static void onVitals(int hp, int maxHp, float stamina,
                                SurvivorState state, float heartbeat) {
        ClientMatchState.setVitals(hp, maxHp, stamina, state, heartbeat);
    }

    public static void onAbilityCooldown(String abilityId, int totalTicks) {
        ClientMatchState.setAbilityCooldown(abilityId, totalTicks, clientTick());
    }

    public static void onGeneratorHighlight(int durationTicks,
                                            List<GeneratorHighlightPacket.Entry> entries) {
        ClientMatchState.setHighlight(entries, durationTicks, clientTick());
    }

    public static void onGeneratorProgress(GeneratorProgressPacket packet) {
        com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay.accept(packet);
    }

    /**
     * Actionbar через shaurma-lib. Текст збирається ТУТ, з клієнтської
     * локалізації — сервер прислав лише ключ і аргументи, тому гравець
     * бачить повідомлення своєю мовою, а не мовою сервера.
     */
    public static void onActionBar(ActionBarMessageType type, String translationKey, String[] args) {
        Object[] boxed = new Object[args.length];
        System.arraycopy(args, 0, boxed, 0, args.length);
        Component text = Component.translatable(translationKey, boxed);
        dev.shaurmalib.forge.overlay.ActionBarMessageSystem.show(type, text.getString());
    }

    /** Ростер усіх гравців матчу — джерело даних для таб-екрану. */
    public static void onRoster(List<RosterSyncPacket.RosterEntry> entries) {
        ClientMatchState.setRoster(entries);
    }

    public static void onCountdown(int digit, int accentArgb) {
        if (digit <= 0) {
            dev.shaurmalib.forge.overlay.AnimatedCountdownSystem.clear();
            return;
        }
        dev.shaurmalib.forge.overlay.AnimatedCountdownSystem.show(digit, accentArgb);
    }

    /** Лічильник клієнтських тіків — база для локального відліку кулдаунів. */
    private static long clientTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }
}

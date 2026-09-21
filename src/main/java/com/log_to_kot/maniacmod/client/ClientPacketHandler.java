package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.client.overlay.notify.GeneratorCompletedOverlay;
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
        if (phase == GamePhase.ENDING) {
            Minecraft.getInstance().setScreen(
                new com.log_to_kot.maniacmod.client.screen.statistics.MatchResultScreen());
        }
    }

    public static void onRole(RoleSyncPacket.Role role, String archetypeId) {
        ClientMatchState.setRole(role, archetypeId);
    }

    /** Конфіг блиску предметів на землі ({@code loot.sparkleEnabled}), синхронізований із сервера. */
    public static void onGroundItemVisualSettings(boolean sparkleEnabled) {
        ClientMatchState.setGroundItemSparkleEnabled(sparkleEnabled);
    }

    public static void onVitals(int hp, int maxHp, float stamina,
                                SurvivorState state, float heartbeat) {
        ClientMatchState.setVitals(hp, maxHp, stamina, state, heartbeat);
    }

    public static void onDownedSurvivors(List<com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket.Entry> entries) {
        ClientMatchState.setDowned(entries);
    }

    public static void onRescueProgress(int progress, int required, boolean asVictim) {
        ClientMatchState.setRescueProgress(progress, required, asVictim);
    }

    public static void onStandUpProgress(int presses, int required) {
        ClientMatchState.setStandUpProgress(presses, required);
    }

    public static void onAbilityCooldown(String abilityId, int totalTicks) {
        ClientMatchState.setAbilityCooldown(abilityId, totalTicks, clientTick());
    }

    public static void onGeneratorHighlight(int durationTicks,
                                            List<GeneratorHighlightPacket.Entry> entries) {
        ClientMatchState.setHighlight(entries, durationTicks, clientTick());
        // Малює саме маркер: ClientMatchState лише тримає дані. Раніше
        // підсвітку не малював ніхто, тож клавіша 5 нічого не показувала.
        com.log_to_kot.maniacmod.client.overlay.notify.GeneratorHighlightMarker
            .show(entries, durationTicks);
    }

    public static void onGeneratorProgress(GeneratorProgressPacket packet) {
        com.log_to_kot.maniacmod.client.overlay.actionprogress.GeneratorProgressOverlay.accept(packet);
    }

    // ── Міні-ігри ремонту ────────────────────────────────────────────────

    public static void onTargetMinigameOpen(
            com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket packet) {
        ClientInputHandler.forceReleaseRepairHold();
        Minecraft.getInstance().setScreen(new com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen(
            packet.seed(), packet.cursorSpeed(), packet.hitZoneWidth(),
            packet.targetPosition(), packet.hitsRequired()));
    }

    public static void onWireMinigameOpen(
            com.log_to_kot.maniacmod.net.s2c.minigame.WireMinigameOpenPacket packet) {
        ClientInputHandler.forceReleaseRepairHold();
        Minecraft.getInstance().setScreen(new com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen(
            packet.initialRightSlotForLeft(), packet.timeLimitTicks()));
    }

    /**
     * Часткове просування всередині вже відкритої міні-гри. Пакет один
     * на обидві міні-ігри — розрізняємо за тим, який екран зараз
     * відкрито, а не за вмістом пакета (див. клас-докстрінг пакета).
     */
    public static void onRepairMinigameProgress(
            com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen target) {
            target.onHit(packet.hitsSoFar());
        } else if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen wires) {
            wires.onWireConnected(packet.leftSlot(), packet.rightSlot());
        }
    }

    /** Міні-гра завершена (успіх чи провал) — закриває який завгодно з двох екранів міні-ігор. */
    public static void onRepairMinigameResult(
            com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.TargetMinigameScreen target) {
            target.onResult(packet.success());
        } else if (screen instanceof com.log_to_kot.maniacmod.client.screen.minigame.WireMinigameScreen wires) {
            wires.onResult(packet.success());
        }
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

    /**
     * Ростер усіх гравців матчу — джерело даних для
     * {@code client/overlay/roster/TabRosterOverlay} (утримання Tab).
     */
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

    /** "Генератор N з M полагоджено" — див. {@link GeneratorCompletedOverlay}. */
    public static void onGeneratorCompleted(int index, int total) {
        GeneratorCompletedOverlay.show(index, total);
    }

    /** Генератор вибухнув — червоний маркер на екрані, див. {@code GeneratorExplosionMarker}. */
    public static void onGeneratorExplosion(net.minecraft.core.BlockPos pos, int durationTicks) {
        com.log_to_kot.maniacmod.client.overlay.notify.GeneratorExplosionMarker.show(pos, durationTicks);
    }

    // ── Меню налаштувань ─────────────────────────────────────────────────

    /**
     * Відкриває {@code SettingsMenuScreen}, або, якщо він уже відкритий
     * (гравець щойно змінив поле й чекає на підтверджене значення),
     * оновлює його на місці замість пересоздання — щоб скрол і фокус
     * поля не скидались при кожній зміні.
     */
    public static void onOpenSettingsMenu(
            com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket packet) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof com.log_to_kot.maniacmod.client.screen.settings.SettingsMenuScreen settings) {
            settings.onValuesUpdated(packet.values());
        } else {
            Minecraft.getInstance().setScreen(
                new com.log_to_kot.maniacmod.client.screen.settings.SettingsMenuScreen(packet.values()));
        }
    }

    /** Лічильник клієнтських тіків — база для локального відліку кулдаунів. */
    private static long clientTick() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }
}

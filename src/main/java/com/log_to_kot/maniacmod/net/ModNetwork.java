package com.log_to_kot.maniacmod.net;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.net.c2s.intent.AbilityActivatePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.HighlightTogglePacket;
import com.log_to_kot.maniacmod.net.c2s.minigame.TargetMinigameClickPacket;
import com.log_to_kot.maniacmod.net.c2s.minigame.WireMinigameDropPacket;
import com.log_to_kot.maniacmod.net.c2s.settings.SettingsChangePacket;
import com.log_to_kot.maniacmod.net.c2s.settings.OpenSettingsMenuRequestPacket;
import com.log_to_kot.maniacmod.net.c2s.selection.ManiacSelectPacket;
import com.log_to_kot.maniacmod.net.c2s.hold.RescueHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.hold.GeneratorRepairHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.StandUpPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapPlacePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.WireMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.CountdownPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorCompletedPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorExplosionPacket;
import com.log_to_kot.maniacmod.net.s2c.loot.GroundItemVisualSettingsPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.StandUpProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.PhaseSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.vitals.SurvivorVitalsPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.List;
import java.util.function.Function;

/**
 * Мережевий канал мода.
 *
 * Канал мода окремий від каналу shaurma-lib ({@code shaurma_lib}) —
 * так версії пакетів бібліотеки й мода не переплутаються при
 * незалежних оновленнях.
 *
 * ── Як додати пакет ──────────────────────────────────────────────────
 *   1. Створити клас у {@code net/s2c} або {@code net/c2s},
 *      реалізувати {@link ModPacket}.
 *   2. Дописати ОДИН рядок у {@link #register()} — s2c(...) або c2s(...).
 *   3. Підняти PROTOCOL_VERSION, якщо змінено формат наявного пакета.
 *
 * ⚠ ID пакетів — це позиція в {@link #register()}. Не вставляй новий
 * пакет усередину списку: клієнт старої версії прочитає чужі байти.
 * Додавай тільки в кінець.
 */
public final class ModNetwork {

    /**
     * Піднімати при БУДЬ-ЯКІЙ зміні формату пакетів. Клієнт і сервер
     * з різними версіями просто не з'єднаються — це краще, ніж
     * зчитати чужі байти й отримати незрозумілий краш посеред матчу.
     */
    private static final String PROTOCOL_VERSION = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(ManiacMod.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private ModNetwork() {}

    public static void register() {
        // ── Сервер → клієнт ─────────────────────────────────────────────
        s2c(PhaseSyncPacket.class,          PhaseSyncPacket::new);
        s2c(RoleSyncPacket.class,           RoleSyncPacket::new);
        s2c(SurvivorVitalsPacket.class,     SurvivorVitalsPacket::new);
        s2c(GeneratorProgressPacket.class,  GeneratorProgressPacket::new);
        s2c(GeneratorHighlightPacket.class, GeneratorHighlightPacket::new);
        s2c(AbilityCooldownPacket.class,    AbilityCooldownPacket::new);
        s2c(ActionBarPacket.class,          ActionBarPacket::new);
        s2c(CountdownPacket.class,          CountdownPacket::new);
        s2c(RosterSyncPacket.class,         RosterSyncPacket::new);
        s2c(TargetMinigameOpenPacket.class, TargetMinigameOpenPacket::new);
        s2c(WireMinigameOpenPacket.class,   WireMinigameOpenPacket::new);
        s2c(RepairMinigameProgressPacket.class, RepairMinigameProgressPacket::new);
        s2c(RepairMinigameResultPacket.class,   RepairMinigameResultPacket::new);
        s2c(OpenSettingsMenuPacket.class,       OpenSettingsMenuPacket::new);
        s2c(StandUpProgressPacket.class,        StandUpProgressPacket::new);
        s2c(GeneratorCompletedPacket.class,     GeneratorCompletedPacket::new);
        s2c(GeneratorExplosionPacket.class,     GeneratorExplosionPacket::new);
        s2c(GroundItemVisualSettingsPacket.class, GroundItemVisualSettingsPacket::new);

        // ── Клієнт → сервер ─────────────────────────────────────────────
        c2s(AbilityActivatePacket.class,    AbilityActivatePacket::new);
        c2s(HighlightTogglePacket.class,    HighlightTogglePacket::new);
        c2s(StandUpPacket.class,            StandUpPacket::new);
        c2s(RescueHoldPacket.class,         RescueHoldPacket::new);
        c2s(GeneratorRepairHoldPacket.class, GeneratorRepairHoldPacket::new);
        c2s(ManiacSelectPacket.class,       ManiacSelectPacket::new);
        c2s(TrapPlacePacket.class,          TrapPlacePacket::new);
        c2s(TargetMinigameClickPacket.class, TargetMinigameClickPacket::new);
        c2s(WireMinigameDropPacket.class,    WireMinigameDropPacket::new);
        c2s(SettingsChangePacket.class,      SettingsChangePacket::new);
        c2s(OpenSettingsMenuRequestPacket.class, OpenSettingsMenuRequestPacket::new);

        // Нові пакети — ТІЛЬКИ в кінець свого блоку.
    }

    // ── Реєстрація ───────────────────────────────────────────────────────

    private static <T extends ModPacket> void s2c(Class<T> type, Function<FriendlyByteBuf, T> decoder) {
        register(type, decoder, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static <T extends ModPacket> void c2s(Class<T> type, Function<FriendlyByteBuf, T> decoder) {
        register(type, decoder, NetworkDirection.PLAY_TO_SERVER);
    }

    /**
     * Спільна реєстрація. {@code consumerMainThread} гарантує, що
     * handle() вже в головному потоці — тому жоден пакет не пише
     * власний enqueueWork (у v3 його забували, і стан правився з
     * мережевого потоку).
     */
    private static <T extends ModPacket> void register(Class<T> type,
                                                        Function<FriendlyByteBuf, T> decoder,
                                                        NetworkDirection direction) {
        CHANNEL.messageBuilder(type, nextId++, direction)
            .encoder(ModPacket::encode)
            .decoder(decoder::apply)
            .consumerMainThread((packet, ctx) -> {
                packet.handle(ctx.get());
                ctx.get().setPacketHandled(true);
            })
            .add();
    }

    // ── Відправка ────────────────────────────────────────────────────────

    public static void toPlayer(ServerPlayer player, ModPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void toPlayers(List<ServerPlayer> players, ModPacket packet) {
        for (ServerPlayer player : players) toPlayer(player, packet);
    }

    public static void toAll(ModPacket packet) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
    }

    /** Усім, крім одного — типовий випадок «усі бачать очікування, крім того, хто вибирає». */
    public static void toAllExcept(List<ServerPlayer> players, ServerPlayer excluded, ModPacket packet) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(excluded.getUUID())) continue;
            toPlayer(player, packet);
        }
    }

    public static void toServer(ModPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    /** Зручність для пакетів: NetworkEvent.Context → відправник. */
    public static ServerPlayer sender(NetworkEvent.Context ctx) {
        ServerPlayer sender = ctx.getSender();
        if (sender == null) throw new IllegalStateException(
            "C2S-пакет без відправника — перевір напрямок реєстрації в ModNetwork.");
        return sender;
    }
}

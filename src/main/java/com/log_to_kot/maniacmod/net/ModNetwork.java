package com.log_to_kot.maniacmod.net;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.RescueProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.DownedSurvivorsPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.AbilityActivatePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.GroundItemPickupPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.HighlightTogglePacket;
import com.log_to_kot.maniacmod.net.c2s.intent.ManiacStrikePacket;
import com.log_to_kot.maniacmod.net.c2s.minigame.TargetMinigameClickPacket;
import com.log_to_kot.maniacmod.net.c2s.minigame.WireMinigameDropPacket;
import com.log_to_kot.maniacmod.net.c2s.settings.SettingsChangePacket;
import com.log_to_kot.maniacmod.net.c2s.settings.OpenSettingsMenuRequestPacket;
import com.log_to_kot.maniacmod.net.c2s.selection.ManiacSelectPacket;
import com.log_to_kot.maniacmod.net.c2s.hold.RescueHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.hold.GeneratorRepairHoldPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.StandUpPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapEscapeStartPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.RopeSwingPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RopeMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.c2s.intent.TrapPlacePacket;
import com.log_to_kot.maniacmod.net.c2s.traps.TrapChoosePacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapCatalogPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.WireMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.settings.OpenSettingsMenuPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.CountdownPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorCompletedPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorExplosionPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ManiacStunPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ManiacHitPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.TrapEscapePromptPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorInteractionPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ItemPickupPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.TrapVictimGlowPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.SmokeMufflePacket;
import com.log_to_kot.maniacmod.net.s2c.notify.WireShockPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TrapEscapeAttemptPacket;
import com.log_to_kot.maniacmod.net.s2c.darkzone.DarkZoneStatePacket;
import com.log_to_kot.maniacmod.net.s2c.loot.GroundItemVisualSettingsPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.StandUpProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.PhaseSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.ObjectiveSyncPacket;
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
     *
     * 15: {@code WireMinigameOpenPacket} несе ще й кольори правої колодки.
     * 16: {@code ObjectiveSyncPacket} — ціль матчу для tab-екрана.
     * 17: оцінки маньяків — {@code ManiacRatingSyncPacket} (S2C) і
     *     {@code ManiacRatingSubmitPacket} (C2S).
     * 18: адмін-екран відгуків — {@code AdminReviewsPacket} (S2C), відкривається
     *     командою {@code /maniac reviews}.
     * 19: димова міна — {@code SmokeMufflePacket} (S2C): приглушити звуки в хмарі.
     * 20: бризки крові від удару маньяка — {@code BloodSplatterPacket} (S2C).
     * 21: шипи — {@code SpikeHitPacket} (S2C): екранний ефект «потрапив на шипи».
     * 28: витягування цвяхів барикади — {@code BarricadePullPacket} (S2C).
     * 29: міні-гра барикади замість утримання ПКМ: {@code BarricadeGameOpenPacket},
     *     {@code BarricadeNailPacket}, {@code BarricadeGameClosePacket} (S2C) і
     *     {@code BarricadeNailHoldPacket}, {@code BarricadeGameExitPacket} (C2S);
     *     {@code BarricadePullPacket} прибрано.
     * 27: барикади — {@code BarricadeBuildStatePacket} (S2C): буфер і прев'ю режиму будування.
     * 32: історія матчів — {@code HistoryListPacket}, {@code HistoryDetailPacket} (S2C) і
     *     {@code HistoryListRequestPacket}, {@code HistoryDetailRequestPacket} (C2S), {@code OpenHistoryPacket} (S2C, команда).
     * 33: лідерборди — {@code LeaderboardPagePacket}, {@code LeaderboardOverviewPacket} (S2C) і
     *     {@code LeaderboardRequestPacket} (C2S).
     * 23: гаки — {@code DownedSurvivorsPacket} несе статус (лежить/несуть/висить) і може не мати
     *     таймера; новий {@code HookHighlightPacket} (S2C) — вільні гаки для маньяка, що несе жертву.
     */
    private static final String PROTOCOL_VERSION = "33";

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
        s2c(DownedSurvivorsPacket.class,          DownedSurvivorsPacket::new);
        s2c(RescueProgressPacket.class,           RescueProgressPacket::new);
        s2c(TrapCatalogPacket.class,              TrapCatalogPacket::new);
        s2c(TrapLoadoutPacket.class,              TrapLoadoutPacket::new);
        s2c(ManiacStunPacket.class,               ManiacStunPacket::new);

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
        c2s(TrapChoosePacket.class,              TrapChoosePacket::new);
        c2s(ManiacStrikePacket.class,            ManiacStrikePacket::new);

        // Нові пакети — ТІЛЬКИ в кінець свого блоку.
        s2c(DarkZoneStatePacket.class,           DarkZoneStatePacket::new);
        c2s(TrapEscapeStartPacket.class,          TrapEscapeStartPacket::new);
        s2c(TrapEscapePromptPacket.class,         TrapEscapePromptPacket::new);
        s2c(TrapVictimGlowPacket.class,           TrapVictimGlowPacket::new);
        s2c(TrapEscapeAttemptPacket.class,        TrapEscapeAttemptPacket::new);
        s2c(WireShockPacket.class,                WireShockPacket::new);
        s2c(GeneratorInteractionPacket.class,     GeneratorInteractionPacket::new);
        s2c(ItemPickupPacket.class,               ItemPickupPacket::new);
        s2c(ManiacHitPacket.class,                ManiacHitPacket::new);
        c2s(RopeSwingPacket.class,                RopeSwingPacket::new);
        s2c(RopeMinigameOpenPacket.class,         RopeMinigameOpenPacket::new);
        // Кіно-вступ (PROTOCOL 14): старт з параметрами подачі й сигнал розтемнення.
        s2c(com.log_to_kot.maniacmod.net.s2c.intro.IntroStartPacket.class,
            com.log_to_kot.maniacmod.net.s2c.intro.IntroStartPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.intro.IntroRevealPacket.class,
            com.log_to_kot.maniacmod.net.s2c.intro.IntroRevealPacket::new);
        // PROTOCOL 16: ціль матчу для tab-екрана («N з M генераторів», віхи).
        s2c(ObjectiveSyncPacket.class,            ObjectiveSyncPacket::new);
        c2s(GroundItemPickupPacket.class,         GroundItemPickupPacket::new);
        // PROTOCOL 17: оцінки маньяків (екран оцінок у кастомному ESC-меню).
        s2c(com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket.class,
            com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.rating.ManiacRatingSubmitPacket.class,
            com.log_to_kot.maniacmod.net.c2s.rating.ManiacRatingSubmitPacket::new);
        // PROTOCOL 18: адмін-екран відгуків (хто що поставив + коментар), команда /maniac reviews.
        s2c(com.log_to_kot.maniacmod.net.s2c.rating.AdminReviewsPacket.class,
            com.log_to_kot.maniacmod.net.s2c.rating.AdminReviewsPacket::new);
        // PROTOCOL 19: димова міна — приглушення звуків для того, хто в хмарі.
        s2c(SmokeMufflePacket.class,              SmokeMufflePacket::new);
        // PROTOCOL 20: бризки крові від удару маньяка (плями на підлозі й стінах).
        s2c(com.log_to_kot.maniacmod.net.s2c.notify.BloodSplatterPacket.class,
            com.log_to_kot.maniacmod.net.s2c.notify.BloodSplatterPacket::new);
        // PROTOCOL 21: шипи — екранний ефект «потрапив на шипи» для жертви.
        s2c(com.log_to_kot.maniacmod.net.s2c.notify.SpikeHitPacket.class,
            com.log_to_kot.maniacmod.net.s2c.notify.SpikeHitPacket::new);
        // ── Евакуація (v22) — лише в кінець блоку, інакше ломиться сумісність клієнт/сервер.
        s2c(com.log_to_kot.maniacmod.net.s2c.evac.ReceiverMenuOpenPacket.class,
            com.log_to_kot.maniacmod.net.s2c.evac.ReceiverMenuOpenPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket.class,
            com.log_to_kot.maniacmod.net.s2c.evac.ClearingSyncPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.intent.ReceiverCallPacket.class,
            com.log_to_kot.maniacmod.net.c2s.intent.ReceiverCallPacket::new);
        // Підсумок матчу (хто переміг) — теж лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchOutcomePacket.class,
            com.log_to_kot.maniacmod.net.s2c.matchstate.MatchOutcomePacket::new);
        // Екранний HUD евакуації і банер «втік 3/6» — теж лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket.class,
            com.log_to_kot.maniacmod.net.s2c.evac.EvacStatusPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.evac.EscapeAnnouncePacket.class,
            com.log_to_kot.maniacmod.net.s2c.evac.EscapeAnnouncePacket::new);
        // PROTOCOL 23: підсвітка вільних гаків для маньяка, що несе жертву — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.hooks.HookHighlightPacket.class,
            com.log_to_kot.maniacmod.net.s2c.hooks.HookHighlightPacket::new);
        // PROTOCOL 24: голос диспетчера після виклику вертольота — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.evac.DispatcherPacket.class,
            com.log_to_kot.maniacmod.net.s2c.evac.DispatcherPacket::new);
        // PROTOCOL 25: сцена перемоги маніяка і статистика матчу — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket.class,
            com.log_to_kot.maniacmod.net.s2c.matchstate.MatchSummaryPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.victory.VictoryStartPacket.class,
            com.log_to_kot.maniacmod.net.s2c.victory.VictoryStartPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.victory.VictoryEndPacket.class,
            com.log_to_kot.maniacmod.net.s2c.victory.VictoryEndPacket::new);
        // PROTOCOL 26: сцена перемоги гравців (VictoryStartPacket отримав прапор «хто переміг»),
        // нагороди за матч (XP) для нового екрана підсумків — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket.class,
            com.log_to_kot.maniacmod.net.s2c.matchstate.MatchRewardPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.intent.StatsViewClosePacket.class,
            com.log_to_kot.maniacmod.net.c2s.intent.StatsViewClosePacket::new);
        // PROTOCOL 27: режим будування барикад (буфер дощок і прев'ю) — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeBuildStatePacket.class,
            com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeBuildStatePacket::new);
        // PROTOCOL 29: міні-гра «витягни цвяхи» (BarricadePullPacket прибрано) — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameOpenPacket.class,
            com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameOpenPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeNailPacket.class,
            com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeNailPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameClosePacket.class,
            com.log_to_kot.maniacmod.net.s2c.barricade.BarricadeGameClosePacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.hold.BarricadeNailHoldPacket.class,
            com.log_to_kot.maniacmod.net.c2s.hold.BarricadeNailHoldPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.intent.BarricadeGameExitPacket.class,
            com.log_to_kot.maniacmod.net.c2s.intent.BarricadeGameExitPacket::new);
        // PROTOCOL 30: екранний ефект влучання для маньяка — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.notify.ManiacHitConfirmPacket.class,
            com.log_to_kot.maniacmod.net.s2c.notify.ManiacHitConfirmPacket::new);
        // PROTOCOL 31: профіль гравця (довготривала статистика, ранги) — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.profile.ProfilePacket.class,
            com.log_to_kot.maniacmod.net.s2c.profile.ProfilePacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.profile.ProfileRequestPacket.class,
            com.log_to_kot.maniacmod.net.c2s.profile.ProfileRequestPacket::new);
        // PROTOCOL 32: історія матчів (адмінський список, особиста історія, деталі матчу) — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.history.HistoryListPacket.class,
            com.log_to_kot.maniacmod.net.s2c.history.HistoryListPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.history.HistoryDetailPacket.class,
            com.log_to_kot.maniacmod.net.s2c.history.HistoryDetailPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.history.HistoryListRequestPacket.class,
            com.log_to_kot.maniacmod.net.c2s.history.HistoryListRequestPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.history.HistoryDetailRequestPacket.class,
            com.log_to_kot.maniacmod.net.c2s.history.HistoryDetailRequestPacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.history.OpenHistoryPacket.class,
            com.log_to_kot.maniacmod.net.s2c.history.OpenHistoryPacket::new);
        // PROTOCOL 33: лідерборди (топи гравців, 3D-бюсти) — лише в кінець блоку.
        s2c(com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardPagePacket.class,
            com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardPagePacket::new);
        s2c(com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket.class,
            com.log_to_kot.maniacmod.net.s2c.leaderboard.LeaderboardOverviewPacket::new);
        c2s(com.log_to_kot.maniacmod.net.c2s.leaderboard.LeaderboardRequestPacket.class,
            com.log_to_kot.maniacmod.net.c2s.leaderboard.LeaderboardRequestPacket::new);
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

    /**
     * Усім гравцям, що зараз у ЦЬОМУ рівні. Потрібно для подій,
     * релевантних лише тим, хто в світі (напр. підсвітка жертви пастки:
     * гравці в лобі/іншому вимірі сутності не бачать).
     */
    public static void toLevel(net.minecraft.server.level.ServerLevel level, ModPacket packet) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), packet);
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

package com.log_to_kot.maniacmod.network;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.entity.ManiacEntity;
import com.log_to_kot.maniacmod.entity.ManiacType;
import com.log_to_kot.maniacmod.entity.ManiacType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {

    private static final String PROTOCOL_VERSION = "6";
    public static SimpleChannel INSTANCE;
    private static int id = 0;

    public static void register() {
        INSTANCE = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ManiacMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
        );

        // 0 — sync visual scale (for renderer sizing)
        INSTANCE.messageBuilder(ManiacScalePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(ManiacScalePacket::decode)
            .encoder(ManiacScalePacket::encode)
            .consumerMainThread(ManiacScalePacket::handle)
            .add();

        // 1 — sync ManiacType (for hitbox + camera + FOV Mixins)
        INSTANCE.messageBuilder(ManiacTypePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(ManiacTypePacket::decode)
            .encoder(ManiacTypePacket::encode)
            .consumerMainThread(ManiacTypePacket::handle)
            .add();

        // 2 — sync video playback to clients
        INSTANCE.messageBuilder(VideoSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(VideoSyncPacket::decode)
            .encoder(VideoSyncPacket::encode)
            .consumerMainThread(VideoSyncPacket::handle)
            .add();

        // 3 — server → maniac player: open character selection screen
        INSTANCE.messageBuilder(OpenManiacSelectPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(OpenManiacSelectPacket::decode)
            .encoder(OpenManiacSelectPacket::encode)
            .consumerMainThread(OpenManiacSelectPacket::handle)
            .add();

        // 4 — client → server: maniac player selected their character
        INSTANCE.messageBuilder(ManiacSelectResponsePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .decoder(ManiacSelectResponsePacket::decode)
            .encoder(ManiacSelectResponsePacket::encode)
            .consumerMainThread(ManiacSelectResponsePacket::handle)
            .add();

        // 5 — server → all clients: show/hide "maniac is choosing" waiting overlay
        INSTANCE.messageBuilder(SelectWaitingPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(SelectWaitingPacket::decode)
            .encoder(SelectWaitingPacket::encode)
            .consumerMainThread(SelectWaitingPacket::handle)
            .add();

        // 6 — server → maniac client: ability cooldown fraction for HUD
        INSTANCE.messageBuilder(AbilityCooldownPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(AbilityCooldownPacket::decode)
            .encoder(AbilityCooldownPacket::encode)
            .consumerMainThread(AbilityCooldownPacket::handle)
            .add();

        // 7 — server → survivor client: generator repair progress overlay
        INSTANCE.messageBuilder(GeneratorProgressPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .decoder(GeneratorProgressPacket::decode)
            .encoder(GeneratorProgressPacket::encode)
            .consumerMainThread(GeneratorProgressPacket::handle)
            .add();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Send ManiacType to ONLY the maniac player (camera/hitbox are personal). */
    public static void sendTypeToPlayer(ServerPlayer player, ManiacType type) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
            new ManiacTypePacket(type));
    }

    /** Broadcast visual scale to ALL players so everyone sees the right model size. */
    public static void broadcastScale(ManiacEntity.ManiacScale scale) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), new ManiacScalePacket(scale));
    }

    /** Clear maniac state when game ends. */
    /** Tell all players to play a video file. */
    public static void sendVideoToAll(String fileName, int fps) {
        INSTANCE.send(PacketDistributor.ALL.noArg(), new VideoSyncPacket(fileName, fps));
    }

    /** Tell all players to stop video. */
    public static void stopVideoForAll() {
        INSTANCE.send(PacketDistributor.ALL.noArg(), new VideoSyncPacket());
    }

    /** Send ability cooldown fraction (0.0–1.0) to maniac's client */
    public static void sendAbilityCooldown(net.minecraft.server.level.ServerPlayer maniac, float fraction) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> maniac),
            new AbilityCooldownPacket(fraction));
    }

    /** Show waiting overlay to all players EXCEPT the maniac */
    public static void showSelectWaiting(net.minecraft.server.level.ServerPlayer maniac, int seconds) {
        for (var p : maniac.getServer().getPlayerList().getPlayers()) {
            if (p.getUUID().equals(maniac.getUUID())) continue;
            INSTANCE.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                new SelectWaitingPacket(true, seconds));
        }
    }

    /** Update timer on all waiting clients */
    public static void updateSelectWaiting(net.minecraft.server.level.ServerPlayer maniac, int seconds) {
        for (var p : maniac.getServer().getPlayerList().getPlayers()) {
            if (p.getUUID().equals(maniac.getUUID())) continue;
            INSTANCE.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                new SelectWaitingPacket(true, seconds));
        }
    }

    /** Hide waiting overlay for all players */
    public static void hideSelectWaiting(net.minecraft.server.level.ServerPlayer maniac) {
        for (var p : maniac.getServer().getPlayerList().getPlayers()) {
            INSTANCE.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                new SelectWaitingPacket(false, 0));
        }
    }

    /** Send open-screen packet to the maniac player */
    public static void sendOpenSelectToPlayer(ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
            new OpenManiacSelectPacket());
    }

    /** Client sends chosen ManiacType to server */
    public static void sendManiacSelectResponse(ManiacType chosen) {
        INSTANCE.sendToServer(new ManiacSelectResponsePacket(chosen));
    }

    public static void clearTypeForPlayer(ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
            new ManiacTypePacket(null));
    }
}

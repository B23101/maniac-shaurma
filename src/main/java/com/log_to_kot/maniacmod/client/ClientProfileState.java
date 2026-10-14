package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.c2s.profile.ProfileRequestPacket;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Останній профіль, який сервер прислав цьому клієнту ({@code ProfilePacket}). Екран профілю й
 * підказка лобі лише читають звідси; оновлення — запитом {@link #request()}.
 */
public final class ClientProfileState {

    private static PlayerProfile profile;
    private static long receivedAtMs;
    private static long requestedAtMs;

    /** Чужі профілі (ПКМ по гравцю в лобі): uuid → останній присланий знімок. */
    public record Other(String name, PlayerProfile profile, long receivedAtMs) {}

    private static final Map<UUID, Other> OTHERS = new HashMap<>();

    private ClientProfileState() {}

    /** Вхід відповіді сервера: власний профіль або чужий, за {@code ownerId}. */
    public static void receive(UUID ownerId, String ownerName, PlayerProfile p) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(ownerId)) {
            set(p);
        } else {
            OTHERS.put(ownerId, new Other(ownerName, p, System.currentTimeMillis()));
        }
    }

    /** {@code null}, поки відповідь по цьому гравцю не прийшла. */
    public static Other other(UUID id) { return OTHERS.get(id); }

    public static void requestOther(UUID id) {
        if (Minecraft.getInstance().getConnection() == null) return;
        ModNetwork.toServer(new ProfileRequestPacket(id));
    }

    public static void set(PlayerProfile p) {
        profile = p;
        receivedAtMs = System.currentTimeMillis();
    }

    /** {@code null}, поки відповідь сервера не прийшла. */
    public static PlayerProfile get() { return profile; }

    public static long receivedAt() { return receivedAtMs; }

    public static void request() {
        if (Minecraft.getInstance().getConnection() == null) return;
        requestedAtMs = System.currentTimeMillis();
        ModNetwork.toServer(new ProfileRequestPacket());
    }

    /** Запитує, лише якщо даних нема або вони старші за {@code maxAgeMs} (і запит не летів щойно). */
    public static void requestIfStale(long maxAgeMs) {
        long now = System.currentTimeMillis();
        if (now - requestedAtMs < 3000L) return;
        if (profile == null || now - receivedAtMs > maxAgeMs) request();
    }

    public static void clear() {
        profile = null;
        receivedAtMs = 0L;
        requestedAtMs = 0L;
        OTHERS.clear();
    }
}

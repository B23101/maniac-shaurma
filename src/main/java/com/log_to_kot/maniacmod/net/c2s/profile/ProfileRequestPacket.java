package com.log_to_kot.maniacmod.net.c2s.profile;

import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.net.s2c.profile.ProfilePacket;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfileStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клієнт → сервер: «пришли профіль». Без {@code target} — власний (відкриття екрана профілю й
 * підказка лобі); з {@code target} — профіль іншого гравця, що зараз онлайн (ПКМ по ньому в лобі).
 * Відповідь — {@link ProfilePacket}. Захист від спаму: не частіше раз на {@value #COOLDOWN_MS} мс
 * для власних запитів і раз на {@value #OTHER_COOLDOWN_MS} мс для чужих.
 *
 * <p>Чужий профіль віддається лише в лобі й лише для гравця онлайн: під час матчу роль і стан
 * інших учасників приховані, профіль не має ставати каналом розвідки.</p>
 */
public record ProfileRequestPacket(UUID target) implements ModPacket {

    private static final long COOLDOWN_MS = 400L;
    private static final long OTHER_COOLDOWN_MS = 250L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_OTHER = new ConcurrentHashMap<>();

    /** Власний профіль. */
    public ProfileRequestPacket() {
        this((UUID) null);
    }

    public ProfileRequestPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readUUID() : null);
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(target != null);
        if (target != null) buf.writeUUID(target);
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPlayer player = ModNetwork.sender(ctx);
        long now = System.currentTimeMillis();
        UUID me = player.getUUID();
        // Мапи лише для антиспаму: не даємо їм рости безкінечно за довгу сесію сервера.
        if (LAST.size() > 512) LAST.clear();
        if (LAST_OTHER.size() > 512) LAST_OTHER.clear();

        if (target == null || target.equals(me)) {
            Long last = LAST.put(me, now);
            if (last != null && now - last < COOLDOWN_MS) return;
            ModNetwork.toPlayer(player, new ProfilePacket(me, player.getGameProfile().getName(),
                PlayerProfileStorage.load(me)));
            return;
        }

        Long last = LAST_OTHER.put(me, now);
        if (last != null && now - last < OTHER_COOLDOWN_MS) return;
        var match = com.log_to_kot.maniacmod.ManiacMod.match();
        if (match == null || match.phases().current() != GamePhase.LOBBY) return;
        ServerPlayer other = player.server.getPlayerList().getPlayer(target);
        if (other == null) return;
        ModNetwork.toPlayer(player, new ProfilePacket(other.getUUID(), other.getGameProfile().getName(),
            PlayerProfileStorage.load(other.getUUID())));
    }
}

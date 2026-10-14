package com.log_to_kot.maniacmod.net.s2c.profile;

import com.log_to_kot.maniacmod.net.S2CPacket;
import com.log_to_kot.maniacmod.progression.profile.PlayerProfile;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Сервер → гравець: довготривалий профіль (відповідь на {@code ProfileRequestPacket}).
 * Це може бути профіль самого одержувача або чужий (ПКМ по гравцю в лобі) — розрізняє
 * {@code ownerId}: клієнт кладе власний профіль у {@code ClientProfileState.set}, чужий — окремо.
 */
public record ProfilePacket(UUID ownerId, String ownerName, PlayerProfile profile) implements S2CPacket {

    public ProfilePacket(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readUtf(64), PlayerProfile.read(buf));
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(ownerId);
        buf.writeUtf(ownerName == null ? "" : ownerName, 64);
        profile.write(buf);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientProfileState.receive(ownerId, ownerName, profile);
    }
}

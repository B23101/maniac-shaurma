package com.log_to_kot.maniacmod.net.s2c.identity;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: роль локального гравця в цьому матчі.
 *
 * ── Категорія: identity ──────────────────────────────────────────────
 * "Хто я" — дані, що змінюються РІДКО (раз на матч, на ROLE_REVEAL, і
 * повторно при реконекті). НЕ плутати з {@code vitals} (хп/стаміна —
 * змінюється щотік) чи майбутнім {@code roster} (хто ВСІ інші гравці
 * — те саме питання, але для чужих даних, не своїх). Один тип запиту
 * ("роль") — один пакет, без дублювання в іншій категорії.
 *
 * Надсилається на фазі ROLE_REVEAL і повторно при вході гравця в гру
 * (реконект), щоб клієнт не лишився з порожньою роллю.
 *
 * v3-еквівалент: ManiacTypePacket, який передавав лише тип маньяка й
 * нічого не казав виживим — тому клієнт виживого взагалі не знав, що
 * він у грі.
 *
 * @param role        роль локального гравця
 * @param archetypeId id архетипу: маньяка ("chucky") або ролі виживого
 *                    ("default"); порожній рядок для глядача
 */
public record RoleSyncPacket(Role role, String archetypeId) implements S2CPacket {

    public enum Role { MANIAC, SURVIVOR, SPECTATOR }

    public RoleSyncPacket(FriendlyByteBuf buf) {
        this(buf.readEnum(Role.class), buf.readUtf());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(role);
        buf.writeUtf(archetypeId);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onRole(role, archetypeId);
    }
}

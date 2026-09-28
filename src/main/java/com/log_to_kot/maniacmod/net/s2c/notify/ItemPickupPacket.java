package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Сервер → клієнтам поруч: цей гравець ЗАРАЗ піднімає предмет із землі.
 *
 * ── Навіщо пакет ─────────────────────────────────────────────────────
 * Підбір — серверна подія ({@code GroundItemEntity#interact}), а поза
 * руки — стан тіла, який мусить бачити КОЖЕН клієнт, що гравця бачить
 * (той самий принцип, що {@link GeneratorInteractionPacket}). Без
 * пакета анімацію бачив би лише той, хто її ініціював.
 *
 * ── Чому подія, а не стан ────────────────────────────────────────────
 * На відміну від генератора, підбір — ОДНОРАЗОВИЙ жест фіксованої
 * тривалості (18 тіків, див. {@code ItemPickupPose#DURATION_TICKS}).
 * Тому пакет несе лише «хто почав»: клієнт сам відлічує тривалість і
 * сам ховає позу. Немає ні «кінця», ні щотікового трафіку, а
 * загублений пакет означає лише те, що в когось одного не зіграє один
 * жест — нічого не зависає.
 *
 * @param playerId хто піднімає предмет
 */
public record ItemPickupPacket(UUID playerId) implements S2CPacket {

    public ItemPickupPacket(FriendlyByteBuf buf) {
        this(buf.readUUID());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ItemPickupPose.start(playerId);
    }
}

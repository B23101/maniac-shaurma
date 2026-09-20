package com.log_to_kot.maniacmod.net.s2c.loot;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: чи малювати блиск (END_ROD) навколо предметів на
 * землі.
 *
 * ── Навіщо окремий пакет, а не читання конфігу на клієнті ───────────
 * {@code ManiacConfigs} читає {@code maniac.yml}, який існує лише на
 * сервері (виділений сервер + інтегрований сервер у сингл-плеєрі); у
 * клієнта з мультиплеєра цього файлу взагалі немає. Блиск малює
 * {@code GroundItemEntity.tickClient()} — клієнтський метод, тому
 * значення {@link com.log_to_kot.maniacmod.config.ConfigSchema#GROUND_ITEM_SPARKLE_ENABLED}
 * треба донести пакетом, так само як {@code PhaseSyncPacket} доносить
 * фазу. Надсилається при вході гравця (разом з {@code PhaseSyncPacket})
 * і на {@code /maniac reload}.
 */
public record GroundItemVisualSettingsPacket(boolean sparkleEnabled) implements S2CPacket {

    public GroundItemVisualSettingsPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(sparkleEnabled);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGroundItemVisualSettings(sparkleEnabled);
    }
}

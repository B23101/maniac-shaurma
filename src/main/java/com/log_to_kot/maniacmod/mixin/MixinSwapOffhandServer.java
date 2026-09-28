package com.log_to_kot.maniacmod.mixin;

import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Серверний блок обміну предметів між руками — справжній захист.
 *
 * Клієнтський {@link MixinSwapOffhandClient} лише не дає F надіслати
 * пакет. Модифікований клієнт може надіслати
 * {@code ServerboundPlayerActionPacket(SWAP_ITEM_WITH_OFFHAND)}
 * напряму, тож сервер сам відкидає цю дію: предмети не рухаються, слота
 * лівої руки в геймплеї немає.
 *
 * Скасовуємо ТІЛЬКИ {@code SWAP_ITEM_WITH_OFFHAND}; решта дій пакета
 * (копання, викидання предмета, скидання використання) проходять як
 * завжди.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MixinSwapOffhandServer {

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void maniacmod$blockSwapOffhand(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            ci.cancel();
        }
    }
}

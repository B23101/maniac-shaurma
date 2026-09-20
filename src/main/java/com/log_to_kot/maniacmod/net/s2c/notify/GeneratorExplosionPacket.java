package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: генератор ВИБУХНУВ (провалена міні-гра ремонту).
 * Надсилається УСІМ гравцям матчу — виживим, маньяку й глядачам.
 *
 * ── Навіщо окремий пакет, якщо є вимкнена підсвітка (клавіша 5) ────────
 * Підсвітка за клавішею — це запит гравця й ще не реалізована; вибух —
 * подія, яку мають бачити ВСІ й ДАЛЕКО (1000+ блоків). Самої сутності
 * генератора на клієнті для цього замало: сервер не відстежує сутності
 * далі за радіус огляду, а чанки далі за нього взагалі не завантажені.
 * Тому позиція приходить окремо, а клієнт малює червоний маркер
 * проєкцією позиції на екран — це не залежить від дальності
 * рендеру й завантаження чанків. Червоний контур самої сутності в
 * зоні прогрузки додає сервер окремо ({@code GeneratorEntity}).
 *
 * @param pos           де стоїть генератор (той самий ключ, що
 *                      {@code MatchOrchestrator.generatorAt})
 * @param durationTicks скільки тіків показувати маркер (за конфігом
 *                      {@code failFlashTicks}, за замовчуванням 100 = 5 с)
 */
public record GeneratorExplosionPacket(BlockPos pos, int durationTicks) implements S2CPacket {

    public GeneratorExplosionPacket(FriendlyByteBuf buf) {
        this(buf.readBlockPos(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(durationTicks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGeneratorExplosion(pos, durationTicks);
    }
}

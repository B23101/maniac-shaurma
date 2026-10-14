package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → УСІМ гравцям рівня: з жертви удару маньяка бризнула кров.
 *
 * ── Що в пакеті, а що рахує клієнт ───────────────────────────────────
 * Сервер не знає, куди саме впаде кожна крапля, і не повинен: плями — суто
 * косметика, яку не варто возити по мережі. Тому пакет несе лише «затравку»:
 * звідки бризнуло, у який бік і скільки крапель. Траєкторії й місця плям
 * клієнт обчислює сам ({@code client.BloodSplatterFx}) із {@code seed} —
 * тож усі гравці бачать ОДНАКОВІ бризки без жодних додаткових пакетів.
 *
 * @param x,y,z     точка, з якої бризкає (груди жертви)
 * @param awayX,awayZ одиничний горизонтальний напрямок «від маньяка» — туди
 *                  летить основна маса крапель (частина летить назад)
 * @param downed    true, якщо удар збив із ніг: крапель більше + калюжа під жертвою
 * @param droplets  скільки крапель вилітає (з конфіга {@code bloodDroplets})
 * @param seed      затравка генератора — однакова для всіх клієнтів
 */
public record BloodSplatterPacket(double x, double y, double z, float awayX, float awayZ,
                                  boolean downed, int droplets, long seed) implements S2CPacket {

    public BloodSplatterPacket(FriendlyByteBuf buf) {
        this(buf.readDouble(), buf.readDouble(), buf.readDouble(),
             buf.readFloat(), buf.readFloat(),
             buf.readBoolean(), buf.readVarInt(), buf.readLong());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(awayX);
        buf.writeFloat(awayZ);
        buf.writeBoolean(downed);
        buf.writeVarInt(droplets);
        buf.writeLong(seed);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.BloodSplatterFx.spawn(x, y, z, awayX, awayZ, downed, droplets, seed);
    }
}

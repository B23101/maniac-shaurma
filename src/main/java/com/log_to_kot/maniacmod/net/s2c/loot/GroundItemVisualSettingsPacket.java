package com.log_to_kot.maniacmod.net.s2c.loot;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Сервер → клієнт: налаштування предметів на землі, потрібні клієнту
 * для чесного HUD — блиск і дистанція підбору.
 *
 * ── Навіщо окремий пакет, а не читання конфігу на клієнті ───────────
 * {@code ManiacConfigs} читає {@code maniac.yml}, який існує лише на
 * сервері (виділений сервер + інтегрований сервер у сингл-плеєрі); у
 * клієнта з мультиплеєра цього файлу взагалі немає. Блиск малює
 * {@code GroundItemEntity.tickClient()}, а дистанцію підбору читає
 * {@code GroundItemHintOverlay} — обом клієнтським методам значення
 * треба донести пакетом, так само як {@code PhaseSyncPacket} доносить
 * фазу. Надсилається при вході гравця (разом з {@code PhaseSyncPacket})
 * і на {@code /maniac reload}.
 *
 * ── БАГФІКС: чому {@code pickupRangeBlocks} тут з'явився ─────────────
 * Раніше клієнтська підказка «ПКМ — взяти» показувалась на БУДЬ-ЯКІЙ
 * дистанції, де ванільний рейкаст ловить сутність (типово ~3 блоки,
 * атрибут {@code ENTITY_INTERACTION_RANGE}), тоді як сервер у
 * {@code GroundItemEntity.interact()} відмовляв за СВОЇМ окремим
 * лімітом {@code ConfigSchema.GROUND_ITEM_PICKUP_RANGE} (2 блоки за
 * замовчуванням). У проміжку між цими двома дистанціями гравець бачив
 * назву предмета й підказку, тиснув ПКМ — і сервер мовчки відмовляв
 * (`InteractionResult.PASS`, без жодного повідомлення), бо стояв
 * задалеко за СВОЄЮ міркою, хоч і в межах видимості прицілу. Виглядало
 * як «навівся, а забрати не можна, треба шукати окрему точку ближче».
 * Тепер клієнт знає РЕАЛЬНИЙ серверний ліміт і сам вважає предмет
 * недосяжним раніше, ніж це зробить сервер — підказка ніколи не обіцяє
 * того, що сервер за мить відхилить.
 */
public record GroundItemVisualSettingsPacket(boolean sparkleEnabled, double pickupRangeBlocks)
        implements S2CPacket {

    public GroundItemVisualSettingsPacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readDouble());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(sparkleEnabled);
        buf.writeDouble(pickupRangeBlocks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onGroundItemVisualSettings(
            sparkleEnabled, pickupRangeBlocks);
    }
}

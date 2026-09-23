package com.log_to_kot.maniacmod.net.s2c.notify;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Сервер → клієнт: маньяк ОГЛУШЕНИЙ ударом лома (або оглушення
 * скінчилось). Надсилається УСІМ гравцям матчу — виживим, маньяку й
 * глядачам, той самий підхід, що {@link GeneratorExplosionPacket}
 * ("подія, яку мають бачити ВСІ").
 *
 * ── Один пакет на два різних глядачі ──────────────────────────────────
 * На відміну від {@code AbilityCooldownPacket} (летить ЛИШЕ власнику),
 * тут одна подія обслуговує одразу двох різних глядачів з різною
 * реакцією:
 *   • САМ маньяк — блокує собі рух/стрибок/поворот камери й малює 5
 *     зірочок на власному екрані (клієнт звіряє
 *     {@code maniacId.equals(self.getUUID())});
 *   • УСІ ІНШІ — малюють 5 зірочок, що обертаються, НАД ГОЛОВОЮ
 *     маньяка в світі (world-space billboard), щоб було видно здаля,
 *     хто зараз оглушений і на скільки ще.
 * Розділяти на два пакети (особистий + широкомовний) означало б два
 * джерела правди про той самий момент часу — реальний ризик
 * розсинхрону через порядок доставки. Один пакет, дві реакції клієнта
 * на нього — простіше й безпечніше.
 *
 * ── Чому не через RosterSyncPacket ────────────────────────────────────
 * RosterSyncPacket — проекція для tab-екрана (Tab, раз на подію,
 * низька частота). World-billboard зірочок потребує однакового
 * знання про стан і в ту мить, коли гравець НЕ тримає Tab, а сам
 * ростер API не призначений для частих ігрових подій цього роду
 * (див. докстрінг {@code RosterSyncPacket}: «свідомий виняток» уже є
 * для hp, множити такі винятки — заплутувати призначення пакета).
 *
 * @param maniacId    хто оглушений
 * @param stunned     true — оглушення почалось; false — скінчилось
 *                    (сервер шле явний "кінець" замість того, щоб
 *                    клієнт сам відраховував і здогадувався)
 * @param totalTicks  на скільки тіків розраховане оглушення (для
 *                    анімації обертання зірочок і власного відліку
 *                    клієнта); 0, коли {@code stunned == false}
 */
public record ManiacStunPacket(UUID maniacId, boolean stunned, int totalTicks) implements S2CPacket {

    public ManiacStunPacket(FriendlyByteBuf buf) {
        this(buf.readUUID(), buf.readBoolean(), buf.readVarInt());
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(maniacId);
        buf.writeBoolean(stunned);
        buf.writeVarInt(totalTicks);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler
            .onManiacStun(maniacId, stunned, totalTicks);
    }
}

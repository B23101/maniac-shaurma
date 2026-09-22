package com.log_to_kot.maniacmod.net.s2c.traps;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт: «ось увесь набір пасток твого архетипу, обери до
 * {@code limit}» — сигнал відкрити екран вибору пасток.
 *
 * ── Чим відрізняється від {@link TrapLoadoutPacket} ──────────────────
 * {@code TrapLoadoutPacket} — це «ось що в тебе ЗАРАЗ на клавішах
 * 5/6/7» (уже звужений підсумок, малює панель зліва). Цей пакет — «ось
 * УВЕСЬ каталог архетипу, з якого можна вибирати» (малює екран вибору).
 * Два різні питання: одне про поточний стан хотбару, інше про меню.
 * Плутати їх в один запис означало б, що екран вибору бачив би вже
 * звужений список і не міг запропонувати те, що гравець не обрав.
 *
 * ── Коли шлеться ─────────────────────────────────────────────────────
 * Один раз при вході в {@code ROLE_REVEAL}, лише маньяку, і лише коли
 * є з чого обирати (розмір каталогу більший за ліміт) — див.
 * {@code TrapModule#syncCatalogIfChoiceNeeded}. Якщо каталог менший
 * або дорівнює ліміту, вибирати нема що: сервер бере всі пастки сам
 * ({@code chooseAll}) і цей пакет не шлеться — екран не відкриється.
 *
 * ── Що робить клієнт при отриманні ────────────────────────────────────
 * Відкриває {@code TrapChooseScreen} (або оновлює вже відкритий, якщо
 * прийшов повторно — реконект). Підтвердження вибору йде окремим
 * пакетом {@code TrapChoosePacket}, а після нього сервер відповідає
 * звичайним {@link TrapLoadoutPacket} із застосованим результатом.
 *
 * @param trapIds ВСІ id пасток архетипу, у природному порядку архетипу
 *                (не порядок клавіш — клавіші з'являються лише після вибору)
 * @param limit   скільки з них можна обрати (= {@code trapsPerMatch}, максимум 3)
 */
public record TrapCatalogPacket(List<String> trapIds, int limit) implements S2CPacket {

    public TrapCatalogPacket(FriendlyByteBuf buf) {
        this(readIds(buf), buf.readVarInt());
    }

    private static List<String> readIds(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ids.add(buf.readUtf());
        return ids;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(trapIds.size());
        for (String id : trapIds) buf.writeUtf(id);
        buf.writeVarInt(limit);
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onTrapCatalog(trapIds, limit);
    }
}

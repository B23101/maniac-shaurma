package com.log_to_kot.maniacmod.net.c2s.settings;

import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.ModPacket;
import com.log_to_kot.maniacmod.server.ServerPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * Клієнт → сервер: гравець хоче відкрити {@code SettingsMenuScreen}
 * (натиснув кнопку "⚙ Налаштування гри" у вікні чату).
 *
 * ── Навіщо окремий пакет, а не просто відкрити екран локально ─────────
 * Клієнт НЕ може сам зібрати {@code SettingsMenuScreen} — йому
 * потрібні поточні значення з диска сервера ({@link
 * com.log_to_kot.maniacmod.config.SettingsSnapshot#collect()}), а не
 * ті, що клієнт міг запам'ятати з минулого відкриття (могли змінитись
 * іншим оператором чи прямим редагуванням yml). Тому кнопка лише
 * СИГНАЛІЗУЄ намір — так само, як команда {@code /maniac settings}
 * робить {@link com.log_to_kot.maniacmod.server.ManiacCommand},
 * і обидва шляхи сходяться в один метод
 * {@link ServerPacketHandler#onOpenSettingsMenuRequest}, щоб перевірка
 * прав жила в одному місці, а не дублювалась.
 *
 * ── Права ────────────────────────────────────────────────────────────
 * Кнопка в чаті показується лише операторам (клієнтська перевірка у
 * {@code ClientSetup}, суто косметична — щоб звичайний гравець її
 * навіть не бачив), але СПРАВЖНЯ перевірка {@code hasPermissions(2)} —
 * тут, на сервері, у {@link ServerPacketHandler}. Той самий принцип,
 * що вже діє для {@link SettingsChangePacket}: сховану кнопку можна
 * підробити модифікованим клієнтом, порожній екран — ні.
 */
public record OpenSettingsMenuRequestPacket() implements ModPacket {

    public OpenSettingsMenuRequestPacket(FriendlyByteBuf buf) {
        this();
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        // Порожньо — сам факт пакета вже є повідомленням.
    }

    @Override
    public void handle(NetworkEvent.Context ctx) {
        ServerPacketHandler.onOpenSettingsMenuRequest(ModNetwork.sender(ctx));
    }
}

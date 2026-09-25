package com.log_to_kot.maniacmod.client.maniac;

import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.s2c.identity.RoleSyncPacket;
import com.log_to_kot.maniacmod.net.s2c.matchstate.RosterSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

/**
 * Хто з гравців — який маньяк, на КЛІЄНТІ.
 *
 * ── Чому з ростеру, а не з {@code RoleSyncPacket} ────────────────────
 * {@code RoleSyncPacket} містить роль ЛИШЕ локального гравця — чужий
 * клієнт з нього не дізнається ні того, хто маньяк, ні який саме.
 * А {@code RosterSyncPacket} розсилається ВСІМ і несе для кожного
 * гравця {@code role} і {@code archetypeId} — це вже готове джерело
 * правди для рендера, тож окремий пакет «хто маньяк» не потрібен.
 *
 * ── Чому архетип, а не просто id ─────────────────────────────────────
 * Архетип — це ЗВИЧАЙНИЙ common-клас ({@code ManiacRegistry}), доступний
 * і на клієнті, тож шляхи до асе́тів, модель і анімації беруться з
 * ТОГО САМОГО об'єкта, що й на сервері.
 *
 * ── Що НЕ береться з локального архетипу ─────────────────────────────
 * Габарити й висота очей. Вони налаштовуються в файлі
 * {@code maniac_stats/<id>.yml}, а це файл КОЖНОЇ сторони окремо:
 * клієнт створить собі свій з дефолтів і на виділеному сервері числа
 * розійдуться. Тому тіло приходить у ростері ({@link #bodyOf}), а
 * локальний конфіг лишається фолбеком на час до першого ростеру.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientManiacs {

    private ClientManiacs() {}

    /**
     * Архетип маньяка для цього гравця, або {@code null}, якщо гравець не
     * маньяк / архетипу немає в реєстрі (наприклад старіший клієнт).
     */
    public static ManiacArchetype archetypeOf(UUID playerId) {
        // Локальний гравець: роль приходить напряму (RoleSyncPacket) і може
        // випередити ростер — тому перевіряємо його окремо, щоб власна
        // модель для маньяка з'являлась одразу, а не через тік.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getUUID().equals(playerId) && ClientMatchState.isManiac()) {
            ManiacArchetype own = byId(ClientMatchState.archetypeId());
            if (own != null) return own;
        }

        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            if (!entry.playerId().equals(playerId)) continue;
            if (entry.role() != RoleSyncPacket.Role.MANIAC) return null;
            return byId(entry.archetypeId());
        }
        return null;
    }

    /**
     * Габарити маньяка, застосовані СЕРВЕРОМ, або {@code null}, якщо цей
     * гравець не маньяк / ростер ще не прийшов.
     *
     * Викликач мусить мати фолбек на локальний конфіг ({@code archetype.hitboxWidth()})
     * — {@code null} тут означає «ще не знаю», а не «нульовий хітбокс».
     */
    public static RosterSyncPacket.ManiacBody bodyOf(UUID playerId) {
        for (RosterSyncPacket.RosterEntry entry : ClientMatchState.roster()) {
            if (entry.playerId().equals(playerId)) return entry.maniacBody();
        }
        return null;
    }

    private static ManiacArchetype byId(String archetypeId) {
        if (archetypeId == null || archetypeId.isEmpty()) return null;
        return ManiacRegistry.exists(archetypeId) ? ManiacRegistry.get(archetypeId) : null;
    }
}

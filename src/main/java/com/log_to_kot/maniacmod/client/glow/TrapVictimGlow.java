package com.log_to_kot.maniacmod.client.glow;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashMap;
import java.util.Map;

/**
 * Клієнтський драйвер підсвітки жертв пасток: бере готовий склад із
 * {@code TrapVictimGlowPacket} і передає його у шар
 * {@link ClientEntityGlow#LAYER_TRAP_VICTIM}.
 *
 * ── Чому без тіку й без пошуку сутностей ─────────────────────────────
 * На відміну від {@link GeneratorHighlightGlow}, якому сервер шле
 * ПОЗИЦІЇ (бо id генераторів на момент пакета він не знає), тут сервер
 * надсилає вже ГОТОВІ id сутностей-жертв — він їх знає, бо сам тримає
 * сесію жертви. Тож клієнту не треба ні щотіка шукати сутність за
 * позицією, ні мати власний таймер: пакет приходить рівно тоді, коли
 * склад змінився (хтось потрапив / звільнився), і кожен такий пакет
 * містить ПОВНИЙ актуальний набір.
 */
@OnlyIn(Dist.CLIENT)
public final class TrapVictimGlow {

    private TrapVictimGlow() {}

    /** Повністю замінює шар підсвітки жертв. Порожній масив — погасити всі. */
    public static void show(int color, int[] entityIds) {
        int rgb = color & 0x00FFFFFF;
        Map<Integer, Integer> next = new HashMap<>();
        for (int id : entityIds) next.put(id, rgb);
        ClientEntityGlow.setLayer(ClientEntityGlow.LAYER_TRAP_VICTIM, next);
    }

    /** Кінець матчу / дисконект — прибираємо шар (id сутностей не переживуть матч). */
    public static void reset() {
        ClientEntityGlow.clearLayer(ClientEntityGlow.LAYER_TRAP_VICTIM);
    }
}

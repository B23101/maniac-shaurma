package com.log_to_kot.maniacmod.client.intro;

import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Поради для loader-екрана. Скільки завгодно: ключі
 * {@code maniacmod.intro.tip.1}, {@code .2}, {@code .3} … у lang-файлах, доки
 * ключі йдуть підряд. Додати пораду = додати рядок у {@code uk_ua.json} і
 * {@code en_us.json}, код міняти не треба.
 *
 * Порядок перемішується на кожен вступ, тож різні гравці (і різні матчі)
 * бачать різні поради; всередині одного вступу поради не повторюються, поки
 * не скінчились усі.
 */
final class IntroTips {

    private static final String KEY_PREFIX = "maniacmod.intro.tip.";
    private static final int HARD_CAP = 200;

    private final List<String> order = new ArrayList<>();

    IntroTips() {
        List<String> all = new ArrayList<>();
        for (int i = 1; i <= HARD_CAP; i++) {
            String key = KEY_PREFIX + i;
            if (!I18n.exists(key)) break;
            all.add(I18n.get(key));
        }
        Collections.shuffle(all, new Random());
        order.addAll(all);
    }

    boolean isEmpty() {
        return order.isEmpty();
    }

    /** Порада за номером слота; по колу, якщо слотів більше, ніж порад. */
    String get(int slot) {
        if (order.isEmpty()) return "";
        return order.get(Math.floorMod(slot, order.size()));
    }
}

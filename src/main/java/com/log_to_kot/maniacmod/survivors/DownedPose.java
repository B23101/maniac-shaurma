package com.log_to_kot.maniacmod.survivors;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.client.ClientMatchState;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import net.minecraft.world.entity.player.Player;

/**
 * Чи гравець зараз ЛЕЖИТЬ на землі — джерело правди для ПОЗИ.
 *
 * Поза ({@code Pose.SWIMMING}, хітбокс 0.6×0.6 — «в одному блоці») ставиться
 * міксином {@code MixinPlayerDownedPose} і на СЕРВЕРІ, і на КЛІЄНТІ, бо
 * ванільний {@code Player.updatePlayerPose} виконується на обох і без цього
 * клієнт щотіка перезаписував би серверну позу «стоячою».
 *
 * ── Обидва стани повзання, а не лише непритомність ───────────────────
 * {@link SurvivorState#isCrawlOnly()} — це {@code CRAWLING || UNCONSCIOUS},
 * і саме так тут і треба. БАГФІКС: раніше умовою був виключно
 * {@code UNCONSCIOUS}, тому гравець у {@code CRAWLING} (щойно впав, ще не
 * зомлів) СТОЯВ — хітбокс лишався стовпом 0.6×1.8, хоча за дизайном він
 * уже лежить і повзе. Через це його можна було «зачепити» там, де його
 * немає, а {@code MixinKeyboardInputDownedMovement} тим часом уже
 * забороняв йому рухатись. Поза тіла тепер відповідає тому, що гравець
 * справді робить.
 *
 * ── Звідки береться відповідь ────────────────────────────────────────
 *   • сервер — {@code MatchContext} (стан гравця);
 *   • клієнт — {@link ClientMatchState#isProne}: для СЕБЕ це власний
 *     стан із vitals, для ЧУЖИХ — список лежачих
 *     ({@code DownedSurvivorsPacket}) плюс стан із ростеру
 *     ({@code CRAWLING} чужих доходить саме ростером).
 *
 * Гілка клієнта викликається лише при {@code isClientSide}, тому на
 * виділеному сервері {@code ClientMatchState} не завантажується.
 */
public final class DownedPose {

    private DownedPose() {}

    public static boolean isDowned(Player player) {
        if (player.level().isClientSide) {
            return ClientMatchState.isProne(player.getUUID());
        }
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) return false;

        // null — НОРМАЛЬНИЙ випадок, а не помилка: міксин питає про КОЖНОГО
        // гравця світу, а стан виживого існує лише в тих, кого роль
        // виживого стосується ({@code MatchOrchestrator#survivorStateOf}:
        // "null, якщо гравець не виживий цього матчу" — це і маньяк, і
        // глядач, і гравець поза матчем). Без цієї перевірки перший же
        // тік з маньяком у світі валив би гру NPE з надрах міксина
        // пози — тобто падало б у кожному матчі.
        SurvivorState state = match.survivorStateOf(player.getUUID());
        return state != null && state.isCrawlOnly();
    }
}

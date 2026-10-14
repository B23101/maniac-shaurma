package com.log_to_kot.maniacmod.client.screen.rating;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Клієнтський стан оцінок. Логіка та сама, що у {@code ClientModeRatingState}
 * снайперів. Оцінюється ГРА В ЦІЛОМУ (запис {@code ManiacRatingSyncPacket.GAME_ID}:
 * візуал / геймплей / карта) і КОЖЕН маньяк окремо (вигляд / здібності / страх).
 * Три поля {@link Rating} універсальні: що вони означають, залежить від запису.
 *
 * ── Джерело даних ────────────────────────────────────────────────────
 * Сервер при заході шле {@code ManiacRatingSyncPacket}: перелік id
 * маньяків, збережені оцінки гравця, прапорець «вже оцінював» і його
 * минулий коментар. Клієнт нічого не вигадує сам: навіть перелік
 * маньяків — з сервера (клієнт і сервер можуть мати різний набір).
 *
 * ── Коли що показується (без змін відносно снайперів) ────────────────
 *   hasEverSubmitted = false → оцінки показуються перед виходом
 *   hasEverSubmitted = true  → вихід одразу (вже оцінював раніше)
 *   rateLater = true         → вихід одразу (натиснув «пізніше» у цій
 *                              сесії; скидається при виході зі світу)
 *   кнопка «Оцінити» в ESC   → лише коли оцінював або натиснув «пізніше»
 *
 * ── Оцінка ───────────────────────────────────────────────────────────
 * 0 = не виставлена, далі 0.5 … 5.0 з кроком 0.5.
 *
 * Стан живе в пам'яті сесії; скидання при виході — у
 * {@code ManiacEscInterceptor.onLogout}.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientManiacRatingState {

    private static final ClientManiacRatingState INSTANCE = new ClientManiacRatingState();

    public static ClientManiacRatingState get() { return INSTANCE; }

    /** Кількість критеріїв оцінки. */
    public static final int CATEGORIES = 3;
    public static final int CAT_LOOK = 0;
    public static final int CAT_ABILITIES = 1;
    public static final int CAT_FEAR = 2;

    /** Оцінка одного маньяка. */
    public static final class Rating {
        public float look;
        public float abilities;
        public float fear;

        public float get(int cat) {
            return switch (cat) {
                case CAT_LOOK -> look;
                case CAT_ABILITIES -> abilities;
                default -> fear;
            };
        }

        public void set(int cat, float v) {
            switch (cat) {
                case CAT_LOOK -> look = v;
                case CAT_ABILITIES -> abilities = v;
                default -> fear = v;
            }
        }

        public boolean isComplete() { return look > 0f && abilities > 0f && fear > 0f; }

        public boolean isEmpty() { return look <= 0f && abilities <= 0f && fear <= 0f; }

        /** Середнє по виставлених критеріях; 0, якщо нічого не виставлено. */
        public float average() {
            int n = 0;
            float sum = 0f;
            for (int c = 0; c < CATEGORIES; c++) {
                float v = get(c);
                if (v > 0f) { sum += v; n++; }
            }
            return n == 0 ? 0f : sum / n;
        }
    }

    private final List<String> knownManiacs = new ArrayList<>();
    private final Map<String, Rating> ratings = new LinkedHashMap<>();
    private boolean hasEverSubmitted;
    private boolean rateLater;
    private String comment = "";

    private ClientManiacRatingState() {}

    /** З {@code ManiacRatingSyncPacket} при заході на сервер. */
    public void onSync(List<String> ids, Map<String, float[]> saved, boolean everSubmitted, String savedComment) {
        knownManiacs.clear();
        knownManiacs.addAll(ids);
        ratings.clear();
        for (String id : ids) {
            Rating r = new Rating();
            float[] s = saved.get(id);
            if (s != null && s.length >= CATEGORIES) {
                r.look = s[0];
                r.abilities = s[1];
                r.fear = s[2];
            }
            ratings.put(id, r);
        }
        hasEverSubmitted = everSubmitted;
        rateLater = false;
        comment = savedComment != null ? savedComment : "";
    }

    /** Вихід зі світу: «пізніше» діє лише в межах однієї сесії. */
    public void onDisconnect() {
        rateLater = false;
    }

    public List<String> knownManiacs() { return Collections.unmodifiableList(knownManiacs); }

    public Rating rating(String id) { return ratings.computeIfAbsent(id, k -> new Rating()); }

    /** Усі оцінки (мутабельна мапа — її читає пакет відправки). */
    public Map<String, Rating> ratings() { return ratings; }

    public boolean hasEverSubmitted() { return hasEverSubmitted; }
    public void markSubmitted() { hasEverSubmitted = true; }

    public boolean isRateLater() { return rateLater; }
    public void setRateLater(boolean v) { rateLater = v; }

    public String comment() { return comment; }
    public void setComment(String c) { comment = c != null ? c : ""; }

    /** Чи показувати екран оцінок перед виходом. */
    public boolean shouldShowRatingOnExit() {
        return !hasEverSubmitted && !rateLater && !knownManiacs.isEmpty();
    }

    /** Чи показувати кнопку «Оцінити маньяків» в ESC. */
    public boolean showRateButtonInEsc() {
        return (hasEverSubmitted || rateLater) && !knownManiacs.isEmpty();
    }
}

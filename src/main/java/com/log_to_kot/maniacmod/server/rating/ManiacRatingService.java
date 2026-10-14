package com.log_to_kot.maniacmod.server.rating;

import com.log_to_kot.maniacmod.maniacs.ManiacRegistry;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.rating.AdminReviewsPacket;
import com.log_to_kot.maniacmod.net.s2c.rating.ManiacRatingSyncPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверна логіка оцінок маньяків: що надсилати клієнту, як
 * перевіряти те, що він прислав, і як зводити відгуки для адміна.
 *
 * ── Чому сервер перевіряє все ────────────────────────────────────────
 * Пакет відправки — це ввід клієнта, якого можна змінити. Тому тут:
 * відсів id, яких немає в {@link ManiacRegistry} (окрім {@code GAME_ID}); округлення до кроку
 * 0.5 і межі 0..5 (у тому числі для NaN); обрізання коментаря; ліміт
 * частоти. Те, що не пройшло, мовчки відкидається, а не ламає файл.
 */
public final class ManiacRatingService {

    /** Макс. довжина коментаря — та сама, що в полі вводу на клієнті. */
    private static final int MAX_COMMENT = 200;
    /** Мін. інтервал між двома відправками одного гравця, мс. */
    private static final long MIN_INTERVAL_MS = 1500L;

    private static final Map<UUID, Long> LAST_SUBMIT = new ConcurrentHashMap<>();

    private ManiacRatingService() {}

    /** Усі оцінювані id: спершу «вся гра», далі маньяки. */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>();
        ids.add(ManiacRatingSyncPacket.GAME_ID);
        ids.addAll(maniacIds());
        return ids;
    }

    /** Id усіх зареєстрованих маньяків у СТАБІЛЬНОМУ порядку (за алфавітом). */
    public static List<String> maniacIds() {
        return new ArrayList<>(new TreeSet<>(ManiacRegistry.all().keySet()));
    }

    /** Надіслати гравцю перелік маньяків і його збережені оцінки. */
    public static void sendSync(ServerPlayer player) {
        ManiacRatingStorage.PlayerRatings saved = ManiacRatingStorage.load(player.getUUID());
        boolean submitted = saved != null && saved.submitted();
        Map<String, float[]> ratings = saved != null ? saved.ratings() : Map.of();

        List<ManiacRatingSyncPacket.Entry> entries = new ArrayList<>();
        for (String id : allIds()) {
            float[] r = ratings.getOrDefault(id, new float[]{0f, 0f, 0f});
            entries.add(new ManiacRatingSyncPacket.Entry(id, r[0], r[1], r[2]));
        }
        ModNetwork.toPlayer(player, new ManiacRatingSyncPacket(
            submitted, entries, saved != null ? saved.comment() : ""));
    }

    /** Прийняти оцінки від гравця: перевірити, зберегти. */
    public static void onSubmit(ServerPlayer player, List<ManiacRatingSyncPacket.Entry> entries, String comment) {
        long now = System.currentTimeMillis();
        Long last = LAST_SUBMIT.get(player.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) return;
        LAST_SUBMIT.put(player.getUUID(), now);

        Map<String, float[]> clean = new LinkedHashMap<>();
        for (ManiacRatingSyncPacket.Entry e : entries) {
            if (!ManiacRatingSyncPacket.GAME_ID.equals(e.id()) && !ManiacRegistry.exists(e.id())) continue;
            float[] r = {sanitize(e.look()), sanitize(e.abilities()), sanitize(e.fear())};
            if (r[0] > 0f || r[1] > 0f || r[2] > 0f) clean.put(e.id(), r);
        }
        ManiacRatingStorage.save(player.getUUID(), player.getGameProfile().getName(), clean, sanitizeComment(comment));
    }

    /** Гравець вийшов — ліміт частоти йому більше не потрібен. */
    public static void forget(UUID player) {
        LAST_SUBMIT.remove(player);
    }

    /** 0 = не виставлено; інакше крок 0.5 у межах 0.5..5. */
    static float sanitize(float v) {
        if (Float.isNaN(v) || v <= 0f) return 0f;
        float stepped = Math.round(v * 2f) / 2f;
        return Math.max(0.5f, Math.min(5f, stepped));
    }

    static String sanitizeComment(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_COMMENT; i++) {
            char c = raw.charAt(i);
            if (c >= 32 && c != 127) sb.append(c);
        }
        return sb.toString().trim();
    }

    // ── Зведення для адміна ─────────────────────────────────────────────

    /** Середні оцінки за всіма гравцями: одного маньяка або (id = GAME_ID) гри в цілому. */
    public record Summary(String maniacId, int voters, float look, float abilities, float fear) {
        public float overall() { return (look + abilities + fear) / 3f; }
    }

    /** Один відгук для стрічки коментарів. */
    public record Review(String player, String timestamp, String comment) {}

    /** Зведення по грі та по КОЖНОМУ маньяку (навіть якщо оцінок ще немає — voters = 0). */
    public static List<Summary> summarize(List<ManiacRatingStorage.PlayerRatings> all) {
        List<Summary> out = new ArrayList<>();
        for (String id : allIds()) {
            int n = 0;
            float look = 0f, abilities = 0f, fear = 0f;
            for (ManiacRatingStorage.PlayerRatings p : all) {
                float[] r = p.ratings().get(id);
                if (r == null || r.length < 3) continue;
                if (r[0] <= 0f && r[1] <= 0f && r[2] <= 0f) continue;
                n++;
                look += r[0];
                abilities += r[1];
                fear += r[2];
            }
            out.add(n == 0
                ? new Summary(id, 0, 0f, 0f, 0f)
                : new Summary(id, n, look / n, abilities / n, fear / n));
        }
        return out;
    }

    /**
     * Усі відгуки для адмін-екрана: зведення + кожен гравець з іменем,
     * часом, коментарем і своїми оцінками (новіші першими). Права
     * перевіряє виклик ({@code ServerPacketHandler.onAdminReviewsRequest}).
     */
    public static AdminReviewsPacket buildAdminPacket() {
        List<ManiacRatingStorage.PlayerRatings> all = ManiacRatingStorage.loadAll();

        List<AdminReviewsPacket.Summary> summaries = new ArrayList<>();
        for (Summary s : summarize(all)) {
            summaries.add(new AdminReviewsPacket.Summary(
                s.maniacId(), s.voters(), s.look(), s.abilities(), s.fear()));
        }

        List<AdminReviewsPacket.Review> reviews = new ArrayList<>();
        for (ManiacRatingStorage.PlayerRatings p : all) {
            if (reviews.size() >= AdminReviewsPacket.MAX_REVIEWS) break;
            List<ManiacRatingSyncPacket.Entry> entries = new ArrayList<>();
            for (Map.Entry<String, float[]> e : p.ratings().entrySet()) {
                float[] r = e.getValue();
                if (r == null || r.length < 3) continue;
                if (r[0] <= 0f && r[1] <= 0f && r[2] <= 0f) continue;
                if (entries.size() >= AdminReviewsPacket.MAX_RATINGS) break;
                entries.add(new ManiacRatingSyncPacket.Entry(e.getKey(), r[0], r[1], r[2]));
            }
            reviews.add(new AdminReviewsPacket.Review(
                p.playerName() != null ? p.playerName() : "???",
                p.timestamp() != null ? p.timestamp() : "",
                p.comment() != null ? p.comment() : "",
                entries));
        }
        return new AdminReviewsPacket(summaries, reviews);
    }

    /** Останні непорожні коментарі (вхід уже відсортований: новіші першими). */
    public static List<Review> latestComments(List<ManiacRatingStorage.PlayerRatings> all, int limit) {
        List<Review> out = new ArrayList<>();
        for (ManiacRatingStorage.PlayerRatings p : all) {
            if (p.comment() == null || p.comment().isBlank()) continue;
            out.add(new Review(p.playerName(), p.timestamp(), p.comment()));
            if (out.size() >= limit) break;
        }
        return out;
    }
}

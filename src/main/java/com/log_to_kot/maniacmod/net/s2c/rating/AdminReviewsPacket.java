package com.log_to_kot.maniacmod.net.s2c.rating;

import com.log_to_kot.maniacmod.net.S2CPacket;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер → клієнт (лише адміну): усі відгуки гравців для екрана
 * {@code AdminReviewsScreen} — хто що поставив і з яким коментарем.
 *
 * ── Категорія: rating ────────────────────────────────────────────────
 * Це дані профілів гравців, а не стан матчу. Шлеться ТІЛЬКИ у відповідь
 * на команду {@code /maniac reviews} після перевірки прав на сервері.
 *
 * ── Що всередині ─────────────────────────────────────────────────────
 *   • {@code summaries} — середні оцінки гри та кожного маньяка (рахує
 *     сервер: {@code ManiacRatingService.summarize}, клієнт не дублює);
 *   • {@code reviews} — по одному запису на гравця, новіші першими: ім'я,
 *     час, коментар і ВСІ його оцінки.
 *
 * Розміри обмежені (і при читанні, і при записі), щоб пакет не
 * перевищив ліміт мережевого кадру.
 */
public record AdminReviewsPacket(List<Summary> summaries, List<Review> reviews) implements S2CPacket {

    public static final int MAX_SUMMARIES = 128;
    public static final int MAX_REVIEWS = 100;
    public static final int MAX_RATINGS = 64;

    /** Середні по одному id (гра або маньяк); {@code voters = 0} — оцінок ще немає. */
    public record Summary(String id, int voters, float look, float abilities, float fear) {}

    /** Відгук одного гравця. {@code ratings} — як і в синхронізації: id + три критерії. */
    public record Review(String player, String timestamp, String comment,
                         List<ManiacRatingSyncPacket.Entry> ratings) {
        public Review {
            ratings = List.copyOf(ratings);
        }
    }

    public AdminReviewsPacket {
        summaries = List.copyOf(summaries);
        reviews = List.copyOf(reviews);
    }

    public AdminReviewsPacket(FriendlyByteBuf buf) {
        this(readSummaries(buf), readReviews(buf));
    }

    private static List<Summary> readSummaries(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_SUMMARIES);
        List<Summary> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(new Summary(buf.readUtf(64), buf.readVarInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return list;
    }

    private static List<Review> readReviews(FriendlyByteBuf buf) {
        int size = Math.min(buf.readVarInt(), MAX_REVIEWS);
        List<Review> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String player = buf.readUtf(64);
            String ts = buf.readUtf(40);
            String comment = buf.readUtf(ManiacRatingSyncPacket.MAX_COMMENT);
            int n = Math.min(buf.readVarInt(), MAX_RATINGS);
            List<ManiacRatingSyncPacket.Entry> ratings = new ArrayList<>(n);
            for (int j = 0; j < n; j++) {
                ratings.add(new ManiacRatingSyncPacket.Entry(
                    buf.readUtf(64), buf.readFloat(), buf.readFloat(), buf.readFloat()));
            }
            list.add(new Review(player, ts, comment, ratings));
        }
        return list;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        int ns = Math.min(summaries.size(), MAX_SUMMARIES);
        buf.writeVarInt(ns);
        for (int i = 0; i < ns; i++) {
            Summary s = summaries.get(i);
            buf.writeUtf(s.id(), 64);
            buf.writeVarInt(s.voters());
            buf.writeFloat(s.look());
            buf.writeFloat(s.abilities());
            buf.writeFloat(s.fear());
        }
        int nr = Math.min(reviews.size(), MAX_REVIEWS);
        buf.writeVarInt(nr);
        for (int i = 0; i < nr; i++) {
            Review r = reviews.get(i);
            buf.writeUtf(r.player(), 64);
            buf.writeUtf(r.timestamp(), 40);
            buf.writeUtf(r.comment(), ManiacRatingSyncPacket.MAX_COMMENT);
            int n = Math.min(r.ratings().size(), MAX_RATINGS);
            buf.writeVarInt(n);
            for (int j = 0; j < n; j++) {
                ManiacRatingSyncPacket.Entry e = r.ratings().get(j);
                buf.writeUtf(e.id(), 64);
                buf.writeFloat(e.look());
                buf.writeFloat(e.abilities());
                buf.writeFloat(e.fear());
            }
        }
    }

    @Override
    public void clientHandle() {
        com.log_to_kot.maniacmod.client.ClientPacketHandler.onAdminReviews(this);
    }
}

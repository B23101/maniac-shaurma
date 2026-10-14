package com.log_to_kot.maniacmod.progression.profile;

import net.minecraft.network.FriendlyByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Довготривалий профіль гравця: XP-доріжки й накопичена статистика по ролях.
 *
 * <p>XP зберігається по ролях: виживший ({@link #survivorXp}) і кожен маніяк окремо
 * ({@link Maniac#xp}), але ПРОГРЕС спільний — рівень рахується із загальної суми
 * ({@link #totalXp()}), а ранги в ролей різні ({@code Ranks.Role}). Рівень і ранг — похідні,
 * тож тут зберігаються лише сирі лічильники. Той самий клас їде мережею ({@link #write}/{@link #read}) —
 * екран профілю малює саме його.</p>
 *
 * <p>Поля публічні й не-final, щоб Gson читав/писав файл без адаптерів; відсутні в старому файлі
 * ключі лишаються зі значеннями за замовчуванням.</p>
 */
public final class PlayerProfile {

    public static final int MAX_MANIACS = 64;

    public long survivorXp;

    // ── Службові поля для лідербордів (НЕ їдуть мережею з профілем; лише файл) ──
    /** Останній відомий нік: лідерборд показує й офлайн-гравців. */
    public String playerName = "";
    /** Значення властивості {@code textures} (base64) — з нього клієнт малює 3D-бюст офлайн-гравця. */
    public String skinTexture = "";
    /** Коли гравець востаннє заходив / завершив матч (мс, epoch). */
    public long lastSeenMs;

    public Survivor survivor = new Survivor();
    public Map<String, Maniac> maniacs = new LinkedHashMap<>();

    /** Статистика гравця в ролі виживого. */
    public static final class Survivor {
        public int matches, wins, escapes, eliminated;
        public int bestSurvivedSeconds, winStreak, bestWinStreak;
        public long hitsTaken, rescues, hooked, repairSeconds, survivedSeconds;
    }

    /** Статистика гравця в ролі КОНКРЕТНОГО маніяка. */
    public static final class Maniac {
        public long xp, hitsDealt, downs, hooks, playedSeconds;
        public int matches, wins, bestDowns, winStreak, bestWinStreak;
        /** Пастки цього маніяка за типами: id пастки → лічильники. */
        public Map<String, Trap> traps = new LinkedHashMap<>();

        public long trapsPlaced() {
            long n = 0;
            for (Trap t : traps.values()) if (t != null) n += t.placed;
            return n;
        }

        public long trapsCaught() {
            long n = 0;
            for (Trap t : traps.values()) if (t != null) n += t.caught;
            return n;
        }
    }

    /** Статистика одного типу пасток: скільки поставлено й скільки жертв зачепило. */
    public static final class Trap {
        public long placed, caught;
    }

    public static final int MAX_TRAP_TYPES = 16;

    /** Спільний прогрес: XP виживого + XP усіх маніяків. Від нього рахуються рівень і ранги. */
    public long totalXp() {
        long sum = survivorXp;
        for (Maniac m : maniacs.values()) if (m != null) sum += m.xp;
        return sum;
    }

    /** Чи є що показувати в лідербордах: без цього порожні профілі не засмічують топи. */
    public boolean hasActivity() {
        if (totalXp() > 0 || survivor.matches > 0) return true;
        for (Maniac m : maniacs.values()) if (m != null && m.matches > 0) return true;
        return false;
    }

    /** Усього зіграно матчів (виживий + усі маніяки). */
    public int totalMatches() {
        int n = survivor.matches;
        for (Maniac m : maniacs.values()) if (m != null) n += m.matches;
        return n;
    }

    /** Усього перемог (виживий + усі маніяки). */
    public int totalWins() {
        int n = survivor.wins;
        for (Maniac m : maniacs.values()) if (m != null) n += m.wins;
        return n;
    }

    /** id маніяка з найбільшим XP; порожньо, якщо за маніяка не грали. */
    public String favouriteManiac() {
        String best = "";
        long bestXp = -1L;
        for (Map.Entry<String, Maniac> e : maniacs.entrySet()) {
            Maniac m = e.getValue();
            if (m == null || m.matches <= 0) continue;
            if (m.xp > bestXp) { bestXp = m.xp; best = e.getKey(); }
        }
        return best;
    }

    public Maniac maniac(String id) {
        return maniacs.computeIfAbsent(id, k -> new Maniac());
    }

    /** Після читання зі старого/ушкодженого файлу — гарантує відсутність null. */
    public PlayerProfile normalize() {
        if (survivor == null) survivor = new Survivor();
        if (playerName == null) playerName = "";
        if (skinTexture == null) skinTexture = "";
        if (maniacs == null) maniacs = new LinkedHashMap<>();
        maniacs.values().removeIf(m -> m == null);
        for (Maniac m : maniacs.values()) {
            if (m.traps == null) m.traps = new LinkedHashMap<>();
            m.traps.values().removeIf(t -> t == null);
        }
        return this;
    }

    // ── Мережа ───────────────────────────────────────────────────────────

    public void write(FriendlyByteBuf b) {
        b.writeVarLong(survivorXp);
        Survivor s = survivor;
        b.writeVarInt(s.matches); b.writeVarInt(s.wins); b.writeVarInt(s.escapes); b.writeVarInt(s.eliminated);
        b.writeVarInt(s.bestSurvivedSeconds); b.writeVarInt(s.winStreak); b.writeVarInt(s.bestWinStreak);
        b.writeVarLong(s.hitsTaken); b.writeVarLong(s.rescues); b.writeVarLong(s.hooked);
        b.writeVarLong(s.repairSeconds); b.writeVarLong(s.survivedSeconds);
        int n = Math.min(maniacs.size(), MAX_MANIACS);
        b.writeVarInt(n);
        int i = 0;
        for (Map.Entry<String, Maniac> e : maniacs.entrySet()) {
            if (i++ >= n) break;
            Maniac m = e.getValue();
            b.writeUtf(e.getKey(), 64);
            b.writeVarLong(m.xp); b.writeVarLong(m.hitsDealt); b.writeVarLong(m.downs);
            b.writeVarLong(m.hooks); b.writeVarLong(m.playedSeconds);
            b.writeVarInt(m.matches); b.writeVarInt(m.wins); b.writeVarInt(m.bestDowns);
            b.writeVarInt(m.winStreak); b.writeVarInt(m.bestWinStreak);
            int tn = Math.min(m.traps.size(), MAX_TRAP_TYPES);
            b.writeVarInt(tn);
            int ti = 0;
            for (Map.Entry<String, Trap> te : m.traps.entrySet()) {
                if (ti++ >= tn) break;
                b.writeUtf(te.getKey(), 64);
                b.writeVarLong(te.getValue().placed); b.writeVarLong(te.getValue().caught);
            }
        }
    }

    public static PlayerProfile read(FriendlyByteBuf b) {
        PlayerProfile p = new PlayerProfile();
        p.survivorXp = b.readVarLong();
        Survivor s = p.survivor;
        s.matches = b.readVarInt(); s.wins = b.readVarInt(); s.escapes = b.readVarInt(); s.eliminated = b.readVarInt();
        s.bestSurvivedSeconds = b.readVarInt(); s.winStreak = b.readVarInt(); s.bestWinStreak = b.readVarInt();
        s.hitsTaken = b.readVarLong(); s.rescues = b.readVarLong(); s.hooked = b.readVarLong();
        s.repairSeconds = b.readVarLong(); s.survivedSeconds = b.readVarLong();
        int n = Math.min(b.readVarInt(), MAX_MANIACS);
        for (int i = 0; i < n; i++) {
            String id = b.readUtf(64);
            Maniac m = new Maniac();
            m.xp = b.readVarLong(); m.hitsDealt = b.readVarLong(); m.downs = b.readVarLong();
            m.hooks = b.readVarLong(); m.playedSeconds = b.readVarLong();
            m.matches = b.readVarInt(); m.wins = b.readVarInt(); m.bestDowns = b.readVarInt();
            m.winStreak = b.readVarInt(); m.bestWinStreak = b.readVarInt();
            int tn = Math.min(b.readVarInt(), MAX_TRAP_TYPES);
            for (int t = 0; t < tn; t++) {
                String tid = b.readUtf(64);
                Trap tr = new Trap();
                tr.placed = b.readVarLong(); tr.caught = b.readVarLong();
                m.traps.put(tid, tr);
            }
            p.maniacs.put(id, m);
        }
        return p;
    }
}

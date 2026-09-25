package com.log_to_kot.maniacmod.sound;

import com.log_to_kot.maniacmod.ManiacMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Тривалість .ogg-файлу з ресурсів мода, у тіках.
 *
 * ── Навіщо це взагалі потрібно ───────────────────────────────────────
 * Звук, що «грає безперервно», у Minecraft не існує як режим: сервер
 * грає звук один раз, а безперервність дає те, що хтось програє його
 * знову РІВНО тоді, коли попереднє програвання скінчилось. Тобто серверу
 * треба знати довжину файлу — інакше або чути шов (програли раніше), або
 * паузу (програли пізніше).
 *
 * ── Чому з файлу, а не з константи в коді ────────────────────────────
 * Число «8.5 секунд» у коді — це друге джерело правди про той самий
 * файл, і воно стає брехнею в ту саму мить, коли художник замінить ogg
 * на довший. Тому довжина читається з самого файлу: заміна асета нічого
 * не ламає, а ключів конфігу під неї не треба взагалі.
 *
 * ── Як саме (Ogg Vorbis, без зовнішніх бібліотек) ────────────────────
 * Файл читається як набір сторінок Ogg:
 * <pre>
 *   0-3  "OggS"      6-13  granule position (8 байт, LE)
 *   26   кількість сегментів (байтів у таблиці)
 *   27…  таблиця сегментів: довжини шматків тіла
 *        далі — тіло сторінки
 * </pre>
 * Остання сторінка несе granule = кількість семплів усього потоку, а
 * частота дискретизації лежить в ідентифікаційному заголовку першої
 * сторінки. Звідси {@code seconds = granule / sampleRate}. Беру не
 * «останню сторінку», а МАКСИМУМ granule: остання сторінка може бути
 * службовою (granule 0), і тоді довжина вийшла б нульовою.
 *
 * Це саме парсинг заголовків, без декодування аудіо: 40 рядків замість
 * аудіо-бібліотеки в залежностях мода.
 */
public final class OggSoundLength {

    /** Тека звуків мода всередині ресурсів. */
    private static final String DIRECTORY = "assets/maniacmod/sounds/";

    /** null = «ще не читали», щоб не парсити той самий файл щоразу. */
    private static final Map<String, Integer> CACHE = new HashMap<>();

    private static final int PAGE_HEADER_BYTES = 27;

    private OggSoundLength() {}

    /**
     * Тривалість звуку в тіках (20 тіків = 1 с), або 0, якщо файл
     * недоступний / не є Ogg Vorbis.
     *
     * 0 — це НЕ помилка на цьому рівні: викликач (луп) просто не має
     * чим рахувати інтервал, і його {@code 0} стає «грати щотіка», що
     * чути як тріск. Тому викликач зобов'язаний трактувати 0 як «луп
     * неможливий» і не грати взагалі — див. {@code GeneratorSoundscape}.
     * А сюди перед тим уже прилетить WARN із причиною.
     */
    public static synchronized int ticksOf(String soundName) {
        Integer cached = CACHE.get(soundName);
        if (cached != null) return cached;

        int ticks = parse(soundName);
        CACHE.put(soundName, ticks);
        return ticks;
    }

    private static int parse(String soundName) {
        String path = DIRECTORY + soundName + ".ogg";
        byte[] bytes;
        try (InputStream in = ManiacMod.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                ManiacMod.LOGGER.warn("[sound] немає файлу {} — луп на ньому зібрати неможливо", path);
                return 0;
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            ManiacMod.LOGGER.warn("[sound] {} не прочитався: {}", path, e.getMessage());
            return 0;
        }

        int sampleRate = 0;
        long maxGranule = 0;
        int page = 0;
        boolean first = true;

        while (page + PAGE_HEADER_BYTES <= bytes.length && isOggPageAt(bytes, page)) {
            if (first) {
                sampleRate = readSampleRate(bytes, page);
                first = false;
            }
            long granule = readGranule(bytes, page);
            if (granule > maxGranule) maxGranule = granule;

            int segments = bytes[page + 26] & 0xFF;
            if (page + PAGE_HEADER_BYTES + segments > bytes.length) break;
            int bodyBytes = 0;
            for (int i = 0; i < segments; i++) bodyBytes += bytes[page + PAGE_HEADER_BYTES + i] & 0xFF;
            page += PAGE_HEADER_BYTES + segments + bodyBytes;
        }

        if (sampleRate <= 0 || maxGranule <= 0) {
            ManiacMod.LOGGER.warn("[sound] {} не читається як Ogg Vorbis (rate={}, samples={})",
                path, sampleRate, maxGranule);
            return 0;
        }
        return (int) Math.max(1, Math.round((double) maxGranule * 20.0 / sampleRate));
    }

    private static boolean isOggPageAt(byte[] bytes, int page) {
        return bytes[page] == 'O' && bytes[page + 1] == 'g' && bytes[page + 2] == 'g' && bytes[page + 3] == 'S';
    }

    /** granule position: 8 байт LE за зміщенням 6 у заголовку сторінки. */
    private static long readGranule(byte[] bytes, int page) {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[page + 6 + i] & 0xFF);
        return value;
    }

    /**
     * Частота дискретизації з ідентифікаційного заголовка Vorbis:
     * {@code 0x01 "vorbis" version(4) channels(1) rate(4 LE)}.
     */
    private static int readSampleRate(byte[] bytes, int page) {
        int segments = bytes[page + 26] & 0xFF;
        int body = page + PAGE_HEADER_BYTES + segments;
        if (body + 16 > bytes.length) return 0;
        if (bytes[body] != 1) return 0; // тип пакета: 1 = ідентифікація
        if (bytes[body + 1] != 'v' || bytes[body + 2] != 'o' || bytes[body + 3] != 'r'
            || bytes[body + 4] != 'b' || bytes[body + 5] != 'i' || bytes[body + 6] != 's') {
            return 0;
        }
        int rate = 0;
        for (int i = 3; i >= 0; i--) rate = (rate << 8) | (bytes[body + 12 + i] & 0xFF);
        return rate;
    }
}

package com.log_to_kot.maniacmod.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Точкова правка одного значення в тексті yml — без перезапису решти файлу.
 *
 * ── Навіщо ───────────────────────────────────────────────────────────
 * Раніше меню налаштувань змінювало значення так: читало файл у мапу,
 * міняло ключ і записувало все через {@code YAML.dump}. Це стирало всі
 * коментарі, групування й порядок ключів — файл після першого ж кліку в
 * меню ставав плоским списком без пояснень. Тут змінюється лише рядок
 * {@code name: значення}; коментарі над ним і порожні рядки лишаються.
 *
 * ── Що вміє ──────────────────────────────────────────────────────────
 *   • ПЛОСКИЙ файл (ключі в корені: {@code generators.yml});
 *   • ВКЛАДЕНИЙ блок ({@code maniac:} → ключі з відступом), причому в
 *     файлі може бути кілька блоків ({@code maniacs.yml}) — шукається
 *     лише в тілі потрібного;
 *   • кінцевий коментар у тому ж рядку ({@code key: 5  # пояснення}).
 *
 * Якщо рядок ключа не знайдено (чи він записаний у нестандартному
 * вигляді, наприклад багаторядковий скаляр), повертає {@code null} —
 * викликач тоді сам вирішує, що робити (старий шлях через dump).
 *
 * Чистий Java без залежностей від Minecraft і ShaurmaLib, тому
 * перевіряється звичайним юніт-тестом.
 */
public final class ConfigTextEditor {

    private ConfigTextEditor() {}

    /**
     * @param text       увесь текст yml
     * @param blockId    id вкладеного блока, або {@code null} для плоского файлу
     * @param key        ім'я ключа
     * @param yamlValue  нове значення, уже у вигляді YAML-скаляра ({@code 5}, {@code true}, {@code 'RANDOM'})
     * @return новий текст або {@code null}, якщо рядка ключа не знайдено
     */
    public static String replaceValue(String text, String blockId, String key, String yamlValue) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String[] rows = text.split("\r?\n", -1);

        int from = 0;
        int to = rows.length;
        boolean nested = blockId != null;
        if (nested) {
            int header = findRootKey(rows, blockId);
            if (header < 0) return null;
            from = header + 1;
            to = endOfBody(rows, from);
        }

        Pattern line = Pattern.compile(
            "^(" + (nested ? "[ \\t]+" : "") + Pattern.quote(key) + ":[ \\t]*)(.*)$");

        for (int i = from; i < to; i++) {
            String row = rows[i];
            if (row.isBlank() || row.stripLeading().startsWith("#")) continue;
            Matcher m = line.matcher(row);
            if (!m.matches()) continue;

            String rest = m.group(2);
            // Без значення в цьому рядку (вкладена мапа чи список) — не скаляр.
            if (rest.isBlank() || rest.startsWith("#")) return null;
            if (rest.startsWith("|") || rest.startsWith(">") || rest.startsWith("[") || rest.startsWith("{")) {
                return null;
            }
            rows[i] = m.group(1) + yamlValue + trailingComment(rest);
            return String.join(eol, rows);
        }
        return null;
    }

    /** Кінцевий коментар ({@code "  # текст"}) зі старого значення, разом з пробілами перед ним. */
    private static String trailingComment(String rest) {
        boolean quoted = rest.startsWith("'") || rest.startsWith("\"");
        int start;
        if (quoted) {
            char q = rest.charAt(0);
            int end = 1;
            while (end < rest.length()) {
                if (rest.charAt(end) == q) {
                    // '' усередині одинарних лапок — екранована лапка
                    if (q == '\'' && end + 1 < rest.length() && rest.charAt(end + 1) == '\'') { end += 2; continue; }
                    break;
                }
                if (q == '"' && rest.charAt(end) == '\\') end++;
                end++;
            }
            start = end + 1;
        } else {
            start = 0;
        }
        int hash = rest.indexOf(" #", Math.min(start, rest.length()));
        if (hash < 0) hash = rest.indexOf("\t#", Math.min(start, rest.length()));
        if (hash < 0) return "";
        int spaces = hash;
        while (spaces > 0 && (rest.charAt(spaces - 1) == ' ' || rest.charAt(spaces - 1) == '\t')) spaces--;
        return rest.substring(spaces);
    }

    private static int findRootKey(String[] rows, String id) {
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i];
            if (r.isEmpty()) continue;
            char c = r.charAt(0);
            if (c == ' ' || c == '\t' || c == '#') continue;
            if (r.stripTrailing().equals(id + ":")) return i;
        }
        return -1;
    }

    /** Індекс рядка, де закінчується тіло блока (наступний кореневий ключ чи кінець файлу). */
    private static int endOfBody(String[] rows, int from) {
        for (int i = from; i < rows.length; i++) {
            String r = rows[i];
            if (r.isBlank()) continue;
            char c = r.charAt(0);
            if (c == '#') continue;
            if (c != ' ' && c != '\t') return i;
        }
        return rows.length;
    }
}

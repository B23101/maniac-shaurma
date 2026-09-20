package com.log_to_kot.maniacmod.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Збирає все, що не так із конфігом, за один прохід читання.
 *
 * НАВІЩО: щоб адмін побачив ПОВНИЙ список проблем одразу, а не
 * виправляв по одній, перезапускаючи сервер щоразу. Помилка конфігу
 * ніколи не валить старт — мод працює на дефолтах і голосно каже, де
 * саме файл розходиться зі схемою.
 */
public final class ConfigDiagnostics {

    public enum Level { INFO, WARN }

    public record Entry(Level level, String message) {}

    private final List<Entry> entries = new ArrayList<>();

    /** Блока не було — його дописано з дефолту в jar. */
    public void healedBlock(String blockId) {
        entries.add(new Entry(Level.INFO,
            "блок '" + blockId + "' був відсутній — додано з дефолту"));
    }

    /** У наявний файл дописано ключі, яких у ньому бракувало (мод оновився). */
    public void healedKeys(String fileName, List<String> keys) {
        entries.add(new Entry(Level.INFO,
            "у " + fileName + " дописано нові налаштування з дефолту: " + String.join(", ", keys)));
    }

    /**
     * Ключів бракує, але безпечно дописати їх не вдалось (вкладений
     * блок не в кінці файлу). Значення читаються з дефолту в пам'яті.
     */
    public void keysNotWritten(String blockId, List<String> keys) {
        entries.add(new Entry(Level.WARN,
            "блок '" + blockId + "': бракує " + String.join(", ", keys)
            + " — дописати автоматично не вдалось, додай вручну (зараз діє дефолт)"));
    }

    /** Блока немає і в jar-дефолті теж. Це вже помилка збірки мода. */
    public void blockMissingEverywhere(String blockId) {
        entries.add(new Entry(Level.WARN,
            "блок '" + blockId + "' відсутній і у файлі, і в дефолті jar — працюю на вбудованих значеннях"));
    }

    public void missingKey(ConfigKey<?> key) {
        entries.add(new Entry(Level.INFO,
            key.path() + " не задано — беру дефолт " + key.defaultValue()));
    }

    public void wrongType(ConfigKey<?> key, Object raw) {
        entries.add(new Entry(Level.WARN,
            key.path() + ": не вдалось прочитати '" + raw + "' — беру дефолт "
            + key.defaultValue() + " (очікується " + key.describeRange() + ")"));
    }

    public void corrected(ConfigKey<?> key, Object was, Object now) {
        entries.add(new Entry(Level.WARN,
            key.path() + ": значення " + was + " поза допустимим (" + key.describeRange()
            + ") — використано " + now));
    }

    /** Ключ є у файлі, але не оголошений у схемі. Мертве налаштування. */
    public void unknownKey(String blockId, String keyName) {
        entries.add(new Entry(Level.WARN,
            blockId + "." + keyName + ": такого налаштування не існує — воно ні на що не впливає"));
    }

    /** Блок є у файлі, але не оголошений у схемі. */
    public void unknownBlock(String blockId) {
        entries.add(new Entry(Level.WARN,
            "блок '" + blockId + "' не існує в моді — він ні на що не впливає"));
    }

    public List<Entry> entries()  { return List.copyOf(entries); }
    public boolean isEmpty()      { return entries.isEmpty(); }

    public boolean hasWarnings() {
        return entries.stream().anyMatch(e -> e.level() == Level.WARN);
    }
}

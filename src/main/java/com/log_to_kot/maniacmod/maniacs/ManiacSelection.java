package com.log_to_kot.maniacmod.maniacs;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Random;

/**
 * Вибір маньяка за конфігом: хто ним стане і яким персонажем.
 *
 * Винесено з команди старту, бо це рішення потрібне ще й авто-старту
 * та рестарту раунду — а дублювати три режими вибору в кожному місці
 * означало б рано чи пізно розійтися в поведінці.
 */
public final class ManiacSelection {

    /** Результат вибору. archetype може бути null для режиму MENU. */
    public record Choice(ServerPlayer player, ManiacArchetype archetype, boolean needsMenu) {}

    private ManiacSelection() {}

    /**
     * @param players  усі гравці онлайн
     * @param forced   гравець, вказаний адміном у команді; null — за конфігом
     * @return вибір, або null якщо реєстр маньяків порожній
     */
    public static Choice choose(List<ServerPlayer> players, ServerPlayer forced, Random rng) {
        if (ManiacRegistry.isEmpty() || players.isEmpty()) return null;

        ServerPlayer player = forced != null ? forced : pickPlayer(players, rng);

        return switch (ManiacConfigs.get(ConfigSchema.MANIAC_TYPE_MODE)) {
            case "MENU"  -> new Choice(player, null, true);
            case "FIXED" -> new Choice(player, fixedOrRandom(rng), false);
            default      -> new Choice(player, randomArchetype(rng), false);
        };
    }

    /**
     * MANUAL без явного гравця в команді все одно має когось дати —
     * інакше адмін, що забув аргумент, отримав би мовчазну відмову
     * старту. Тому падаємо у випадковий вибір і це видно в логах.
     */
    private static ServerPlayer pickPlayer(List<ServerPlayer> players, Random rng) {
        return players.get(rng.nextInt(players.size()));
    }

    /**
     * FIXED з невідомим id — не привід зривати матч: беремо
     * випадкового. Перевірка id живе тут, а не в конфігу, бо перелік
     * маньяків задає код.
     */
    private static ManiacArchetype fixedOrRandom(Random rng) {
        String id = ManiacConfigs.get(ConfigSchema.FIXED_MANIAC_ID);
        if (!id.isEmpty() && ManiacRegistry.exists(id)) return ManiacRegistry.get(id);
        return randomArchetype(rng);
    }

    private static ManiacArchetype randomArchetype(Random rng) {
        List<ManiacArchetype> all = List.copyOf(ManiacRegistry.all().values());
        return all.get(rng.nextInt(all.size()));
    }
}

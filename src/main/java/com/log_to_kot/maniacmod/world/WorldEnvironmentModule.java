package com.log_to_kot.maniacmod.world;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Keeps the match clock deterministic and owns all match weather changes.
 *
 * ── Час доби (виправлено) ──────────────────────────────────────────
 * Раніше {@code maintainGameTime} викликався ЛИШЕ в ігровій фазі, тому
 * в лобі час біг звичайним ходом — попри те, що {@code maintainGameTime}
 * у конфігу стоїть {@code true}. Тепер годину притримує в кожній фазі
 * (і лобі, і гра): саме цього вимагає налаштування, і саме тому
 * "конфіг був, а час усе одно йшов".
 *
 * ── Погода в лобі — НЕ налаштування, а правило ──────────────────────
 * У всіх технічних фазах (лобі, кіно, розкидання, оголошення ролей,
 * підсумки, скидання) погода прибирається КОЖНОГО тіку й не може
 * з'явитись узагалі: там працює лише час доби. Тому в схемі й немає
 * ключа на кшталт {@code lobbyClearWeather} — це не вибір адміна, а
 * властивість режиму, і налаштування, яке можна випадково вимкнути й
 * отримати дощ посеред лобі, тут було б шкідливим.
 *
 * Під час гри події плануються випадковими інтервалами з тривалістю й
 * цільовою часткою з конфігу. Перша подія рахується від найменшого
 * інтервалу, щоб погоду було видно вже в першому матчі, а не через
 * 5–15 хвилин.
 */
public final class WorldEnvironmentModule implements PhaseListener {

    private final Supplier<MatchOrchestrator> matchSupplier;
    private final Random random = new Random();

    private boolean gameplaySession;
    private boolean weatherActive;
    private boolean thunder;
    private long gameplayTicks;
    private long weatherTicks;
    private long nextEventTick;
    private long eventTicksLeft;

    public WorldEnvironmentModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    @Override
    public String id() {
        return "world-environment";
    }

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        if (!phase.isGameplay()) {
            gameplaySession = false;
            weatherActive = false;
            eventTicksLeft = 0;
            clearWeather();
            maintainGameTime(server());
            return;
        }

        if (!gameplaySession) {
            gameplaySession = true;
            gameplayTicks = 0;
            weatherTicks = 0;
            weatherActive = false;
            eventTicksLeft = 0;
            // Перший інтервал — мінімальний з конфігу (без випадкового
            // розкиду), щоб погода гарантовано трапилась у першому матчі,
            // а не через випадкові 5–15 хвилин.
            nextEventTick = (long) ManiacConfigs.get(ConfigSchema.WEATHER_MIN_INTERVAL_SECONDS) * 20L;
            clearWeather();
        }
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        MinecraftServer server = server();
        if (server == null) return;

        // Годину притримує в КОЖНІЙ фазі (лобі теж) — див. докклас.
        maintainGameTime(server);

        if (!phase.isGameplay()) {
            // Лобі (і будь-яка технічна фаза) — погоди немає взагалі.
            clearWeather();
            return;
        }

        gameplayTicks++;

        if (!ManiacConfigs.get(ConfigSchema.WEATHER_EVENTS_ENABLED)) {
            weatherActive = false;
            eventTicksLeft = 0;
            clearWeather();
            return;
        }

        if (weatherActive) {
            weatherTicks++;
            eventTicksLeft--;
            applyWeather(server);
            if (eventTicksLeft <= 0) {
                weatherActive = false;
                clearWeather();
                nextEventTick = gameplayTicks + randomIntervalTicks();
            }
            return;
        }

        clearWeather();
        if (gameplayTicks < nextEventTick) return;

        double share = gameplayTicks == 0 ? 0.0 : (double) weatherTicks / gameplayTicks;
        double targetShare = ManiacConfigs.get(ConfigSchema.WEATHER_TARGET_SHARE);
        if (share < targetShare
            && random.nextDouble() <= ManiacConfigs.get(ConfigSchema.WEATHER_EVENT_CHANCE)) {
            startEvent(server);
        } else {
            nextEventTick = gameplayTicks + randomIntervalTicks();
        }
    }

    private void startEvent(MinecraftServer server) {
        long duration = randomDurationTicks();
        if (duration <= 0) {
            nextEventTick = gameplayTicks + randomIntervalTicks();
            return;
        }
        weatherActive = true;
        thunder = random.nextDouble() <= ManiacConfigs.get(ConfigSchema.WEATHER_THUNDER_CHANCE);
        eventTicksLeft = duration;
        weatherTicks++;
        applyWeather(server);
    }

    private void maintainGameTime(MinecraftServer server) {
        if (server == null) return;
        if (!ManiacConfigs.get(ConfigSchema.MAINTAIN_GAME_TIME)) return;
        long time = ManiacConfigs.get(ConfigSchema.GAME_TIME_TICKS);
        for (ServerLevel level : server.getAllLevels()) {
            // Пишемо лише коли реально відрізняється: інакше кожен тік
            // летить ClientboundSetTimePacket на 3 рівні (60 пакетів/с).
            if (level.getDayTime() % 24000L != time % 24000L) {
                level.setDayTime(time);
            }
        }
    }

    private void applyWeather(MinecraftServer server) {
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(1, eventTicksLeft));
        for (ServerLevel level : server.getAllLevels()) {
            level.setWeatherParameters(0, duration, true, thunder);
        }
    }

    private void clearWeather() {
        MinecraftServer server = server();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.isRaining() || level.isThundering()) {
                level.setWeatherParameters(6000, 0, false, false);
            }
        }
    }

    private MinecraftServer server() {
        MatchOrchestrator match = matchSupplier.get();
        return match == null ? null : match.server();
    }

    private long randomIntervalTicks() {
        int min = ManiacConfigs.get(ConfigSchema.WEATHER_MIN_INTERVAL_SECONDS);
        int max = ManiacConfigs.get(ConfigSchema.WEATHER_MAX_INTERVAL_SECONDS);
        return randomTicks(min, max);
    }

    private long randomDurationTicks() {
        int min = ManiacConfigs.get(ConfigSchema.WEATHER_MIN_DURATION_SECONDS);
        int max = ManiacConfigs.get(ConfigSchema.WEATHER_MAX_DURATION_SECONDS);
        return randomTicks(min, max);
    }

    private long randomTicks(int minSeconds, int maxSeconds) {
        int low = Math.min(minSeconds, maxSeconds);
        int high = Math.max(minSeconds, maxSeconds);
        if (low == high) return low * 20L;
        return (low + random.nextInt(high - low + 1)) * 20L;
    }
}

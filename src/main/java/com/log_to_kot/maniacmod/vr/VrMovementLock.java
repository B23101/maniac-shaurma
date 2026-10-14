package com.log_to_kot.maniacmod.vr;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Серверний замок руху для VR-гравців (room-scale).
 *
 * ── Проблема ─────────────────────────────────────────────────────────
 * Усі блокування руху в моді — на КЛІЄНТІ: міксини {@code KeyboardInput}
 * обнуляють клавіші (повзе, у пастці, маньяк оглушений). У VR гравець
 * пересувається фізично, по кімнаті, і клавіші тут ні до чого: оглушений
 * маньяк, спійманий у пастку чи лежачий виживий міг би просто піти.
 *
 * ── Що робимо ────────────────────────────────────────────────────────
 * Щотіка, ЛИШЕ для VR-гравців ({@link VrServerCompat#isVrPlayer}), дзеркалимо
 * ті самі умови, що й клієнтські міксини, але вже на сервері:
 *  • PIN  — стан CRAWLING, у пастці, оглушений маньяк, а також «заморожені»
 *    фази CINEMATIC / SCATTER / ROLE_REVEAL (бібліотечна заморозка обнуляє лише
 *    швидкість і ретелепортує в ПОТОЧНУ позицію — room-scale вона не зупиняє):
 *    горизонтальний зсув відкочується до останньої прийнятої позиції;
 *  • SLOW — стан UNCONSCIOUS (лежить): може «повзти», тож обмежуємо
 *    швидкість тим самим коефіцієнтом {@code downedCrawlSpeed}, що й
 *    для клавіатурних гравців (атрибут швидкості на room-scale не діє).
 * Вертикаль не чіпаємо (падіння/стрибок), як і ванільну фізику.
 *
 * Не чіпає: пасажирів (переноска/гак — позицією керує сервер), глядачів,
 * креатив, і всіх, хто не у VR. Працює, лише якщо Vivecraft стоїть і на
 * сервері (інакше сервер не знає, хто у VR — див. {@link VrServerCompat}).
 */
@Mod.EventBusSubscriber(modid = ManiacMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VrMovementLock {

    /** Блоків за тік при ванільній швидкості ходьби (0.1 атрибут ≈ 4.317 б/с). */
    private static final double WALK_PER_TICK = 0.2159;
    /** Допуск на дрижання трекінгу/округлення, блоків за тік. */
    private static final double JITTER = 0.03;
    /** Допуск для повного закріплення (PIN): менший за крок будь-якого реального руху. */
    private static final double PIN_EPS = 0.02;
    /**
     * Зсув за тік, більший за це, вважаємо серверним телепортом (розкид у SCATTER,
     * сцена перемоги, гак/пастка) і переякорюємось, а не «відкочуємо». Реальна
     * ходьба/біг далекі від цього. Свідомий компроміс: телепорт-локомоція Vivecraft
     * на 4+ блоків за тік під замком теж пройде.
     */
    private static final double SERVER_TELEPORT_DIST = 4.0;
    private static final int PRUNE_EVERY_TICKS = 200;

    private enum Mode { NONE, PIN, SLOW }

    /** Остання прийнята X/Z-позиція гравця (за UUID). */
    private static final Map<UUID, double[]> LAST = new HashMap<>();
    private static int tick;

    private VrMovementLock() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MatchOrchestrator match = ManiacMod.match();
        if (match == null) { LAST.clear(); return; }

        var players = event.getServer().getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            handle(match, player);
        }
        if (++tick >= PRUNE_EVERY_TICKS) {
            tick = 0;
            LAST.keySet().removeIf(id -> event.getServer().getPlayerList().getPlayer(id) == null);
        }
    }

    private static void handle(MatchOrchestrator match, ServerPlayer player) {
        UUID id = player.getUUID();
        if (player.isSpectator() || player.isCreative() || player.isPassenger()
            || !VrServerCompat.isVrPlayer(player)) {
            LAST.remove(id);
            return;
        }
        Mode mode = modeOf(match, id);
        if (mode == Mode.NONE) {
            LAST.remove(id);
            return;
        }

        double x = player.getX(), z = player.getZ();
        double[] last = LAST.get(id);
        if (last == null) { // перший тік під замком: це і є якір
            LAST.put(id, new double[] { x, z });
            return;
        }

        double dx = x - last[0], dz = z - last[1];
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > SERVER_TELEPORT_DIST) { // це сервер перемістив гравця — не воюємо з ним
            last[0] = x; last[1] = z;
            return;
        }
        double allowed = mode == Mode.PIN
            ? PIN_EPS
            : WALK_PER_TICK * ManiacConfigs.get(ConfigSchema.DOWNED_CRAWL_SPEED) + JITTER;

        if (dist <= allowed) {
            last[0] = x; last[1] = z; // дозволений крок — приймаємо
            return;
        }
        // Занадто далеко: лишаємо рівно стільки, скільки дозволено (PIN — нуль).
        double keep = mode == Mode.PIN ? 0.0 : allowed / dist;
        double nx = last[0] + dx * keep, nz = last[1] + dz * keep;
        last[0] = nx; last[1] = nz;
        player.connection.teleport(nx, player.getY(), nz, player.getYRot(), player.getXRot());
    }

    /** Ті самі умови, що й клієнтські MixinKeyboardInput*, але з серверних джерел. */
    private static Mode modeOf(MatchOrchestrator match, UUID id) {
        GamePhase phase = match.phases().current();
        if (phase == GamePhase.CINEMATIC || phase == GamePhase.SCATTER || phase == GamePhase.ROLE_REVEAL) {
            return Mode.PIN; // фази без правила MOVEMENT: ніхто не рухається
        }
        if (match.isManiac(id)) {
            return match.maniacStun().isStunned(id) ? Mode.PIN : Mode.NONE;
        }
        if (!match.isSurvivor(id)) return Mode.NONE;
        if (match.traps().isTrapped(id)) return Mode.PIN;
        SurvivorState state = match.survivorStateOf(id);
        if (state == SurvivorState.CRAWLING) return Mode.PIN;
        if (state == SurvivorState.UNCONSCIOUS) return Mode.SLOW;
        return Mode.NONE;
    }
}

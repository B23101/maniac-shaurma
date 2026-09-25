package com.log_to_kot.maniacmod.map;

import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.items.FuelCanisterItem;
import com.log_to_kot.maniacmod.map.minigame.ActiveRepairMinigame;
import com.log_to_kot.maniacmod.map.minigame.RepairMinigameType;
import com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec;
import com.log_to_kot.maniacmod.map.minigame.WireMinigameLayout;
import com.log_to_kot.maniacmod.map.zones.GeneratorPoi;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorHighlightPacket;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.GeneratorProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.WireMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorCompletedPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.GeneratorExplosionPacket;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Генератори: сесії ремонту, міні-ігри ремонту, залив бензину, підсвітка.
 *
 * ── Що це замінює з v3 ───────────────────────────────────────────────
 * ManiacGameManager.refreshRepair / tickRepairSessions / doTickRepair /
 * hideRepairProgress / deactivateGenerator — приблизно 120 рядків,
 * вплетених між пастками й боєм. Тепер це самостійний модуль, який
 * можна читати цілком, не гортаючи чужу логіку.
 *
 * ── Сесія ремонту ────────────────────────────────────────────────────
 * Клієнт шле {@code GeneratorRepairHoldPacket} лише на ЗМІНУ стану
 * утримання ПКМ (почав/відпустив) — той самий принцип "hold", що вже
 * застосований для підняття непритомних (RescueHoldPacket), а не
 * щотіковий виклик і не {@code Entity.interact()} (який у ванілі
 * спрацьовує один раз на клік, а не триває, поки кнопка затиснута).
 * {@link #refreshRepair} відкриває сесію, {@link #stopRepair} явно
 * закриває її, коли прийшло {@code holding=false}. Жодного таймауту
 * "від часу останнього дотику" немає — сесія живе, доки не прийде
 * явний stopRepair, або доки гравець не зникне зі списку (дисконект —
 * тоді {@link #tickSessions} сам прибирає осиротілий запис).
 *
 * ── Міні-ігри поверх REPAIR ──────────────────────────────────────────
 * Щотік, для КОЖНОГО гравця з активною RepairSession на стадії REPAIR
 * (і БЕЗ уже активної міні-гри), кидається окремий шанс
 * MINIGAME_TRIGGER_CHANCE_PER_TICK. Випало — саме на цього гравця (не
 * на всіх, хто лагодить той самий генератор) відкривається одна з
 * двох міні-ігор {@link RepairMinigameType} (рівноймовірно), і його
 * REPAIR-сесія позначається "заморожено" — {@link #tickOne} перестає
 * додавати прогрес від його імені, доки міні-гра не завершиться. Решта
 * ж гравців на тому самому генераторі цей час теж НЕ лагодять: поки на
 * генераторі висить чужа міні-гра, ремонт заблоковано для всіх (див.
 * {@link ConfigSchema#MINIGAME_BLOCKS_REPAIR}), бо інакше скілл-чек
 * одного гравця нічого не важив би — генератор протягнули б чужі руки.
 * Їм самим у цей час іде actionbar-повідомлення
 * («тут іде міні-гра»), і лише коли гра скінчиться, їхні сесії
 * продовжують рух прогресу — кожної власне, зі свого місця.
 *
 * Одна активна міні-гра на гравця зберігається в {@link #activeMinigames}
 * окремо від RepairSession — міні-гра прив'язана до гравця, а не до
 * генератора: якщо генератор чомусь зникне під час міні-гри (не має
 * статись у звичайній грі, але захист не завадить), гравець просто
 * програє її на дисконект-подібному шляху, а не зависає з відкритим
 * екраном назавжди.
 *
 * ── Чому Supplier<MatchOrchestrator>, а не Supplier<MatchContext> ────
 * MatchContext навмисно package-private (core.match) — цей модуль
 * живе в іншому пакеті й фізично не може його імпортувати. Увесь
 * доступ до стану матчу йде через вузькі фасадні методи оркестратора
 * ({@code generators()}, {@code generatorAt(pos)}...). Якщо колись
 * знадобиться нове поле стану — метод додається на фасад, а не
 * пробивається окремий імпорт MatchContext.
 */
public final class GeneratorModule implements PhaseListener {

    private final Supplier<MatchOrchestrator> matchSupplier;
    private final Random rng = new Random();

    /** UUID гравця → сесія ремонту. */
    private final Map<UUID, RepairSession> sessions = new HashMap<>();

    /** UUID гравця → активна міні-гра (якщо зараз розбирається з нею). */
    private final Map<UUID, ActiveRepairMinigame> activeMinigames = new HashMap<>();

    /**
     * Звук генераторів: старт, гул роботи, гул заливу. Живе окремим
     * об'єктом, бо розклад озвучення не має нічого спільного з прогресом
     * ремонта — див. {@link GeneratorSoundscape}.
     */
    private final GeneratorSoundscape sounds = new GeneratorSoundscape();

    private long tick = 0;

    public GeneratorModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private static final class RepairSession {
        final BlockPos pos;

        /**
         * Скільки тіків підряд ця сесія вже заливає бензин. Потрібен лише
         * для розкладу дробового темпу (див. {@link #fuelGainForTick}),
         * а НЕ для обліку заряду каністри: заряд лежить у самому
         * {@code ItemStack} і від сесії не залежить. Скидається разом із
         * сесією — це безпечно, бо втрата цього лічильника ніколи не
         * губить заряд, а лише зсуває фазу дробового округлення на один
         * тік.
         */
        int fuelTicks = 0;

        /**
         * Що клієнт бачить зараз (ключ останнього надісланого прогресу),
         * або -1, якщо бар прихований. Пакет шлеться лише при ЗМІНІ
         * ключа: раніше сервер слав його кожен тік кожному, хто
         * лагодить, хоч значення не змінювалось. Клієнтський бар сам
         * не гасне, тож пропуск тіків безпечний.
         */
        int lastSentKey = -1;

        /**
         * Чи гравець зараз ДІЙСНО тримає Shift+ПКМ. Під час міні-гри клієнт
         * скидає своє утримання ЛОКАЛЬНО, не повідомляючи сервер
         * ({@code ClientInputHandler.forceReleaseRepairHold}), тож якщо гравець
         * відпустив кнопку, поки грав, сервер про це не дізнається. Без цього
         * прапорця сесія після міні-гри лишалась би «живою» й додавала прогрес
         * без жодного утримання. Тепер відкриття міні-гри ставить false, а
         * наступний {@code holding=true} із клієнта (кнопку ще тримають) —
         * повертає true.
         */
        boolean holding = true;

        /**
         * Скільки тіків прожила ця сесія (незалежно від пауз) — проти
         * {@link ConfigSchema#MINIGAME_GRACE_TICKS}: доки не набіжить,
         * міні-гра на цю сесію не випадає (див. клас-докстрінг конфіга).
         * Рахується щотік у {@link #tickOne}, а не лише під час holding —
         * пауза не повинна продовжувати grace-період на невизначений час.
         */
        int ageTicks = 0;

        /**
         * Скільки тіків лишилось до кінця cooldown після останньої
         * міні-гри цієї сесії (0 — можна кидати нову). Виставляється в
         * {@link ConfigSchema#MINIGAME_COOLDOWN_TICKS} одразу після
         * succeedMinigame/failMinigame для гравця з цією сесією.
         */
        int minigameCooldownTicks = 0;

        /**
         * Скільки тіків лишилось до наступного повтору повідомлення
         * «на цьому генераторі йде міні-гра». Гасить спам щотіка: гравець,
         * що тримає кнопку біля заблокованого генератора, бачить причину
         * одразу, а далі — раз на {@link #BLOCKED_NOTICE_INTERVAL_TICKS}.
         * Затухає в {@link #tickOne} разом з іншими лічильниками сесії,
         * тож наступна міні-гра не «успадковує» недогарок попередньої.
         */
        int blockedNoticeTicks = 0;

        RepairSession(BlockPos pos) {
            this.pos = pos;
        }
    }

    @Override
    public String id() {
        return "generators";
    }

    // ── Фази ─────────────────────────────────────────────────────────────

    @Override
    public void onPhaseExit(GamePhase phase, List<ServerPlayer> players) {
        // Будь-який вихід з ігрової фази закриває всі сесії. Інакше
        // гравець, що ремонтував у момент завершення матчу, лишався б
        // із висячим прогрес-баром (саме це робив v3).
        if (!phase.isGameplay()) return;
        for (ServerPlayer player : players) {
            hideProgress(player);
            if (activeMinigames.containsKey(player.getUUID())) {
                ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
            }
        }
        sessions.clear();
        activeMinigames.clear();
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        tick++;
        // Вибух генератора тікається ЗАВЖДИ, а не лише коли фаза дозволяє
        // ремонт: інакше вибух, що стався за мить до кінця ремонтної фази,
        // назавжди лишився б червоним, бо його лічильник ніхто не гасить.
        tickGenerators(players);

        if (phase.allows(PhaseRule.GENERATOR_REPAIR)) {
            tickSessions(players);
        } else {
            if (!sessions.isEmpty()) sessions.clear();
            if (!activeMinigames.isEmpty()) {
                // Фаза більше не дозволяє ремонт (наприклад матч
                // завершується) — відкриті міні-ігри самі по собі
                // більше нікому не тікаються нижче, тож закриваємо їх
                // тут-таки без штрафу: провалом це вважати нечесно.
                for (Map.Entry<UUID, ActiveRepairMinigame> entry : activeMinigames.entrySet()) {
                    ServerPlayer player = find(players, entry.getKey());
                    if (player != null) ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
                }
                activeMinigames.clear();
            }
        }

        // Звук — ПІСЛЯ ремонта й залива, і в будь-якій фазі. Після — бо
        // бензин, що пішов у генератор цього тіку, мусить озвучитись цього
        // ж тіку, а не наступного. У будь-якій — бо гул завершеного
        // генератора не має зникати від зміни правила фази.
        sounds.tick(matchSupplier.get().generators(), levelOf(players));
    }

    // ── Ремонт ───────────────────────────────────────────────────────────

    /**
     * Гравець почав тримати ПКМ над генератором — приходить з
     * {@code ServerPacketHandler.onGeneratorRepairHold} при
     * {@code holding=true}. Клієнт шле цей пакет лише один раз на
     * початок утримання (див. {@code ClientInputHandler.handleGeneratorRepair}) —
     * подальший прогрес рахує {@link #tickOne} щотік сам, без потреби
     * в повторних викликах цього методу.
     */
    public void refreshRepair(ServerPlayer player, BlockPos pos) {
        RepairSession existing = sessions.get(player.getUUID());
        if (existing != null && existing.pos.equals(pos)) {
            existing.holding = true;
            return;
        }
        // Гравець переключився на інший генератор (або це перший
        // виклик) — старий бар геть, новий запис на нову позицію.
        if (existing != null) hideProgress(player);
        sessions.put(player.getUUID(), new RepairSession(pos));
    }

    /**
     * Гравець відпустив ПКМ — приходить при {@code holding=false}.
     * Це ЄДИНИЙ шлях явного завершення сесії за нормальної гри (немає
     * жодного таймауту "від часу останнього дотику" — див. клас-докстрінг).
     *
     * Якщо в гравця саме зараз відкрита міні-гра, сесію все одно НЕ
     * прибираємо — вона й далі чекає результату міні-гри (той самий
     * виняток, що вже враховано в {@link #tickSessions}), інакше
     * відпускання ПКМ під час міні-гри (природна річ — руки на екрані
     * міні-гри, а не на кнопці генератора) забрало б у гравця сесію,
     * до якої міні-гра прив'язана.
     */
    public void stopRepair(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (activeMinigames.containsKey(uuid)) return;
        if (sessions.remove(uuid) != null) {
            hideProgress(player);
        }
    }

    private void tickSessions(List<ServerPlayer> players) {
        Iterator<Map.Entry<UUID, RepairSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RepairSession> entry = it.next();
            UUID uuid = entry.getKey();

            ServerPlayer player = find(players, uuid);
            if (player == null) {
                // Гравець зник зі списку (дисконект) — те саме робить
                // MatchOrchestrator.onPlayerLeft окремо для міні-ігор;
                // тут прибираємо саму сесію, щоб не тікати прогрес
                // нікому не потрібного запису.
                activeMinigames.remove(uuid);
                it.remove();
                continue;
            }

            // tickOne ЛИШЕ повідомляє, чи сесія жива; видаляє її тут, через
            // ітератор. Раніше tickOne сам робив sessions.remove(...) просто
            // під час обходу — при двох гравцях на одному генераторі це
            // ConcurrentModificationException у тіку сервера, щойно
            // генератор завершувався.
            if (!tickOne(player, entry.getValue(), players)) {
                it.remove();
            }
        }

        tickMinigameTimeouts(players);
    }

    /**
     * Один тік роботи над генератором.
     *
     * На відміну від v3, генератор НЕ створюється на льоту, якщо його
     * немає в списку: генератори існують тільки там, де їх розмітили.
     *
     * ── Пауза, а не скасування ───────────────────────────────────────
     * Клієнт шле сигнал утримання лише на ЗМІНУ стану, тож якщо сервер
     * скасує сесію, поки гравець і далі тримає кнопку, нового сигналу не
     * буде, доки той не відпустить і не натисне знову. Тому недійсні
     * умови (відійшов, відвернувся, збитий з ніг) лише ставлять ремонт на
     * ПАУЗУ: сесія лишається, бар ховається, прогрес не йде. Повернувся
     * до генератора з тримаючи кнопку — прогрес продовжується сам.
     * Гравець, що втратив роль виживого, і завершений чи зниклий
     * генератор — це справжнє скасування.
     *
     * ── Стадія REPAIR ────────────────────────────────────────────────
     * Утримання Shift+ПКМ додає 1 тік прогресу за тік гри, і рівно за
     * REPAIR_SECONDS_PER_STAGE секунд суцільного утримання набирається
     * 100% (див. {@link GeneratorPoi#addRepairProgress}). Якщо в гравця
     * активна міні-гра — його внесок заморожений (див. клас-докстрінг).
     * Інакше, з імовірністю MINIGAME_TRIGGER_CHANCE_PER_TICK, саме зараз
     * запускається одна з двох міні-ігор на цього гравця.
     *
     * ── Стадія FUEL ──────────────────────────────────────────────────
     * Той самий принцип утримання, але прогрес забирається із заряду
     * каністри в руці. Див. {@link #tickFuel}.
     *
     * @return true, якщо сесію треба залишити; false — прибрати
     */
    private boolean tickOne(ServerPlayer player, RepairSession session, List<ServerPlayer> players) {
        UUID uuid = player.getUUID();
        MatchOrchestrator match = matchSupplier.get();

        if (!match.isSurvivor(uuid)) {
            hideProgress(player);
            return false;
        }

        GeneratorPoi generator = findGenerator(session.pos);
        if (generator == null || generator.isCompleted()) {
            hideProgress(player);
            return false;
        }

        // Grace/cooldown лічильники сесії — незалежно від holding і від
        // того, чи зараз активна міні-гра (див. RepairSession.ageTicks).
        session.ageTicks++;
        if (session.minigameCooldownTicks > 0) session.minigameCooldownTicks--;
        if (session.blockedNoticeTicks > 0) session.blockedNoticeTicks--;

        // Міні-гра заморожує внесок, і сама стежить за дистанцією та станом
        // гравця (tickMinigameTimeouts) — звичайні перевірки тут не потрібні.
        if (activeMinigames.containsKey(uuid)) {
            return true;
        }

        if (!session.holding || !canWork(player, session.pos)) {
            pauseSession(player, session);
            return true;
        }

        // Чиясь міні-гра зупиняє ремонт ЦЬОГО генератора для всіх (див.
        // ConfigSchema#MINIGAME_BLOCKS_REPAIR). Сюди доходить лише той, у
        // кого власної міні-гри немає — свій випадок закритий вище.
        if (ManiacConfigs.get(ConfigSchema.MINIGAME_BLOCKS_REPAIR) && minigameInProgressAt(session.pos)) {
            blockByMinigame(player, session);
            return true;
        }

        if (generator.stage() == GeneratorPoi.Stage.REPAIR) {
            generator.addRepairProgress(1);
            sendProgress(player, session, generator);

            boolean pastGrace = session.ageTicks >= ManiacConfigs.get(ConfigSchema.MINIGAME_GRACE_TICKS);
            boolean pastCooldown = session.minigameCooldownTicks <= 0;

            if (generator.stage() == GeneratorPoi.Stage.REPAIR && pastGrace && pastCooldown
                    && rng.nextDouble() < ManiacConfigs.get(ConfigSchema.MINIGAME_TRIGGER_CHANCE_PER_TICK)) {
                startRandomMinigame(player, generator);
            }
            return true;
        }

        // Stage.FUEL
        tickFuel(player, session, generator, players);
        return true;
    }

    /**
     * Чи може гравець зараз працювати над генератором: живий і не збитий
     * з ніг, достатньо близько й дивиться в його бік.
     *
     * Ці перевірки діють КОЖЕН тік, а не лише при старті: раніше сервер
     * вірив первинному клікові клієнта, і гравець, що почав ремонт, а
     * потім відійшов із затиснутими Shift і ПКМ, продовжував
     * ремонтувати. Пороги — у конфізі (repairMaxDistanceBlocks,
     * repairMaxLookAngleDegrees).
     */
    private boolean canWork(ServerPlayer player, BlockPos pos) {
        SurvivorState state = matchSupplier.get().survivorStateOf(player.getUUID());
        if (state == null || state.isCrawlOnly() || state.isTerminal()) return false;

        // Центр хітбокса 1.2×1.2 сутності генератора.
        Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5);
        Vec3 toGenerator = center.subtract(player.getEyePosition());
        double distance = toGenerator.length();

        double maxDistance = ManiacConfigs.get(ConfigSchema.REPAIR_MAX_DISTANCE_BLOCKS);
        if (distance > maxDistance) return false;

        int maxAngle = ManiacConfigs.get(ConfigSchema.REPAIR_MAX_LOOK_ANGLE_DEGREES);
        // Упритул напрямок на центр стрибає від найменшого руху — кут там
        // безглуздий, тож перевіряємо лише коли генератор не «в обличчя».
        if (maxAngle < 180 && distance > 0.75) {
            double cos = player.getLookAngle().dot(toGenerator) / distance;
            if (cos < Math.cos(Math.toRadians(maxAngle))) return false;
        }
        return true;
    }

    /** Ремонт на паузі: ховаємо бар (один раз), сесію лишаємо. */
    private void pauseSession(ServerPlayer player, RepairSession session) {
        if (session.lastSentKey != -1) {
            hideProgress(player);
            session.lastSentKey = -1;
        }
    }

    /**
     * Як часто (у тіках) нагадувати гравцю, що ремонт стоїть через чужу
     * міні-гру: перший раз — одразу, далі раз на 2 секунди. Рідше —
     * гравець не зрозуміє, чому прогрес не рухається; частіше —
     * перетвориться на миготіння в actionbar.
     */
    private static final int BLOCKED_NOTICE_INTERVAL_TICKS = 40;

    /**
     * Чи йде ЗАРАЗ міні-гра на цьому генераторі (неважливо, на кого саме).
     *
     * Міні-гри зберігаються за ГРАВЦЕМ, а не за генератором (див.
     * {@link #activeMinigames}), тож це зворотний пошук. Мапа мала —
     * максимум один запис на гравця, — тож обходити її щотіка дешевше,
     * ніж тримати окремий індекс «генератор → міні-гра», який довелося б
     * синхронізувати в кожному місці старту/кінця гри.
     */
    private boolean minigameInProgressAt(BlockPos pos) {
        for (ActiveRepairMinigame minigame : activeMinigames.values()) {
            if (minigame.generatorPos().equals(pos)) return true;
        }
        return false;
    }

    /**
     * Ремонт на паузі через чужу міні-гру на тому самому генераторі.
     *
     * Сесію не скасовуємо — лише пауза, як і для відходу від генератора:
     * гравець може тримати кнопку, поки інший розбирається зі скілл-чеком,
     * і прогрес продовжиться сам, щойно гра скінчиться. Нового сигналу від
     * клієнта для цього не потрібно, а якби ми видалили сесію, гравець із
     * затиснутою кнопкою просто застиг би без прогресу до наступного
     * натискання.
     */
    private void blockByMinigame(ServerPlayer player, RepairSession session) {
        pauseSession(player, session);
        if (session.blockedNoticeTicks > 0) return;
        session.blockedNoticeTicks = BLOCKED_NOTICE_INTERVAL_TICKS;
        ModNetwork.toPlayer(player, new ActionBarPacket(
            ActionBarMessageType.COOLDOWN, "maniacmod.generator.minigame_busy"));
    }

    /**
     * Один тік заливки: бере каністру з руки, переносить частину її
     * заряду в генератор і зменшує заряд рівно на те, що генератор
     * реально прийняв.
     *
     * ── Чому читаємо руку гравця тут, а не в пакеті ────────────────────
     * {@code GeneratorRepairHoldPacket} лишається тим самим "почав/
     * закінчив тримати" сигналом і для FUEL, без окремого поля про
     * предмет — сервер сам звіряє поточний вміст руки щотік. Це означає, що гравець може почати тримати Shift+ПКМ
     * БЕЗ каністри в руці (сесія все одно відкриється через
     * refreshRepair), просто прогрес не піде, доки заряджена каністра
     * не з'явиться в руці — практично це дозволяє взяти іншу каністру
     * ПІД ЧАС утримання, не відпускаючи кнопки.
     *
     * ── Чому списуємо "прийняте", а не "хотіли злити" ────────────────
     * {@code generator.addFuel} сам затискає підсумок до
     * fuelRequiredPercent() (200%), тож на останньому тіку генератор
     * може взяти менше, ніж ми пропонували. Якби ми списували
     * пропоноване, із каністри зникав би заряд, який нікуди не потрапив.
     * Тому різниця fuelPercent() до/після — єдине джерело істини для
     * списання, а Math.min із залишком заряду не дає злити більше, ніж
     * у каністрі є.
     *
     * ── Чому жодного shrink ─────────────────────────────────────────────
     * Каністра постійна: порожня (0%) вона просто перестає давати
     * прогрес, а не зникає.
     */
    private void tickFuel(ServerPlayer player, RepairSession session, GeneratorPoi generator,
                           List<ServerPlayer> players) {
        ItemStack canister = canisterInHand(player);
        if (canister == null) {
            pauseSession(player, session);
            session.fuelTicks = 0;
            return;
        }

        int charge = FuelCanisterItem.getCharge(canister);
        if (charge <= 0) {
            // Порожня каністра — те саме, що відсутня: нічого лити.
            pauseSession(player, session);
            session.fuelTicks = 0;
            return;
        }

        int offered = Math.min(fuelGainForTick(session.fuelTicks++), charge);
        int before = generator.fuelPercent();
        boolean justCompleted = generator.addFuel(offered);
        int accepted = generator.fuelPercent() - before;

        if (accepted > 0) {
            FuelCanisterItem.setCharge(canister, charge - accepted);
        }

        sendProgress(player, session, generator);

        if (accepted > 0) {
            // Саме прийнятий бензин, а не факт утримання кнопки: у порожній
            // каністрі або на вже повному баку звучати нічому.
            sounds.fuelPoured(generator);
        }

        if (justCompleted) {
            sounds.completed(generator, levelOf(players));
            onGeneratorCompleted(generator, players);
        }
    }

    // TODO(майбутнє розширення): SCREWDRIVER_SPEED_BONUS у конфізі
    // зарезервований під бонус швидкості від інструмента-викрутки в руці
    // гравця (ScrewdriverItem — поки що незареєстрований предмет). Коли він
    // з'явиться, множник піде в аргумент GeneratorPoi.addRepairProgress(ticks);
    // наразі швидкість завжди базова.

    /**
     * Скільки % бензину переноситься на {@code n}-му (з нуля) тіку
     * безперервної заливки при темпі FUEL_PERCENT_PER_SECOND.
     *
     * Темп задано у відсотках за СЕКУНДУ, а тіків у секунді 20, тож на
     * тік випадає дробове число (2%/с → 0.1%/тік), тоді як і прогрес
     * генератора, і заряд каністри — цілі відсотки. Замість накопичувача
     * дробової частини (ще один стан, що міг би розсинхронізуватись)
     * використовується різниця двох округлених у нижчий бік накопичених
     * сум: {@code floor(rate*(n+1)/20) - floor(rate*n/20)}. Кожне окреме
     * значення — 0 або більше, а за будь-які 20 підряд тіків сума рівно
     * дорівнює {@code rate}. Тобто темп точний, без систематичного
     * "вгору" чи "вниз", яке давало б ceil()/floor() на кожен тік.
     */
    static int fuelGainForTick(int n) {
        int rate = ManiacConfigs.get(ConfigSchema.FUEL_PERCENT_PER_SECOND);
        long after = (long) rate * (n + 1) / 20;
        long before = (long) rate * n / 20;
        return (int) (after - before);
    }

    /**
     * Стек каністри в руці гравця або {@code null}, якщо в жодній руці
     * її немає. Перевага — ЗАРЯДЖЕНІЙ: якщо в основній руці порожня
     * каністра, а в офф-хенді заряджена, береться заряджена (інакше
     * гравець із двома каністрами стояв би без прогресу через порожню в
     * "не тій" руці). Якщо заряджених немає, повертається будь-яка
     * знайдена (порожня) — {@link #tickFuel} сам розпізнає її за нульовим
     * зарядом і покаже гравцеві відповідну підказку.
     *
     * Повертаємо САМЕ живий стек із інвентаря, а не копію: заряд
     * пишеться прямо в його NBT ({@code FuelCanisterItem.setCharge}), тож
     * зміна одразу видна гравцеві й потрапляє на збереження та в клієнтську
     * синхронізацію слота.
     */
    private static ItemStack canisterInHand(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean mainIsCanister = main.getItem() instanceof FuelCanisterItem;
        boolean offIsCanister = off.getItem() instanceof FuelCanisterItem;

        if (mainIsCanister && !FuelCanisterItem.isEmpty(main)) return main;
        if (offIsCanister && !FuelCanisterItem.isEmpty(off)) return off;
        if (mainIsCanister) return main;
        if (offIsCanister) return off;
        return null;
    }

    /**
     * Раз на тік для кожного генератора: гасить лічильник вибуху та, поки
     * він триває, підтримує на генераторі вогонь і дим.
     */
    private void tickGenerators(List<ServerPlayer> players) {
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            boolean wasExploding = generator.explosionTicksLeft() > 0;
            generator.tick();
            if (!wasExploding) continue;

            // Вогонь і дим — раз на 3 тіки: щільно, але без пакетного шторму.
            if (tick % 3 == 0 && generator.explosionTicksLeft() > 0) {
                ServerLevel level = levelOf(players);
                if (level != null) {
                    Vec3 at = centerOf(generator.pos());
                    burst(players, level, ParticleTypes.FLAME, at, 8, 0.35, 0.02);
                    burst(players, level, ParticleTypes.LARGE_SMOKE, at, 4, 0.30, 0.02);
                }
            }
        }
    }

    // ── Міні-ігри ────────────────────────────────────────────────────────

    /** Обирає одну з двох міні-ігор рівноймовірно й відкриває її гравцю. */
    private void startRandomMinigame(ServerPlayer player, GeneratorPoi generator) {
        // Клієнт скине утримання локально, коли відкриє екран (див.
        // RepairSession.holding) — сервер вважає його відпущеним до нового сигналу.
        RepairSession session = sessions.get(player.getUUID());
        if (session != null) session.holding = false;

        RepairMinigameType type = rng.nextBoolean() ? RepairMinigameType.WIRES : RepairMinigameType.TARGET;
        if (type == RepairMinigameType.TARGET) {
            startTargetMinigame(player, generator);
        } else {
            startWireMinigame(player, generator);
        }

        announceMinigameStarted(player, generator);
    }

    /**
     * Повідомляє РЕШТУ виживих, що на генераторі почалась міні-гра й ремонт
     * цього генератора тимчасово заблоковано.
     *
     * ── Кому саме ─────────────────────────────────────────────────────
     * Той, хто грає, пакет не отримує — він і без того бачить екран
     * міні-гри. Маньяку — тим більше: скілл-чек виживих це внутрішня
     * справа команди, а не безкоштовна підказка «біжи до цього
     * генератора». Тому явна перевірка ролі, а не розсилка «всім».
     *
     * Коли блокування вимкнено в конфізі, повідомлення не шлеться взагалі:
     * інформувати нема про що — решта й так продовжує лагодити.
     */
    private void announceMinigameStarted(ServerPlayer player, GeneratorPoi generator) {
        if (!ManiacConfigs.get(ConfigSchema.MINIGAME_BLOCKS_REPAIR)) return;

        ActionBarPacket packet = new ActionBarPacket(ActionBarMessageType.INFO,
            "maniacmod.generator.minigame_started",
            new String[] { player.getName().getString() });

        for (ServerPlayer other : matchSupplier.get().onlinePlayers()) {
            if (other.getUUID().equals(player.getUUID())) continue;
            if (!matchSupplier.get().isSurvivor(other.getUUID())) continue;
            ModNetwork.toPlayer(other, packet);
        }
    }

    private void startTargetMinigame(ServerPlayer player, GeneratorPoi generator) {
        double cursorSpeed = ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_CURSOR_SPEED);
        double hitZoneWidth = ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_HIT_ZONE_WIDTH);
        int hitsRequired = ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_HITS_REQUIRED);
        // Ціль — не по центру й не впритул до країв, інакше повзунок
        // застрягав би в куті на розвороті замість пробігати повз неї.
        double targetPosition = 0.15 + rng.nextDouble() * 0.70;
        long seed = rng.nextLong();

        TargetMinigameSpec spec = new TargetMinigameSpec(
            seed, cursorSpeed, hitZoneWidth, targetPosition, hitsRequired);
        activeMinigames.put(player.getUUID(), new ActiveRepairMinigame(generator.pos(), spec));

        ModNetwork.toPlayer(player, new TargetMinigameOpenPacket(
            generator.pos(), seed, cursorSpeed, hitZoneWidth, targetPosition, hitsRequired));
    }

    private void startWireMinigame(ServerPlayer player, GeneratorPoi generator) {
        WireMinigameLayout layout = WireMinigameLayout.random(rng);
        activeMinigames.put(player.getUUID(), new ActiveRepairMinigame(generator.pos(), layout));

        int[] initial = new int[layout.slotCount()];
        for (int i = 0; i < initial.length; i++) initial[i] = layout.initialRightSlotForLeft(i);

        ModNetwork.toPlayer(player, new WireMinigameOpenPacket(
            generator.pos(), initial, ManiacConfigs.get(ConfigSchema.WIRE_MINIGAME_TICKS)));
    }

    /** Максимальна дистанція (у блоках) від генератора під час міні-гри — далі вважається втечею/провалом. */
    private static final double MINIGAME_MAX_DISTANCE_SQ = 6.0 * 6.0;

    /**
     * Раз на тік для кожної активної міні-гри:
     *  • генератор зник або вже не в стадії REPAIR — гру закрито БЕЗ штрафу
     *    (гравець нічим не винен, що інший добив ремонт);
     *  • гравець збитий з ніг чи вибув — те саме, без штрафу: маньяк, що
     *    вдарив, не повинен ще й «вибухати» генератор команді;
     *  • втік далі за MINIGAME_MAX_DISTANCE — провал, як і раніше (не можна
     *    «просто вийти» з екрана);
     *  • WIRES не вклався в час — провал.
     */
    private void tickMinigameTimeouts(List<ServerPlayer> players) {
        Iterator<Map.Entry<UUID, ActiveRepairMinigame>> it = activeMinigames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveRepairMinigame> entry = it.next();
            ActiveRepairMinigame minigame = entry.getValue();
            ServerPlayer player = find(players, entry.getKey());

            GeneratorPoi generator = findGenerator(minigame.generatorPos());
            boolean generatorGone = generator == null
                || generator.stage() != GeneratorPoi.Stage.REPAIR;
            SurvivorState state = matchSupplier.get().survivorStateOf(entry.getKey());
            boolean playerOut = state == null || state.isCrawlOnly() || state.isTerminal();

            if (generatorGone || playerOut) {
                it.remove();
                if (player != null) ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
                continue;
            }

            if (player != null && player.blockPosition().distSqr(minigame.generatorPos()) > MINIGAME_MAX_DISTANCE_SQ) {
                it.remove();
                failMinigame(player, minigame);
                continue;
            }

            if (minigame.type() != RepairMinigameType.WIRES) continue;
            if (!minigame.tickAndCheckTimeout()) continue;

            it.remove();
            failMinigame(player, minigame);
        }
    }

    /**
     * Клік у міні-грі "ціль" — приходить із {@code ServerPacketHandler}.
     * Мовчки ігнорує клік без активної TARGET-міні-гри в цього гравця
     * (модифікований/застарілий клієнт чи запізнілий пакет після вже
     * завершеної гри).
     *
     * @return true, якщо клік належав цій міні-грі — викликач тоді не
     *         пробує інші міні-гри (у капкана свій обробник кліку з тим
     *         самим пакетом)
     */
    public boolean onTargetMinigameClick(ServerPlayer player, double cursorPosition) {
        ActiveRepairMinigame minigame = activeMinigames.get(player.getUUID());
        if (minigame == null || minigame.type() != RepairMinigameType.TARGET) return false;

        // Позицію з пакета сервер НЕ приймає на віру — перевіряє її проти
        // власної траєкторії повзунка з допуском на затримку мережі.
        ActiveRepairMinigame.Result result = minigame.registerTargetClick(
            cursorPosition, ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_LAG_TOLERANCE_MS));
        switch (result) {
            case SUCCESS -> {
                activeMinigames.remove(player.getUUID());
                succeedMinigame(player);
            }
            case HIT_PROGRESS -> ModNetwork.toPlayer(player,
                RepairMinigameProgressPacket.attempt(minigame.targetHits()));
            case FAIL -> {
                activeMinigames.remove(player.getUUID());
                failMinigame(player, minigame);
            }
            default -> { /* WIRE_CONNECTED неможливий для TARGET-типу */ }
        }
        return true;
    }

    /**
     * Закриває міні-гру цього гравця БЕЗ наслідків для генератора —
     * потрібно, коли екран міні-гри має поступитися іншому (жертву
     * капкана ловить СВОЯ міні-гра визволення, а клієнт тримає лише один
     * екран).
     *
     * ── Чому саме «без наслідків», а не {@link #failMinigame} ───────
     * {@code failMinigame} — це провал гравця: вибух генератора й знятий
     * прогрес. Тут гравець нічим не винен: він порався з генератором, а
     * потім наступив у капкан. Той самий вибір, що в
     * {@code tickMinigameTimeouts} для збитого з ніг.
     *
     * Провалу міні-гри тут не потрібно ще й з другої причини: клієнт
     * відкриє екран капкана тим самим пакетом {@code TargetMinigameOpenPacket},
     * і будь-який пізніший результат генератора закрив би вже ЙОГО.
     *
     * @return true, якщо міні-гра справді була й закрита
     */
    public boolean abandonMinigame(ServerPlayer player) {
        if (activeMinigames.remove(player.getUUID()) == null) return false;
        ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
        return true;
    }

    /**
     * Дріт перетягнуто в міні-грі "дроти" — приходить із
     * {@code ServerPacketHandler}. Індекси поза 0..3 ігноруються мовчки
     * (модифікований клієнт).
     */
    public void onWireMinigameDrop(ServerPlayer player, int leftSlot, int rightSlot) {
        ActiveRepairMinigame minigame = activeMinigames.get(player.getUUID());
        if (minigame == null || minigame.type() != RepairMinigameType.WIRES) return;
        if (leftSlot < 0 || leftSlot >= WireMinigameLayout.SLOT_COUNT) return;
        if (rightSlot < 0 || rightSlot >= WireMinigameLayout.SLOT_COUNT) return;

        ActiveRepairMinigame.Result result = minigame.registerWireDrop(leftSlot, rightSlot);
        switch (result) {
            case SUCCESS -> {
                activeMinigames.remove(player.getUUID());
                succeedMinigame(player);
            }
            case WIRE_CONNECTED -> ModNetwork.toPlayer(player,
                RepairMinigameProgressPacket.wireConnected(leftSlot, rightSlot));
            case FAIL -> {
                activeMinigames.remove(player.getUUID());
                failMinigame(player, minigame);
            }
            default -> { /* HIT_PROGRESS неможливий для WIRES-типу */ }
        }
    }

    /**
     * Гравець покинув матч/від'єднався, поки в нього була відкрита
     * міні-гра — трактується як провал (див. клас-докстрінг). Викликач
     * (місце обробки виходу гравця з матчу) сам вирішує, коли саме це
     * викликати; тут лише сама реакція.
     */
    public void onPlayerLeftDuringMinigame(UUID uuid) {
        ActiveRepairMinigame minigame = activeMinigames.remove(uuid);
        if (minigame == null) return;
        applyMinigameFailure(minigame);
        // Гравця вже немає на сервері — надсилати йому пакет нема кому,
        // достатньо застосувати штраф до генератора.
    }

    private void succeedMinigame(ServerPlayer player) {
        ModNetwork.toPlayer(player, new RepairMinigameResultPacket(true));
        armMinigameCooldown(player);
        // Успіх нічого не додає до repairPercent сам по собі — гравець
        // просто повертається до звичайного утримання ПКМ на
        // наступному тіку (сесія й без того лишалась активною).
    }

    private void failMinigame(ServerPlayer player, ActiveRepairMinigame minigame) {
        if (player != null) {
            ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
            armMinigameCooldown(player);
        }
        applyMinigameFailure(minigame);
    }

    /** Виставляє cooldown на сесію ремонту гравця після завершеної міні-гри (успіх чи провал). */
    private void armMinigameCooldown(ServerPlayer player) {
        RepairSession session = sessions.get(player.getUUID());
        if (session != null) session.minigameCooldownTicks = ManiacConfigs.get(ConfigSchema.MINIGAME_COOLDOWN_TICKS);
    }

    /**
     * Наслідок проваленої міні-гри: ВИБУХ генератора. Знімає частку
     * спільного прогресу ремонту ({@code minigameFailLossPercent}, за
     * замовчуванням 10%) і показує вибух УСІМ: партикли й звук на самому
     * генераторі, червоний контур сутності та червоний екранний маркер на
     * {@code failFlashTicks} (за замовчуванням 5 с) — для виживих, маньяка
     * і глядачів однаково. Бензин не чіпається.
     */
    private void applyMinigameFailure(ActiveRepairMinigame minigame) {
        GeneratorPoi generator = findGenerator(minigame.generatorPos());
        if (generator == null) return;
        if (!generator.explode(ManiacConfigs.get(ConfigSchema.MINIGAME_FAIL_LOSS_PERCENT))) return;

        List<ServerPlayer> everyone = matchSupplier.get().onlinePlayers();
        ModNetwork.toPlayers(everyone, new GeneratorExplosionPacket(
            generator.pos(), generator.explosionTicksLeft()));

        ServerLevel level = levelOf(everyone);
        if (level == null) return;
        Vec3 at = centerOf(generator.pos());
        burst(everyone, level, ParticleTypes.EXPLOSION_EMITTER, at, 1, 0.0, 0.0);
        burst(everyone, level, ParticleTypes.FLAME, at, 40, 0.6, 0.08);
        burst(everyone, level, ParticleTypes.LARGE_SMOKE, at, 20, 0.5, 0.05);
        burst(everyone, level, ParticleTypes.LAVA, at, 12, 0.5, 0.0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE,
            SoundSource.BLOCKS, 4.0f, 0.8f);
    }

    /**
     * Шле партикли всім гравцям того самого виміру НА ДАЛЕКУ ДИСТАНЦІЮ
     * (longDistance = до 512 блоків замість ванільних 32). Звичайний
     * {@code ServerLevel.sendParticles} без гравця бачить лише ближнє коло.
     */
    private static void burst(List<ServerPlayer> players, ServerLevel level, ParticleOptions particle,
                              Vec3 at, int count, double spread, double speed) {
        for (ServerPlayer player : players) {
            if (player.serverLevel() != level) continue;
            level.sendParticles(player, particle, true, at.x, at.y, at.z,
                count, spread, spread, spread, speed);
        }
    }

    /** Вимір, у якому йде матч, — за першим гравцем (матч триває в одному вимірі). */
    private static ServerLevel levelOf(List<ServerPlayer> players) {
        return players.isEmpty() ? null : players.get(0).serverLevel();
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5);
    }

    // ── Підсвітка ────────────────────────────────────────────────────────

    /**
     * Відповідь на клавішу 5. Один пакет зі станами всіх генераторів;
     * клієнт сам гасить підсвітку через HIGHLIGHT_DURATION_TICKS.
     *
     * ── Приватність ──────────────────────────────────────────────────
     * Пакет іде ЛИШЕ гравцю, що натиснув 5 (не {@code toPlayers}), а
     * малює його суто клієнтський рендер. Ванільне світіння сутності
     * ({@code setGlowingTag}) тут свідомо НЕ використовується: воно
     * видиме всім гравцям, включно з маньяком.
     *
     * ── Кольори (рахує сервер, клієнт лише малює) ────────────────────
     *   DONE        зелений — генератор повністю полагоджено
     *   IN_PROGRESS жовтий  — ЗАРАЗ хтось лагодить, заливає бензин або
     *                          розбирається з міні-грою (на цей час ремонт
     *                          заблоковано для решти)
     *   FAILED      червоний — щойно вибухнув (лишається як є)
     *   IDLE        білий   — не полагоджений, ніхто не працює
     *
     * Жовтий береться не з {@link GeneratorPoi#visualState()}: той
     * ставиться при БУДЬ-ЯКОМУ прогресі й не гасне, тож недоремонтований
     * генератор, від якого всі пішли, лишався б жовтим назавжди. Тут
     * жовтий означає саме «працюють прямо зараз» — його знають лише
     * активні сесії цього модуля.
     */
    public void sendHighlight(ServerPlayer player) {
        java.util.Set<BlockPos> busy = new java.util.HashSet<>();
        for (Map.Entry<UUID, RepairSession> entry : sessions.entrySet()) {
            if (isActivelyWorking(entry.getKey(), entry.getValue())) {
                busy.add(entry.getValue().pos);
            }
        }
        // Генератор, на якому ЗАРАЗ іде міні-гра, теж «зайнятий»: гравець
        // фізично поруч і саме ним займається, просто внесок заморожений,
        // доки гра не скінчиться. Без цього підсвітка була б БІЛОЮ («ніхто
        // не працює») саме тоді, коли ремонт стоїть через чужу міні-гру.
        for (ActiveRepairMinigame minigame : activeMinigames.values()) {
            busy.add(minigame.generatorPos());
        }

        List<GeneratorHighlightPacket.Entry> entries = new ArrayList<>();
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            entries.add(new GeneratorHighlightPacket.Entry(
                generator.pos(), highlightStateOf(generator, busy.contains(generator.pos()))));
        }
        ModNetwork.toPlayer(player, new GeneratorHighlightPacket(
            ManiacConfigs.get(ConfigSchema.HIGHLIGHT_DURATION_TICKS), entries));
    }

    /**
     * Чи гравець ПРЯМО ЗАРАЗ рухає ремонт/залив цього генератора.
     * Сесія, що існує, але на паузі (відійшов, відвернувся, нема
     * каністри), — не «працює»; так само й сесія під час міні-гри
     * (внесок заморожений). Це майже той самий набір умов, що в
     * {@link #tickOne}, тільки без побічних ефектів: свідомо БЕЗ
     * перевірки {@link ConfigSchema#MINIGAME_BLOCKS_REPAIR} — гравець, що
     * упирається в заблокований міні-грою генератор, усе одно ЗАЙНЯТИЙ
     * ним просто зараз, а саме це жовтий і означає.
     */
    private boolean isActivelyWorking(UUID uuid, RepairSession session) {
        if (!session.holding) return false;
        if (activeMinigames.containsKey(uuid)) return false;
        if (!matchSupplier.get().isSurvivor(uuid)) return false;
        ServerPlayer player = matchSupplier.get().onlinePlayer(uuid);
        return player != null && canWork(player, session.pos);
    }

    /** Чиста функція вибору кольору — окремо, щоб пріоритети було видно в одному місці. */
    static GeneratorPoi.VisualState highlightStateOf(GeneratorPoi generator, boolean someoneWorking) {
        if (generator.isCompleted()) return GeneratorPoi.VisualState.DONE;
        // Вибух важливіший за «працюють»: інакше червоний зникав би, щойно
        // інший гравець далі лагодить той самий генератор.
        if (generator.explosionTicksLeft() > 0) return GeneratorPoi.VisualState.FAILED;
        if (someoneWorking) return GeneratorPoi.VisualState.IN_PROGRESS;
        return GeneratorPoi.VisualState.IDLE;
    }

    // ── Заливка бензину ──────────────────────────────────────────────────

    /**
     * Генератор щойно повністю завершено (обидві стадії) — розсилає
     * {@link GeneratorCompletedPacket} УСІМ виживим одночасно, не лише
     * тому, хто заливав останню каністру.
     *
     * ── Що таке "N з M" ───────────────────────────────────────────────
     * N — КІЛЬКІСТЬ уже завершених генераторів (включно з цим), а не
     * номер точки в списку. Раніше тут був номер у списку, тож перший
     * готовий генератор міг показати «Генератор 4 з 5», а бонусний —
     * навіть «6 з 5». M — GENERATORS_REQUIRED, тобто скільки треба
     * полагодити для виходу, а не скільки точок розмічено (з бонусними
     * їх більше). Бонусні понад норму не виводять N за межі M.
     */
    private void onGeneratorCompleted(GeneratorPoi completed, List<ServerPlayer> players) {
        int done = 0;
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            if (generator.isCompleted()) done++;
        }
        int total = ManiacConfigs.get(ConfigSchema.GENERATORS_REQUIRED);

        GeneratorCompletedPacket packet = new GeneratorCompletedPacket(Math.min(done, total), total);
        for (ServerPlayer player : players) {
            if (matchSupplier.get().isSurvivor(player.getUUID())) {
                ModNetwork.toPlayer(player, packet);
            }
        }
    }

    // ── Допоміжне ────────────────────────────────────────────────────────

    public GeneratorPoi findGenerator(BlockPos pos) {
        return matchSupplier.get().generatorAt(pos);
    }

    /**
     * Дебаг: миттєво завершує УСІ ще не готові генератори матчу — для
     * {@code /maniac generators complete}.
     *
     * ── Чому не просто цикл {@code forceComplete()} ────────────────────
     * Генератор, який хтось ЛАГОДИТЬ прямо зараз, має активну
     * {@link RepairSession} із прогрес-баром на екрані того гравця.
     * Просто змінивши {@code GeneratorPoi} під його ногами, ми лишили б
     * клієнта з баром, що вже не відповідає жодній реальній стадії
     * (сесія й далі "жива", але сервер більше нічого в неї не додасть —
     * бар просто застиг би). Тому спершу гасимо прогрес усім, хто зараз
     * лагодить чи заливає ЦЕЙ конкретний генератор, так само, як робить
     * {@link #onPhaseExit} при виході з ігрової фази — лише вибірково,
     * по конкретних uuid, а не по всіх сесіях одразу (гравці, що
     * лагодять генератор, який і без того вже DONE, свою сесію не
     * втрачають — forceComplete на завершений генератор і так no-op).
     *
     * ── Один сумарний пакет, а не по одному на генератор ───────────────
     * {@link #onGeneratorCompleted} рахує "N з M" наново щоразу — при
     * команді, що завершує кілька генераторів одним викликом, проміжні
     * "3 з 5", "4 з 5" гравець побачив би лише як миготіння. Тут рахунок
     * і розсилка йдуть один раз, ПІСЛЯ того як усі позначені готовими.
     *
     * @return скільки генераторів щойно завершено цим викликом (0, якщо
     *         усі вже були готові — команда відпрацювала, але новин нема).
     */
    public int completeAllGenerators(List<ServerPlayer> players) {
        int justCompleted = 0;
        for (GeneratorPoi generator : matchSupplier.get().generators()) {
            if (generator.isCompleted()) continue;

            for (Iterator<Map.Entry<UUID, RepairSession>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, RepairSession> entry = it.next();
                if (!entry.getValue().pos.equals(generator.pos())) continue;
                ServerPlayer player = find(players, entry.getKey());
                if (player != null) hideProgress(player);
                activeMinigames.remove(entry.getKey());
                it.remove();
            }

            if (generator.forceComplete()) {
                justCompleted++;
                sounds.completed(generator, levelOf(players));
            }
        }

        if (justCompleted > 0) {
            onGeneratorCompleted(null, players);
        }
        return justCompleted;
    }

    /**
     * Шле клієнтові прогрес, лише якщо він змінився з минулого разу
     * (див. {@link RepairSession#lastSentKey}). Для FUEL довжина бара —
     * це частка від fuelRequiredPercent, порахована ТУТ: клієнт не знає
     * цього налаштування й раніше ділив на жорстке 2.
     */
    private void sendProgress(ServerPlayer player, RepairSession session, GeneratorPoi generator) {
        boolean repairStage = generator.stage() == GeneratorPoi.Stage.REPAIR;
        int fuelRequired = Math.max(1, ManiacConfigs.get(ConfigSchema.FUEL_REQUIRED_PERCENT));
        int stagePercent = repairStage
            ? generator.repairPercent()
            : Math.min(100, generator.fuelPercent() * 100 / fuelRequired);

        int key = repairStage ? stagePercent : 1000 + generator.fuelPercent();
        if (key == session.lastSentKey) return;
        session.lastSentKey = key;

        ModNetwork.toPlayer(player, new GeneratorProgressPacket(
            true,
            repairStage ? 0 : 1,
            stagePercent,
            repairStage ? 0 : 1,   // стадій пройдено
            2,                     // стадій усього: REPAIR, FUEL
            generator.fuelPercent()));
    }

    private void hideProgress(ServerPlayer player) {
        ModNetwork.toPlayer(player, GeneratorProgressPacket.hidden());
    }

    private static ServerPlayer find(List<ServerPlayer> players, UUID uuid) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) return player;
        }
        return null;
    }
}

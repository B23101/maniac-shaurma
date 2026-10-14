package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.vr.VrServerCompat;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
import com.log_to_kot.maniacmod.entity.RopeTrapEntity;
import com.log_to_kot.maniacmod.entity.SmokeMineEntity;
import com.log_to_kot.maniacmod.entity.SpikeTrapEntity;
import com.log_to_kot.maniacmod.items.GeoScissorsItem;
import com.log_to_kot.maniacmod.maniacs.ManiacArchetype;
import com.log_to_kot.maniacmod.map.minigame.TargetMinigameSpec;
import com.log_to_kot.maniacmod.net.ModNetwork;
import com.log_to_kot.maniacmod.net.s2c.actionprogress.AbilityCooldownPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameProgressPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.RepairMinigameResultPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TargetMinigameOpenPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.ActionBarPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.TrapEscapePromptPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.TrapVictimGlowPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.SmokeMufflePacket;
import com.log_to_kot.maniacmod.net.s2c.notify.SpikeHitPacket;
import com.log_to_kot.maniacmod.net.s2c.notify.WireShockPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TrapEscapeAttemptPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapCatalogPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket;
import com.log_to_kot.maniacmod.registry.ModParticles;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.forge.stamina.StaminaService;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import dev.shaurmalib.common.lock.LockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import com.log_to_kot.maniacmod.offline.ManiacOffline;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Пастки маньяка: розміщення, спрацювання, звільнення.
 *
 * ── Один модуль на ВСІ пастки ────────────────────────────────────────
 * Життєвий цикл однаковий для будь-якої пастки (див.
 * {@link TrapArchetype}), тому він живе тут ОДИН раз. Конкретна пастка
 * додає лише «де ставити / що з'являється / що робить із жертвою».
 * Сутність-тіло ({@link BearTrapEntity}) не тримає ігрового стану —
 * лише показує модель і питає модуль у свій тік.
 *
 * ── Що модуль тримає ─────────────────────────────────────────────────
 *   • {@link #placed}      — усі пастки матчу (для тіку й очищення);
 *   • {@link #victims}     — хто зараз у пастці і в якій;
 *   • {@link #slotCooldown} — перезарядка розміщення по слотах;
 *   • {@link #immunity}    — короткий імунітет жертви після звільнення;
 *   • {@link #smokeClouds} — хмари димових мін (див. секцію «Димова міна»).
 * Усе це стирається на виході з ігрових фаз. Сама сутність прибирається
 * {@code MatchRuntimeRegistry.cleanup}, тому світ не засмічується.
 *
 * ── Довіра клієнту ───────────────────────────────────────────────────
 * Клієнт надсилає лише «слот N, підтверджую». Куди дивиться маньяк,
 * дальність, фазу, кулдаун і валідність місця сервер перераховує сам.
 *
 * ── Жертва й ЛКМ ─────────────────────────────────────────────────────
 * Жертві ставиться лише {@link LockType#MOVEMENT}. {@code ATTACK}
 * навмисно НЕ ставиться: звільнення — це ЛКМ ломом по капкану, і
 * заблокований удар зробив би пастку безвихідною.
 *
 * ── Саморятунок: міні-гра «попади в ціль» ─────────────────────────────
 * У момент захлопування жертві відкривається міні-гра
 * {@link TrapEscapeMinigame}: набрати {@code trapEscapeHitsRequired}
 * влучань (5), промах знімає {@code trapEscapeMissPenalty} (2), на всю
 * спробу — {@code trapEscapeTicks}. Встиг — вибрався сам (капкан
 * розкривається й зникає, як після удару ломом); не встиг — лишається
 * в капкані й чекає на товариша. Спроба одна на захлопування.
 *
 * ── Кого пастка НЕ чіпає ─────────────────────────────────────────────
 * Лежачих (CRAWLING/UNCONSCIOUS) і термінальних — див.
 * {@link #canCatch}. Перевірка стоїть ОДНИМ місцем на всі типи пасток
 * ({@link #tickTrap}), а не в кожній архетипі: інакше нова пастка
 * мусила б пам'ятати про це правило, і колись його забула б.
 */
public final class TrapModule implements PhaseListener {

    private static final String LOCK_REASON = "trap_victim";

    /** Імунітет жертви після звільнення, у тіках (3 с): щоб не потрапити в той самий капкан миттєво. */
    private static final int RELEASE_IMMUNITY_TICKS = 60;

    private final Supplier<MatchOrchestrator> matchSupplier;

    /** Усі пастки матчу за UUID сутності. */
    private final Map<UUID, PlacedTrap> placed = new HashMap<>();

    /** UUID жертви → UUID капкана, у якому вона. */
    private final Map<UUID, UUID> victims = new HashMap<>();

    /** Слот 0..2 → скільки тіків до дозволу поставити ще раз. */
    private final int[] slotCooldown = new int[ManiacArchetype.MAX_TRAP_SLOTS];

    /** UUID гравця → скільки тіків він ще не спрацьовує пастки. */
    private final Map<UUID, Integer> immunity = new HashMap<>();

    /**
     * Позиція гравця в момент останнього запиту {@link #sweptBox} і на тік
     * раніше — щоб дріт бачив не лише те, де гравець СТОЇТЬ зараз, а й
     * шлях, яким він пройшов за тік (див. {@link #tickWire}).
     */
    private record Trail(long tick, Vec3 previous, Vec3 current) {}

    private final Map<UUID, Trail> trails = new HashMap<>();

    /** Більший за це зсув за тік (блоків) — телепорт, а не рух: шлях не рахуємо. */
    private static final double MAX_SWEEP_BLOCKS = 5.0;

    /**
     * UUID жертви → її спроба вибратися з капкана. Запису немає = жертва
     * вже не має шансу вибратися сама (спроба використана) і чекає на
     * товариша з ломом.
     */
    private final Map<UUID, TrapEscapeMinigame> escapes = new HashMap<>();

    /**
     * UUID жертви → скільки тіків лишилось, щоб натиснути F і почати
     * саму спробу ({@link #escapes}). Запис тут — це стан "капкан щойно
     * захлопнувся, клієнт бачить текстову підказку", ДО того, як
     * гравець натиснув F і повзунок узагалі почав рухатись.
     *
     * ── Навіщо окремий стан, а не одразу {@link #escapes} ────────────
     * За дизайном гравець спершу бачить лише напис "Натисни F" — без
     * запущеного повзунка, без тайм-ауту самої міні-гри. Секунди, що
     * йдуть на прочитання підказки й натискання клавіші, НЕ повинні
     * витрачати час {@code trapEscapeTicks} (інакше повільна реакція
     * забирала б шанс ще до першого влучання). Тому в цьому записі
     * лише окремий, коротший ліміт {@code trapEscapePromptTicks} — і
     * лише коли він мине НАТИСНУТИМ F, стартує {@link TrapEscapeMinigame}
     * зі своїм власним годинником.
     */
    private final Map<UUID, Integer> escapePromptTicks = new HashMap<>();

    /** UUID жертви → UUID капкана, у якому вона чекає F (для {@link #onEscapeStart}). */
    private final Map<UUID, UUID> escapePromptTrap = new HashMap<>();

    /** Лише для розкладки міні-гри (позиція цілі, seed) — ігровий стан не тримає. */
    private final Random rng = new Random();

    /**
     * UUID маньяка → ПЕРША точка вже встановленого дроту, що чекає на
     * другу (ТЗ 2.1). Запису немає = маньяк ще не вибрав жодної точки.
     * Це стан ТИМЧАСОВОГО вибору, а не матчeвий: він живе лише між двома
     * ПКМ одного розміщення.
     */
    private final Map<UUID, BlockPos> pendingFirstPoint = new HashMap<>();

    /**
     * Активний удар струмом (ТЗ 5). Ключ — UUID дроту-сутності, бо
     * охопити може лише одна людина на дріт (ТЗ 7).
     */
    private final Map<UUID, WireShock> wireShocks = new HashMap<>();

    /**
     * Одна сесія удару струмом.
     *
     * @param victimId  хто зараз під струмом
     * @param ticksLeft скільки тіків ще діє (від {@code electricWireShockTicks})
     * @param elapsed   скільки тіків МИНУЛО — за ним раз на 20 тіків
     *                  наноситься шкода й грає наступний звук
     */
    private record WireShock(UUID victimId, int ticksLeft, int elapsed) {}

    /**
     * Активні хмари газу димових мін за UUID сутності-міни. Хмара не
     * «тримає» гравця (на відміну від капкана) — тому жертви сюди НЕ
     * потрапляють у {@link #victims}, а лок руху не ставиться: з хмари
     * можна вибігти.
     */
    private final Map<UUID, SmokeCloud> smokeClouds = new HashMap<>();

    /**
     * Гравець → скільки тіків він ще світиться контуром після хмари. Склад
     * їде в тому самому {@link TrapVictimGlowPacket}, що й жертви капкана
     * (див. {@link #broadcastVictimGlow}) — окремого шару підсвітки немає.
     */
    private final Map<UUID, Integer> smokeGlow = new HashMap<>();

    /** Хто зараз у хмарі й має приглушені звуки (щоб слати пакет лише на зміну). */
    private final Set<UUID> smokeMuffled = new HashSet<>();

    /**
     * Гравець → скільки тіків стаміна ще НЕ відновлюється після газу. У хмарі
     * щотіка скидається на {@code smokeMineStaminaLockTicks}, після виходу
     * спадає до нуля; поки &gt; 0 — стаміна притиснута до нуля.
     */
    private final Map<UUID, Integer> smokeStaminaLock = new HashMap<>();

    /** Одна хмара газу. Стан змінюється щотіка лише в {@link #tickSmoke}. */
    private static final class SmokeCloud {
        final ServerLevel level;
        final UUID mineId;
        /** Центр міни: x/z — середина блока, floorY — верхня грань підлоги. */
        final double x, floorY, z;
        int ticksLeft;
        int elapsed;
        /** Гравець → скільки тіків поспіль він у хмарі (за цим щосекунди йде доза). */
        final Map<UUID, Integer> inside = new HashMap<>();
        /** Кого цей газ уже зарахував у статистику «жертв» (кожен — один раз за хмару). */
        final Set<UUID> counted = new HashSet<>();

        SmokeCloud(ServerLevel level, UUID mineId, double x, double floorY, double z, int ticks) {
            this.level = level;
            this.mineId = mineId;
            this.x = x;
            this.floorY = floorY;
            this.z = z;
            this.ticksLeft = ticks;
        }
    }

    /** Скільки тіків наприкінці хмари нові частинки вже не додаємо — вони доживають і гаснуть разом із газом. */
    private static final int SMOKE_NO_EMIT_TICKS = 60;

    /** Густота диму: скільки частинок і як часто сиплемо (на ВСЮ зону, а не на кубик 3×3). */
    private static final int SMOKE_EMIT_PERIOD_TICKS = 2;
    private static final int SMOKE_EMIT_COUNT = 28;
    /** Перший викид при спрацюванні — хмара «вибухає» одразу, а не наповнюється поступово. */
    private static final int SMOKE_BURST_COUNT = 260;

    /**
     * UUID капкана → тіків до наступного звуку боротьби жертви.
     *
     * ── Чому період = довжині файлу ─────────────────────────────────
     * Боротьба — це не одна подія, а ТРИВАЛИЙ стан, тож звук
     * повторюється. Коли інтервал дорівнює довжині самого ogg, наступне
     * програвання стартує тієї ж миті, коли скінчилось попереднє: ні
     * шва, ні паузи, і нічого не треба тримати в конфігу — заміна файла
     * сама змінить темп. Довжину читає {@code OggSoundLength}.
     *
     * Порожній осередок = 0 = «грати зараз», тому перший звук
     * виставляється в {@link #trigger} на повну довжину: капкан спершу
     * клацає, і лише потім починається боротьба.
     */
    private final Map<UUID, Integer> struggleCooldown = new HashMap<>();

    /**
     * UUID капкана → скільки разів боротьби ще МОЖНА програти.
     *
     * <p>Раніше звук боротьби повторювався, доки капкан тримав жертву, —
     * тобто НЕСКІНЧЕННО, і саме це було претензією з ТЗ ("безкінечний
     * звук зловлення"). Тепер лічильник скінченний і береться з
     * {@code ConfigSchema.BEAR_TRAP_STRUGGLE_REPEATS}: капкан клацнув,
     * кілька разів смикнувся — і затих.</p>
     */
    private final Map<UUID, Integer> strugglePlaysLeft = new HashMap<>();

    /** Тривалість звуку боротьби в тіках; 0 — файл недоступний, звук не граємо. */
    private final int struggleSoundTicks = OggSoundLength.ticksOf("trap_struggle");

    /** Що маньяк обрав у меню на початку матчу: індекси в {@link ManiacArchetype#traps()}. */
    private final List<Integer> chosenSlots = new ArrayList<>();

    private record PlacedTrap(TrapArchetype type, UUID entityId) {}

    public TrapModule(Supplier<MatchOrchestrator> matchSupplier) {
        this.matchSupplier = matchSupplier;
    }

    private MatchOrchestrator match() {
        return matchSupplier.get();
    }

    @Override
    public String id() {
        return "traps";
    }

    // ── Життєвий цикл фаз ────────────────────────────────────────────────

    @Override
    public void onPhaseEnter(GamePhase phase, List<ServerPlayer> players) {
        // ROLE_REVEAL: якщо в маньяка вже є архетип (RANDOM/FIXED — для
        // MENU архетип прийде пізніше через onTrapsChosen/morphManiac,
        // і тоді каталог піде звідти) і є з чого обирати — пропонуємо
        // вибір. Якщо каталог не більший за ліміт, вибирати нема що:
        // TrapModule сам візьме всі при вході в ігрову фазу нижче,
        // TrapCatalogPacket не шлеться, екран вибору не відкриється.
        if (phase == GamePhase.ROLE_REVEAL) {
            MatchOrchestrator m = match();
            ManiacArchetype archetype = m == null ? null : m.maniacArchetype();
            if (archetype != null) {
                for (ServerPlayer p : players) {
                    if (!m.isManiac(p.getUUID())) continue;
                    // Вибирати нема з чого — беремо пастки й шлемо набір ОДРАЗУ, а не
                    // на вході в HUNT: інакше оголошення ролі маньяка (воно стартує
                    // від цього пакета) висіло б порожнім увесь ROLE_REVEAL.
                    if (!autoPickIfNoChoice(p, archetype)) syncCatalogIfChoiceNeeded(p, archetype);
                }
            }
        }

        // Вхід в ігрову фазу. Якщо маньяк не встиг обрати пастки в меню
        // (дебаг, старт командою, вибір відкладено чи не був потрібен) —
        // беремо всі, до ліміту: маньяк не має лишитись без пасток лише
        // через те, що не було кому натиснути. Клієнту одразу шлемо набір.
        if (phase.allows(PhaseRule.TRAPS)) {
            MatchOrchestrator m = match();
            ManiacArchetype archetype = m == null ? null : m.maniacArchetype();
            // Автовибір лише там, де вибирати НЕМА З ЧОГО (каталог не більший за
            // ліміт). Якщо вибір є — пастки з'являться, лише коли маньяк сам
            // підтвердить їх у меню; гра тим часом триває.
            if (m != null && chosenSlots.isEmpty() && !choiceRequired(archetype)) chooseAll(archetype);
            for (ServerPlayer p : players) {
                if (m == null || !m.isManiac(p.getUUID())) continue;
                syncLoadout(p);
                // Вибір ще не зроблено (старт командою, меню не встигло відкритись) —
                // меню пропонуємо тут; якщо воно вже відкрите, клієнт лише оновить дані.
                if (choicePending(archetype)) syncCatalogIfChoiceNeeded(p, archetype);
            }
            return;
        }

        // Стан пасток живе лише в ігрових фазах. Вихід у неігрову
        // (кінець, скидання, лобі) обов'язково розморожує жертв.
        clearLoadoutOnClient(players);
        releaseEveryone(players);
        placed.clear();
        clearSmoke();
        // Разом із пастками зникає й їхній звуковий стан: у новому матчі
        // ті самі UUID не повторяться, але тримати мертві записи нема сенсу
        // (і саме цей виклик робить скидання повним, а не частковим).
        struggleCooldown.clear();
        strugglePlaysLeft.clear();
        pendingFirstPoint.clear();
        wireShocks.clear();
        victims.clear();
        // Підсвітка жертв не переживає матч: id сутностей зникнуть, а
        // клієнтський шар треба явно погасити, інакше наступний матч
        // почнеться з чужими контурами (ті самі id теоретично могли б
        // належати новим сутностям).
        broadcastVictimGlow();
        immunity.clear();
        trails.clear();
        closeAllEscapes(players);
        escapePromptTicks.clear();
        escapePromptTrap.clear();
        java.util.Arrays.fill(slotCooldown, 0);
        // chosenSlots чистимо лише при поверненні в LOBBY/RESET: вибір у
        // меню робиться ДО ігрової фази (ROLE_REVEAL — теж неігрова), і
        // стирати його на вході в ROLE_REVEAL означало б знищити вибір,
        // який щойно зроблено.
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET) resetChoice();
        // Страховка: порожній склад підсвітки ПІСЛЯ всього очищення — гарантовано
        // гасить контури в усіх, навіть якщо проміжний пакет випередила зміна фази.
        broadcastVictimGlow();
    }

    @Override
    public void onPhaseTick(GamePhase phase, List<ServerPlayer> players, long ticksInPhase) {
        if (!phase.allows(PhaseRule.TRAPS)) return;

        tickSlotCooldowns(players);
        tickImmunity();
        // Не звільнений, але вибулий / від'єднаний / більше не виживий — знімаємо лок.
        releaseInvalidVictims(players);
        tickEscapePrompts(players);
        tickEscapes(players);
        tickSmoke();
    }

    // ── Вибір пасток у меню ──────────────────────────────────────────────

    /**
     * Маньяк обрав пастки в меню. Приймаємо лише реальні індекси зі
     * списку його архетипу, без дублів і не більше {@code trapsPerMatch}.
     * Усе інше мовчки відкидається: модифікований клієнт не має способу
     * взяти пастку, якої в маньяка немає, чи більше, ніж дозволено.
     */
    public void onTrapsChosen(ServerPlayer maniac, List<Integer> indices) {
        MatchOrchestrator m = match();
        if (m == null || !m.isManiac(maniac.getUUID())) return;
        ManiacArchetype archetype = m.maniacArchetype();
        if (archetype == null) return;

        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        Set<Integer> seen = new HashSet<>();
        chosenSlots.clear();
        for (int index : indices) {
            if (chosenSlots.size() >= limit) break;
            if (index < 0 || index >= archetype.traps().size()) continue;
            if (!seen.add(index)) continue;
            chosenSlots.add(index);
        }
    }

    /**
     * Автовибір: усі пастки архетипу до ліміту. Лише коли вибирати нема з чого, а раніше — коли меню
     * не показувалось (дебаг-режим, старт командою) — маньяк не має
     * лишитись без пасток лише через те, що не було кому натиснути.
     */
    public void chooseAll(ManiacArchetype archetype) {
        chosenSlots.clear();
        if (archetype == null) return;
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        for (int i = 0; i < archetype.traps().size() && chosenSlots.size() < limit; i++) {
            chosenSlots.add(i);
        }
    }

    /** Чи є з чого вибирати: каталог архетипу більший за ліміт пасток на матч. */
    private boolean choiceRequired(ManiacArchetype archetype) {
        if (archetype == null) return false;
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        return archetype.traps().size() > limit;
    }

    /** Чи вже зроблено вибір (підтверджено в меню або взято автоматично). */
    public boolean hasChosen() {
        return !chosenSlots.isEmpty();
    }

    /** Порожній вибір — наступний матч починається без застарілих пасток. */
    public void resetChoice() {
        chosenSlots.clear();
    }

    /**
     * ДЕБАГ: примусово шле {@link TrapCatalogPacket} маньяку, ігноруючи
     * перевірку {@code all.size() <= limit} з {@link #syncCatalogIfChoiceNeeded}.
     * Використовується лише {@code /maniac debug traps} для UI-тесту
     * {@code TrapChooseScreen}, коли в реєстрі замало пасток, щоб екран
     * відкрився природним шляхом. Ігрову логіку вибору не чіпає: після
     * закриття екрана {@code chooseAll}/{@code onTrapsChosen} відпрацюють
     * як завжди.
     *
     * @return скільки пасток було в надісланому каталозі (0, якщо в
     *         архетипу взагалі немає жодної — пакет тоді не шлеться)
     */
    public int debugForceCatalog(ServerPlayer maniac, ManiacArchetype archetype) {
        List<TrapArchetype> all = archetype.traps();
        if (all.isEmpty()) return 0;

        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        List<String> ids = new ArrayList<>();
        for (TrapArchetype t : all) ids.add(t.id());
        ModNetwork.toPlayer(maniac, new TrapCatalogPacket(ids, limit));
        return ids.size();
    }

    /**
     * Шле маньяку його набір пасток і дальність розміщення — з цього
     * клієнт малює панель зліва й квадрат-підказку. Порядок id = порядок
     * клавіш 5/6/7. Викликається після вибору в меню, при вході в гру й
     * при перезаході маньяка.
     */
    public void syncLoadout(ServerPlayer maniac) {
        List<String> ids = new ArrayList<>();
        for (int slot = 0; slot < chosenSlots.size(); slot++) {
            TrapArchetype type = trapForSlot(slot);
            if (type != null) ids.add(type.id());
        }
        ModNetwork.toPlayer(maniac, new TrapLoadoutPacket(ids,
            ManiacConfigs.get(ConfigSchema.TRAP_PLACE_RANGE_BLOCKS)));
    }

    /**
     * Якщо каталог архетипу більший за ліміт — шле {@link TrapCatalogPacket}
     * (сигнал відкрити екран вибору). Якщо не більший — вибирати нема
     * що, пакет НЕ шлеться: маньяк однаково отримає всі пастки через
     * автовибір при вході в ігрову фазу.
     *
     * Викликається з трьох місць, де архетип щойно стає відомим:
     * вхід у ROLE_REVEAL (RANDOM/FIXED), вибір у меню маньяка
     * (onTrapsChosen — ні, туди НЕ треба: там вибір уже ЗРОБЛЕНО) і
     * дебаг-morph. Насправді виклик потрібен рівно там, де архетип
     * ПРИЗНАЧАЄТЬСЯ, а не де пастки вже обираються — тому дивись
     * виклики в {@code MatchOrchestrator.onManiacChosen}/{@code morphManiac}
     * і в {@link #onPhaseEnter} вище.
     */
    public void syncCatalogIfChoiceNeeded(ServerPlayer maniac, ManiacArchetype archetype) {
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        List<TrapArchetype> all = archetype.traps();
        if (all.size() <= limit) return;

        List<String> ids = new ArrayList<>();
        for (TrapArchetype t : all) ids.add(t.id());
        ModNetwork.toPlayer(maniac, new TrapCatalogPacket(ids, limit));
    }

    /**
     * Каталог не більший за ліміт → вибору немає: беремо всі пастки й шлемо
     * набір маньяку негайно.
     *
     * @return true, якщо вибір закрито автоматично (екран вибору не потрібен)
     */
    public boolean autoPickIfNoChoice(ServerPlayer maniac, ManiacArchetype archetype) {
        if (archetype == null) return false;
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        if (archetype.traps().size() > limit) return false;
        if (chosenSlots.isEmpty()) chooseAll(archetype);
        syncLoadout(maniac);
        return true;
    }

    /**
     * Маньяк іще має обрати пастки: каталог більший за ліміт, а вибір не
     * зроблено. Поки це так, {@code MatchOrchestrator} не закриває ROLE_REVEAL
     * (у межах обмеженого запасу часу) — інакше вибір мовчки втрачався б.
     */
    public boolean choicePending(ManiacArchetype archetype) {
        if (archetype == null) return false;
        int limit = Math.min(ManiacConfigs.get(ConfigSchema.TRAPS_PER_MATCH),
                             ManiacArchetype.MAX_TRAP_SLOTS);
        return archetype.traps().size() > limit && chosenSlots.isEmpty();
    }

    /** Порожній набір — клієнт ховає панель і виходить із режиму розміщення. */
    private void clearLoadoutOnClient(List<ServerPlayer> players) {
        MatchOrchestrator m = match();
        if (m == null) return;
        for (ServerPlayer p : players) {
            if (m.isManiac(p.getUUID())) ModNetwork.toPlayer(p, TrapLoadoutPacket.empty());
        }
    }

    /** Що обрано: слот клавіші (0..2) → індекс у списку пасток архетипу. Для HUD. */
    public List<Integer> chosenSlots() {
        return List.copyOf(chosenSlots);
    }

    /** Пастка на клавіші слота, або null, якщо не обрана. */
    public TrapArchetype trapForSlot(int slot) {
        MatchOrchestrator m = match();
        if (m == null || slot < 0 || slot >= chosenSlots.size()) return null;
        ManiacArchetype archetype = m.maniacArchetype();
        return archetype == null ? null : archetype.trapAt(chosenSlots.get(slot));
    }

    // ── Розміщення ───────────────────────────────────────────────────────

    /**
     * Перевірка місця без розміщення — для червоного/зеленого квадрата.
     * Той самий шлях, що й реальне розміщення, тож клієнт ніколи не
     * покаже зелений там, де сервер відмовить.
     */
    public TrapArchetype.PlacementResult check(ServerPlayer maniac, int slot, BlockPos floor) {
        TrapArchetype type = trapForSlot(slot);
        if (type == null) return TrapArchetype.PlacementResult.UNAVAILABLE;
        if (slotCooldown[slot] > 0) return TrapArchetype.PlacementResult.UNAVAILABLE;

        ServerLevel level = maniac.serverLevel();
        TrapArchetype.PlacementResult shared = TrapPlacementRules.check(level, floor, maniac);
        if (!shared.ok()) return shared;

        TrapArchetype.PlacementResult own = type.validatePlacement(level, floor);
        if (!own.ok()) return own;

        if (isOccupied(level, floor)) return TrapArchetype.PlacementResult.OCCUPIED;
        return TrapArchetype.PlacementResult.OK;
    }

    /**
     * Маньяк підтвердив розміщення (ПКМ у режимі розміщення).
     *
     * Куди ставити, рахується ТУТ за поглядом маньяка, а не береться з
     * клієнта. Блок-підлога — той, у верхню грань якого він дивиться.
     *
     * @return результат: {@code OK} — поставлено; інше — причина відмови для повідомлення
     */
    public TrapArchetype.PlacementResult onPlaceConfirmed(ServerPlayer maniac, int slot, boolean secondPoint,
                                                          BlockPos clientTarget) {
        MatchOrchestrator m = match();
        if (m == null || !m.isManiac(maniac.getUUID())) return TrapArchetype.PlacementResult.UNAVAILABLE;
        if (!m.phases().allows(PhaseRule.TRAPS)) return TrapArchetype.PlacementResult.UNAVAILABLE;

        TrapArchetype type = trapForSlot(slot);
        if (type == null) return TrapArchetype.PlacementResult.UNAVAILABLE;

        BlockPos point = resolveTarget(maniac, clientTarget);
        if (point == null) {
            notifyPlaceFailure(maniac, TrapArchetype.PlacementResult.TOO_FAR);
            return TrapArchetype.PlacementResult.TOO_FAR;
        }

        // TWO_POINTS (електричний дріт) — окремий шлях: спершу перша точка,
        // потім друга + перевірка пари. SINGLE_BLOCK (капкан, міна) — як було.
        if (type.placementShape() == TrapArchetype.PlacementShape.TWO_POINTS) {
            return confirmTwoPoint(maniac, slot, type, point, secondPoint);
        }

        TrapArchetype.PlacementResult result = check(maniac, slot, point);
        if (!result.ok()) {
            notifyPlaceFailure(maniac, result);
            return result;
        }

        Entity body = type.spawn(maniac.serverLevel(), point, maniac);
        if (body == null) return TrapArchetype.PlacementResult.NO_ROOM_ABOVE;

        finishPlacement(maniac, slot, type, body, point);
        maniac.serverLevel().playSound(null, point,
            type.placeSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
        return TrapArchetype.PlacementResult.OK;
    }

    /**
     * Який блок вважати обраним. Пріоритет — блок, який ПОКАЗУВАВ клієнт
     * (щоб пастка стала рівно там, де світився квадрат), але лише якщо
     * погляд гравця справді проходить біля нього й він у межах дальності.
     * Інакше — власний рейкаст сервера (старий шлях): так клієнт, який
     * прислав нісенітницю, отримує звичайну відмову, а не обхід правил.
     */
    private BlockPos resolveTarget(ServerPlayer maniac, BlockPos clientTarget) {
        double range = ManiacConfigs.get(ConfigSchema.TRAP_PLACE_RANGE_BLOCKS);
        if (clientTarget != null
            && maniac.level().isLoaded(clientTarget)
            && TrapPlacementRules.isWithinReach(clientTarget, maniac)
            && TrapAiming.isLookingAtFloor(maniac.level(), maniac, clientTarget, range)) {
            return clientTarget;
        }
        return TrapAiming.targetFloor(maniac.level(), maniac, range);
    }

    /**
     * Друге підтвердження для {@code TWO_POINTS}-пастки (електричний дріт).
     *
     * <p>{@code secondPoint=false} — маньяк вибрав ПЕРШУ точку: її треба
     * лише перевірити як окрему опору й запам'ятати. {@code true} — ДРУГА:
     * перевіряємо пару й ставимо пастку.</p>
     *
     * <p>Якщо друга точка прийшла без першої (клієнт загубив стан,
     * модифікований клієнт, перезайшов) — трактуємо її як першу, а не
     * відкидаємо: так те саме натискання не «губиться» і гравець просто
     * продовжує вибір з поточного погляду.</p>
     */
    private TrapArchetype.PlacementResult confirmTwoPoint(ServerPlayer maniac, int slot, TrapArchetype type,
                                                          BlockPos point, boolean secondPoint) {
        UUID id = maniac.getUUID();
        BlockPos first = pendingFirstPoint.get(id);

        if (!secondPoint || first == null) {
            TrapArchetype.PlacementResult single = check(maniac, slot, point);
            if (!single.ok()) {
                pendingFirstPoint.remove(id);
                notifyPlaceFailure(maniac, single);
                return single;
            }
            pendingFirstPoint.put(id, point);
            // Тихо підтверджуємо першу точку — звук самого встановлення
            // грає аж на другій, коли пастка реально з'являється.
            notify(maniac, ActionBarMessageType.INFO, "maniacmod.trap.wire_first_point");
            return TrapArchetype.PlacementResult.OK;
        }

        pendingFirstPoint.remove(id);
        TrapArchetype.PlacementResult pair = checkPair(maniac, slot, type, first, point);
        if (!pair.ok()) {
            notifyPlaceFailure(maniac, pair);
            return pair;
        }

        Entity body = type.spawn(maniac.serverLevel(), first, point, maniac);
        if (body == null) return TrapArchetype.PlacementResult.NO_ROOM_ABOVE;

        finishPlacement(maniac, slot, type, body, point);
        maniac.serverLevel().playSound(null, point,
            ModSounds.WIRE_PLACE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        return TrapArchetype.PlacementResult.OK;
    }

    /** Записує тіло пастки в реєстр, ставить перезарядку слоту й шле її клієнту. */
    private void finishPlacement(ServerPlayer maniac, int slot, TrapArchetype type, Entity body, BlockPos at) {
        placed.put(body.getUUID(), new PlacedTrap(type, body.getUUID()));
        MatchOrchestrator stats = match();
        if (stats != null) stats.recordTrapPlaced(maniac.getUUID(), type.id());
        int cooldown = type.placementCooldownTicks();
        slotCooldown[slot] = cooldown;
        ModNetwork.toPlayer(maniac,
            new AbilityCooldownPacket(AbilityCooldownPacket.trapId(slot), cooldown));
    }

    /**
     * Спільний вихід відмови розміщення: локальний ключ для повідомлення
     * маньяку. Один метод, щоб усі нові причини (зокрема для дроту)
     * отримували текст без дублювання рядка.
     */
    private void notifyPlaceFailure(ServerPlayer maniac, TrapArchetype.PlacementResult result) {
        notify(maniac, ActionBarMessageType.ERROR, "maniacmod.trap.place_" + result.name().toLowerCase());
    }

    /**
     * Перевірка ПАРИ точок для {@code TWO_POINTS}-пастки: спільні правила
     * (не ближче до гравця, у межах дальності) для КОЖНОЇ точки, форма
     * кожної точки окремо і, нарешті, обмеження самої пари (Y, довжина,
     * перешкоди) — у архетипу.
     */
    private TrapArchetype.PlacementResult checkPair(ServerPlayer maniac, int slot, TrapArchetype type,
                                                    BlockPos a, BlockPos b) {
        if (slotCooldown[slot] > 0) return TrapArchetype.PlacementResult.UNAVAILABLE;
        ServerLevel level = maniac.serverLevel();

        for (BlockPos point : new BlockPos[] { a, b }) {
            // Дальність — лише для щойно вибраної точки (b). Перша (a)
            // пройшла її, коли її обирали; маньяк потім відійшов, щоб
            // вибрати другу, тож повторна перевірка «від очей зараз»
            // відхиляла б будь-який дріт довший за пів дальності.
            TrapArchetype.PlacementResult shared = point.equals(b)
                ? TrapPlacementRules.check(level, point, maniac)
                : TrapPlacementRules.checkIgnoringReach(level, point, maniac);
            if (!shared.ok()) return shared;
            TrapArchetype.PlacementResult own = type.validatePlacement(level, point);
            if (!own.ok()) return own;
            if (isOccupied(level, point)) return TrapArchetype.PlacementResult.OCCUPIED;
        }

        TrapArchetype.PlacementResult pair = type.validatePlacement(level, a, b);
        if (!pair.ok()) return pair;

        // Гравець — така сама перешкода, як блок: дріт крізь нього не
        // протягнути. Клієнт позицій виживих не знає (щоб колір не
        // видавав їх крізь стіни), тож це лише серверна перевірка.
        Vec3 wa = ElectricWireArchetype.wireAnchor(level, a);
        Vec3 wb = ElectricWireArchetype.wireAnchor(level, b);
        if (TrapPlacementRules.isSegmentBlockedBySurvivor(wa, wb, maniac)) {
            return TrapArchetype.PlacementResult.OBSTRUCTED;
        }
        return TrapArchetype.PlacementResult.OK;
    }

    /**
     * Чи коло цього блока вже стоїть пастка (будь-якого типу). Дріт
     * враховується так само, як капкан: інакше маньяк ставив би дві
     * пастки в одній точці, а хітбокс дроту (увесь його відрізок)
     * перетинався б із другою пасткою.
     */
    private boolean isOccupied(ServerLevel level, BlockPos floor) {
        AABB box = new AABB(floor.above());
        for (Entity e : level.getEntities((Entity) null, box.inflate(0.3),
                x -> x instanceof BearTrapEntity || x instanceof RopeTrapEntity
                    || x instanceof SmokeMineEntity || x instanceof SpikeTrapEntity)) {
            if (isRegistered(e)) return true;
        }

        // Дріт має крихітний бокс у центрі (див. WireGeometry), тож
        // перетин боксів його не бачить — міряємо відстань від центру
        // блока до ВІДРІЗКА кожного дроту поруч.
        double maxLen = ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_MAX_LENGTH_BLOCKS);
        Vec3 probe = ElectricWireArchetype.wireAnchor(level, floor);
        double limitSqr = 0.6 * 0.6;
        for (ElectricWireEntity wire : level.getEntitiesOfClass(ElectricWireEntity.class,
                box.inflate(maxLen + 1.0))) {
            if (!isRegistered(wire)) continue;
            if (WireGeometry.distanceToSegmentSqr(probe, wire.anchorA(), wire.anchorB()) < limitSqr) {
                return true;
            }
        }
        return false;
    }

    // ── Реєстр сутностей ─────────────────────────────────────────────────

    /** Чи модуль знає цю сутність. Сирота (пережила матч) — ні, і сама себе прибирає. */
    public boolean isRegistered(Entity entity) {
        if (entity == null) return false;
        return placed.containsKey(entity.getUUID());
    }

    // ── Тік капкана (викликається з BearTrapEntity.tick) ─────────────────

    /**
     * Перевіряє, чи хтось наступив на цю пастку. Захлопнутий капкан
     * не спрацьовує знову, доки жертву не звільнено.
     */
    public void tickTrap(BearTrapEntity trap) {
        PlacedTrap info = placed.get(trap.getUUID());
        if (info == null) return;
        if (!match().phases().allows(PhaseRule.TRAPS)) return;
        if (trap.isSnapped()) {
            // Захлопнутий капкан не спрацьовує вдруге, але він не «мовчить»:
            // поки тримає жертву — вона смикається, і це чути.
            tickStruggle(trap);
            return;
        }

        AABB zone = trap.getBoundingBox();
        // Гравці І тіла тих, хто вийшов: тіло, що стоїть у зоні, наступає на пастку так само.
        for (LivingEntity candidate : ManiacOffline.inBox(trap.level(), zone)) {
            UUID cid = ManiacOffline.idOf(candidate);
            if (cid == null) continue;
            if (immunity.containsKey(cid)) continue;
            if (victims.containsKey(cid)) continue;
            // Лежачого пастка не ловить — і це перевіряється ОДИН раз тут,
            // на всі типи пасток, а не в кожній архетипі (див. canCatch).
            if (!canCatchId(cid)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            trigger(trap, info, candidate);
            return;
        }
    }

    /**
     * Звук боротьби в захлопнутому капкані — раз на {@code struggleSoundTicks}
     * тіків, доки він когось тримає.
     *
     * ── Чому це тікається з сутності, а не з фази ────────────────────
     * Капкан і так тікає себе сам ({@code BearTrapEntity.tick} → {@link #tickTrap}),
     * тож звук приходить рівно тоді, коли капкан існує. Окремий тік у
     * {@code onPhaseTick} вимагав би пошуку сутності за UUID на кожен
     * тік — той самий результат дорожче й з зайвим станом у модулі.
     *
     * Смикання чути ВСІМ поблизу, а не лише жертві: це і є сенс звуку —
     * тімейт має почути, що когось узяло, навіть коли не бачить.
     */
    private void tickStruggle(BearTrapEntity trap) {
        // Скінченна кількість повторів (ТЗ: «звук не повинен
        // зациклюватися нескінченно»). Лічильник виставлений у
        // {@link #trigger} і не поповнюється, доки капкан тримає ту саму
        // жертву, — тому звук природно затихає.
        int playsLeft = strugglePlaysLeft.getOrDefault(trap.getUUID(), 0);
        if (playsLeft <= 0) return;
        if (struggleSoundTicks <= 0) return;

        int left = struggleCooldown.getOrDefault(trap.getUUID(), 0);
        if (left > 0) {
            struggleCooldown.put(trap.getUUID(), left - 1);
            return;
        }

        trap.level().playSound(null, trap.blockPosition(),
            ModSounds.TRAP_STRUGGLE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        strugglePlaysLeft.put(trap.getUUID(), playsLeft - 1);
        // -1, а не повна довжина: тік, у якому ми граємо, теж витрачається
        // на відлік, тож наступне програвання стане рівно за один період
        // від цього — без шва на стику двох файлів.
        struggleCooldown.put(trap.getUUID(), Math.max(1, struggleSoundTicks - 1));
    }

    /**
     * Чи може пастка ЗАХЛОПНУТИ цього гравця: живий виживий на ногах.
     *
     * ── Чому лежачих відсікаємо тут, а не в архетипах ────────────────
     * Правило спільне для ВСІХ пасток (і для майбутніх): пастка ловить
     * того, хто НАСТУПАЄ на неї. Гравець, що вже лежить (повзе чи без
     * свідомості), фізично не робить кроку — капкан під ним спрацював би
     * вже в момент падіння, і лежачий у капкані означав би «збитий з ніг
     * ще раз, без жодної спроби вирватися». Термінальний стан (вибув чи
     * втік) — теж ні: він уже не бореться за життя.
     *
     * Архетип пастки може додати СВОЇ умови ({@link TrapArchetype#shouldTrigger}),
     * але обійти це правило не може — воно стоїть вище в
     * {@link #tickTrap}.
     */
    private boolean canCatch(ServerPlayer candidate) {
        return canCatchId(candidate.getUUID());
    }

    /** Те саме за UUID: потрібне й для тіла, якого як {@link ServerPlayer} немає. */
    private boolean canCatchId(UUID id) {
        MatchOrchestrator m = match();
        if (m == null || !m.isSurvivor(id)) return false;
        return standsUp(m.survivorStateOf(id));
    }

    // ── Димова міна ──────────────────────────────────────────────────────
    //
    // Життєвий цикл: міна чекає → хтось наступив ({@link #tickSmokeMine}) →
    // хмара ({@link SmokeCloud}) живе {@code smokeMineCloudTicks} і веде
    // {@link #tickSmoke} → хмара скінчилась, міна зникає. Усередині зони
    // (квадрат навколо міни) щосекунди діє доза газу
    // ({@link SmokeMineArchetype#applyEffect}: шкода + сліпота), гравець
    // світиться контуром ({@link #smokeGlow}) і чує приглушено
    // ({@link #smokeMuffled}). Хмара НЕ знерухомлює: вибігти можна.

    /** Тік міни (з {@code SmokeMineEntity.tick}): чекає, чи хтось наступить. */
    public void tickSmokeMine(SmokeMineEntity mine) {
        PlacedTrap info = placed.get(mine.getUUID());
        if (info == null) return;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return;
        // Спрацювала — далі хмару веде tickSmoke, міна більше нікого не «ловить».
        if (mine.isTriggered()) return;

        for (LivingEntity candidate : ManiacOffline.inBox(mine.level(), mine.getBoundingBox())) {
            UUID cid = ManiacOffline.idOf(candidate);
            if (cid == null) continue;
            if (immunity.containsKey(cid)) continue;
            if (victims.containsKey(cid)) continue;
            if (!canCatchId(cid)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            triggerSmokeMine(mine);
            return;
        }
    }

    /** Наступили: клацання, шипіння, перша порція диму — і хмара починає жити. */
    private void triggerSmokeMine(SmokeMineEntity mine) {
        if (!(mine.level() instanceof ServerLevel level)) return;
        mine.setTriggered(true);

        SmokeCloud cloud = new SmokeCloud(level, mine.getUUID(), mine.getX(), mine.getY(), mine.getZ(),
            ManiacConfigs.get(ConfigSchema.SMOKE_MINE_CLOUD_TICKS));
        smokeClouds.put(mine.getUUID(), cloud);

        level.playSound(null, mine.blockPosition(), ModSounds.TRAP_SNAP.get(),
            SoundSource.BLOCKS, 1.0f, 1.0f);
        level.playSound(null, mine.blockPosition(), SoundEvents.FIRE_EXTINGUISH,
            SoundSource.BLOCKS, 2.0f, 0.6f);
        sendSmoke(cloud, SMOKE_BURST_COUNT);
    }

    /**
     * Раз на тік для ВСІХ хмар і для спільного стану «у хмарі»: дози газу,
     * підсвітка, глушіння звуків, прибирання відпрацьованих хмар.
     *
     * <p>Спільний стан (підсвітка, глушіння) рахується тут, а не в самій
     * хмарі, бо гравець може стояти одразу в кількох хмарах: пакет шлеться
     * лише коли він увійшов у першу чи вийшов з останньої.</p>
     */
    private void tickSmoke() {
        MatchOrchestrator m = match();
        if (m == null) return;

        Set<UUID> insideNow = new HashSet<>();
        boolean glowChanged = false;
        int glowTicks = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_GLOW_TICKS);
        int staminaLockTicks = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_STAMINA_LOCK_TICKS);

        for (SmokeCloud cloud : new ArrayList<>(smokeClouds.values())) {
            PlacedTrap info = placed.get(cloud.mineId);
            if (info == null) {
                // Міну прибрали (скидання матчу): хмарі нічого лишатись.
                smokeClouds.remove(cloud.mineId);
                continue;
            }

            double r = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_RADIUS_BLOCKS);
            double h = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_HEIGHT_BLOCKS);
            AABB zone = new AABB(cloud.x - r, cloud.floorY, cloud.z - r,
                cloud.x + r, cloud.floorY + h, cloud.z + r);
            BlockPos minePos = BlockPos.containing(cloud.x, cloud.floorY, cloud.z);

            Set<UUID> inZone = new HashSet<>();
            for (LivingEntity p : ManiacOffline.inBox(cloud.level, zone)) {
                UUID id = ManiacOffline.idOf(p);
                // Те саме правило, що й у інших пасток: лише живий виживий на ногах.
                if (id == null || !canCatchId(id) || !info.type().shouldTrigger(p)) continue;
                inZone.add(id);
                insideNow.add(id);
                if (cloud.counted.add(id)) m.recordTrapCaught(info.type().id(), id);

                int inTicks = cloud.inside.getOrDefault(id, 0);
                // Доза (шкода) в момент входу й далі щосекунди, поки гравець у хмарі.
                if (inTicks % 20 == 0) info.type().applyEffect(p, minePos);
                // Сліпота й стаміна — КОЖЕН тік, а не раз на секунду: інакше
                // сліпота могла б «мигнути», а стаміна встигала б відновитись.
                // Стаміна є лише в онлайн-гравця; тіло лише дихає димом (шкода + підсвітка).
                cloud.inside.put(id, inTicks + 1);
                if (p instanceof ServerPlayer sp) {
                    SmokeMineArchetype.applyGasTick(sp);
                    if (staminaLockTicks > 0) smokeStaminaLock.put(id, staminaLockTicks);
                }

                if (glowTicks > 0 && smokeGlow.put(id, glowTicks) == null) glowChanged = true;
            }
            // Вийшов із зони — лічильник скидається: повторний вхід знову дає дозу одразу.
            cloud.inside.keySet().retainAll(inZone);

            cloud.elapsed++;
            if (cloud.elapsed % SMOKE_EMIT_PERIOD_TICKS == 0 && cloud.ticksLeft > SMOKE_NO_EMIT_TICKS) {
                sendSmoke(cloud, SMOKE_EMIT_COUNT);
            }
            if (--cloud.ticksLeft <= 0) endSmokeCloud(cloud);
        }

        // «Хвіст» стаміни: поза хмарою лічильник спадає, а поки він > 0 —
        // стаміна щотіка притиснута до нуля (не відновлюється).
        for (Iterator<Map.Entry<UUID, Integer>> it = smokeStaminaLock.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer p = onlinePlayer(entry.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (!insideNow.contains(entry.getKey())) {
                int left = entry.getValue() - 1;
                if (left <= 0) {
                    it.remove();
                    continue;
                }
                entry.setValue(left);
            }
            SmokeMineArchetype.drainStamina(p);
        }

        // Підсвітка: у хмарі тримається на максимумі, після неї спадає.
        for (Iterator<Map.Entry<UUID, Integer>> it = smokeGlow.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (insideNow.contains(entry.getKey())) continue;
            int left = entry.getValue() - 1;
            if (left <= 0) {
                it.remove();
                glowChanged = true;
            } else {
                entry.setValue(left);
            }
        }
        if (glowChanged) broadcastVictimGlow();

        // Глушіння звуків: пакет лише на вхід у першу хмару й вихід з останньої.
        // Пакет шлемо й при mufflePercent=0 (з множником 1.0): клієнт за ним же
        // вмикає плями диму на екрані, глушіння там лише одна з двох реакцій.
        int mufflePercent = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_MUFFLE_PERCENT);
        for (UUID id : insideNow) {
            if (!smokeMuffled.add(id)) continue;
            ServerPlayer p = onlinePlayer(id);
            if (p != null) ModNetwork.toPlayer(p, new SmokeMufflePacket(true, 1.0f - mufflePercent / 100.0f));
        }
        for (Iterator<UUID> it = smokeMuffled.iterator(); it.hasNext();) {
            UUID id = it.next();
            if (insideNow.contains(id)) continue;
            it.remove();
            ServerPlayer p = onlinePlayer(id);
            if (p != null) ModNetwork.toPlayer(p, SmokeMufflePacket.stop());
        }
    }

    /** Хмара скінчилась: міна (вона одноразова) зникає разом із нею. */
    private void endSmokeCloud(SmokeCloud cloud) {
        smokeClouds.remove(cloud.mineId);
        placed.remove(cloud.mineId);
        Entity mine = cloud.level.getEntity(cloud.mineId);
        if (mine != null) mine.discard();
    }

    /**
     * Сипле частинки диму всім, хто в тому ж вимірі. Шлемо «силою» (довга
     * відстань): хмару мають бачити здалеку, це й є її сенс як завіси.
     * Розкид — у межах зони, тож видимий дим збігається з реальною зоною.
     */
    private static void sendSmoke(SmokeCloud cloud, int count) {
        double r = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_RADIUS_BLOCKS);
        double h = ManiacConfigs.get(ConfigSchema.SMOKE_MINE_HEIGHT_BLOCKS);
        for (ServerPlayer viewer : cloud.level.getServer().getPlayerList().getPlayers()) {
            if (viewer.serverLevel() != cloud.level) continue;
            // Розкид гаусів: ~68% частинок у межах ±(0.55·r), решта далі — тож
            // дим заповнює всю зону й трохи виповзає за її межі (м'який край).
            cloud.level.sendParticles(viewer, ModParticles.SMOKE_CLOUD.get(), true,
                cloud.x, cloud.floorY + h * 0.45, cloud.z,
                count, r * 0.55, h * 0.28, r * 0.55, 0.01);
        }
    }

    /**
     * Повне скидання димових мін: хмари, підсвітка, глушіння. Тим, кого газ
     * застав, знімаємо сліпоту й повертаємо звук — інакше після кінця
     * матчу вони ще кілька секунд сиділи б сліпими в лобі.
     */
    private void clearSmoke() {
        Set<UUID> touched = new HashSet<>(smokeMuffled);
        touched.addAll(smokeGlow.keySet());
        for (UUID id : touched) {
            ServerPlayer p = onlinePlayer(id);
            if (p == null) continue;
            p.removeEffect(MobEffects.BLINDNESS);
            ModNetwork.toPlayer(p, SmokeMufflePacket.stop());
        }
        smokeClouds.clear();
        smokeGlow.clear();
        smokeMuffled.clear();
        smokeStaminaLock.clear();
    }

    // ── Шипи ─────────────────────────────────────────────────────────────
    //
    // Життєвий цикл: шипи чекають ({@link SpikeTrapEntity#ARMED}) → хтось
    // наступив ({@link #tickSpikeTrap}) → удар ({@link #triggerSpikes}: шкода,
    // стаміна в нуль, підсвітка, звук, кров, екранний ефект жертви) → шипи
    // стирчать {@code spikeTrapLingerTicks} → ховаються й зникають.
    // Жертву шипи НЕ тримають: ні лока руху, ні запису в {@link #victims}.
    //
    // Підсвітка й «хвіст» стаміни йдуть тими самими картами, що й у димової
    // міни ({@link #smokeGlow}, {@link #smokeStaminaLock}): їх щотіка веде
    // {@link #tickSmoke} і так само чистять onPlayerLeft/onOfflineBodyGone/
    // clearSmoke — окремого стану для шипів не потрібно.

    /**
     * Тік шипів (з {@code SpikeTrapEntity.tick}): поки зведені — чекають, чи
     * хтось наступить; після спрацювання лише ведуть таймер стирчання.
     */
    public void tickSpikeTrap(SpikeTrapEntity spike) {
        PlacedTrap info = placed.get(spike.getUUID());
        if (info == null) return;

        if (!spike.isArmed()) {
            if (spike.advanceAfterTrigger()) {
                placed.remove(spike.getUUID());
                spike.discard();
            }
            return;
        }

        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return;

        // Усі, хто стоїть на шипах цього тіка, отримують удар разом.
        List<LivingEntity> hit = null;
        for (LivingEntity candidate : ManiacOffline.inBox(spike.level(), spike.getBoundingBox())) {
            UUID cid = ManiacOffline.idOf(candidate);
            if (cid == null) continue;
            if (immunity.containsKey(cid)) continue;
            if (victims.containsKey(cid)) continue;
            // Лежачого пастка не ловить — єдине місце перевірки, див. canCatch.
            if (!canCatchId(cid)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            if (hit == null) hit = new ArrayList<>();
            hit.add(candidate);
        }
        if (hit != null) triggerSpikes(spike, info, hit);
    }

    /** Наступили: шипи вискакують, звук, і кожна жертва отримує удар. */
    private void triggerSpikes(SpikeTrapEntity spike, PlacedTrap info, List<LivingEntity> hit) {
        if (!(spike.level() instanceof ServerLevel level)) return;
        MatchOrchestrator m = match();
        if (m == null) return;

        spike.extend(ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_LINGER_TICKS));

        // Звук із місця пастки: металевий «шінг» + хруст (власний ogg) і колючий
        // шерех поверх нього — чути всім поблизу, не лише жертві.
        BlockPos pos = spike.blockPosition();
        level.playSound(null, pos, ModSounds.SPIKE_HIT.get(), SoundSource.BLOCKS,
            1.6f, 0.92f + level.random.nextFloat() * 0.16f);
        level.playSound(null, pos, SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, SoundSource.BLOCKS,
            1.2f, 0.7f);
        // Іскри від металу в самій пастці.
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
            spike.getX(), spike.getY() + 0.25, spike.getZ(), 16, 0.35, 0.1, 0.35, 0.4);

        int glowTicks = ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_GLOW_TICKS);
        int lockTicks = ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_STAMINA_LOCK_TICKS);
        double knockUp = ManiacConfigs.get(ConfigSchema.SPIKE_TRAP_KNOCK_UP);
        boolean glowChanged = false;

        for (LivingEntity victim : hit) {
            UUID vid = ManiacOffline.idOf(victim);
            if (vid == null) continue;

            // Шкода + стаміна в нуль (SpikeTrapArchetype.applyEffect).
            m.recordTrapCaught(info.type().id(), vid);   // ДО applyEffect: падіння від шипів приписується пастці
            info.type().applyEffect(victim, pos);
            // Якщо хп скінчились — applyEffect уже завів жертву в «лежачий» стан.
            boolean downed = !standsUp(m.survivorStateOf(vid));

            // Підсвітка контуром для всіх + «хвіст» стаміни: ведуть їх tickSmoke.
            if (glowTicks > 0) {
                Integer prev = smokeGlow.get(vid);
                smokeGlow.put(vid, Math.max(glowTicks, prev == null ? 0 : prev));
                if (prev == null) glowChanged = true;
            }
            if (lockTicks > 0) smokeStaminaLock.merge(vid, lockTicks, Math::max);

            // Удар знизу підкидає: жертва не «залипає», а відлітає з шипів.
            if (knockUp > 0.0 && victim instanceof ServerPlayer sp) {
                Vec3 dm = sp.getDeltaMovement();
                sp.setDeltaMovement(dm.x * 0.6, Math.max(dm.y, knockUp), dm.z * 0.6);
                sp.hurtMarked = true;
            }

            // Бризки: шматочки крові й іскри навколо жертви + плями на підлозі/стінах
            // (той самий BloodSplatterPacket, що й від удару маньяка).
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                    net.minecraft.core.particles.ParticleTypes.BLOCK,
                    net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState()),
                victim.getX(), victim.getY() + 0.5, victim.getZ(), 24, 0.25, 0.4, 0.25, 0.14);
            sendSpikeBlood(level, victim, downed);

            // Екранний ефект «потрапив на шипи» — лише самій жертві.
            if (victim instanceof ServerPlayer sp) {
                ModNetwork.toPlayer(sp, new SpikeHitPacket(downed));
            }
        }
        if (glowChanged) broadcastVictimGlow();
    }

    /** Кров від шипів — з тих самих налаштувань, що й кров від ударів маньяка. */
    private static void sendSpikeBlood(ServerLevel level, LivingEntity victim, boolean downed) {
        if (!ManiacConfigs.get(ConfigSchema.BLOOD_ENABLED)) return;
        int droplets = ManiacConfigs.get(ConfigSchema.BLOOD_DROPLETS);
        if (droplets <= 0) return;
        // Напрямку «від маньяка» немає (0,0) — клієнт розкидає краплі на всі боки.
        ModNetwork.toLevel(level, new com.log_to_kot.maniacmod.net.s2c.notify.BloodSplatterPacket(
            victim.getX(), victim.getY() + victim.getBbHeight() * 0.35, victim.getZ(),
            0f, 0f, downed, droplets, level.random.nextLong()));
    }

    // ── Електричний дріт (ТЗ 2-9) ─────────────────────────────────────────

    /**
     * Тік дроту (викликається з {@code ElectricWireEntity.tick}).
     *
     * <p>Або веде вже активний удар струмом, або перевіряє, чи хтось його
     * перетнув. Торкання ловиться ТОЧНОЮ відстанню до ВІДРІЗКА, а не
     * лише перетином AABB: хітбокс дроту — прямокутник навколо всієї
     * лінії, тож у його кутах гравець стояв би «в пастці», фізично не
     * торкаючись дроту.</p>
     */
    public void tickWire(ElectricWireEntity wire) {
        PlacedTrap info = placed.get(wire.getUUID());
        if (info == null) return;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return;

        WireShock shock = wireShocks.get(wire.getUUID());
        if (shock != null) {
            tickShock(wire, shock);
            return;
        }

        Vec3 a = wire.anchorA();
        Vec3 b = wire.anchorB();

        // Грубий відбір сусідів — велика рамка навколо дроту (лише
        // прискорення). Рамка з запасом (1.5 блока), бо гравець міг
        // ПЕРЕСКОЧИТИ дріт за один тік і опинитись уже за межами вузької
        // рамки. Саме торкання — ТОЧНЕ: відрізок дроту проти AABB гравця
        // ({@link #sweptBox}), тож кут «порожнього прямокутника»
        // діагоналі не рахується.
        for (LivingEntity candidate : ManiacOffline.inBox(wire.level(),
                wire.getBoundingBoxForCulling().inflate(1.5))) {
            UUID cid = ManiacOffline.idOf(candidate);
            if (cid == null) continue;
            if (immunity.containsKey(cid)) continue;
            if (victims.containsKey(cid)) continue;
            if (!canCatchId(cid)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            if (!WireGeometry.segmentTouchesBox(a, b, sweptBox(candidate))) continue;
            startShock(wire, candidate);
            return;
        }
    }

    /**
     * AABB гравця, ПРОТЯГНУТИЙ від позиції минулого тіка до поточної.
     *
     * ── Чому не просто {@code getBoundingBox()} ──────────────────────
     * Дріт перевіряється раз на тік. Бігом, ривком чи стрибком гравець
     * зсувається за тік настільки, що між двома перевірками бокс міг
     * опинитись ПО РІЗНІ боки тонкого дроту (висота 2 пікселі, радіус
     * 0.06), жодного разу його не перетнувши — і розтяжка «не
     * спрацьовувала» на гравця, який фізично через неї пройшов.
     *
     * Позиція минулого тіка береться з власного {@link #trails}, а не з
     * {@code xo/yo/zo} гравця: коли саме ванільний код оновлює {@code xo},
     * залежить від порядку тіків сутностей і мережі, тож покладатись на
     * це не можна. Запис оновлюється лениво — раз на тік, при першому
     * запиті; якщо минулого тіка гравця не питали (був далеко від усіх
     * дротів), шлях не рахуємо — стара позиція була б застарілою.
     */
    private AABB sweptBox(LivingEntity player) {
        AABB now = player.getBoundingBox();
        long tick = player.level().getGameTime();
        Vec3 pos = player.position();

        UUID trailId = ManiacOffline.idOf(player);
        Trail trail = trails.get(trailId);
        if (trail == null || tick - trail.tick() > 1) {
            trail = new Trail(tick, pos, pos);
        } else if (trail.tick() != tick) {
            trail = new Trail(tick, trail.current(), pos);
        }
        trails.put(trailId, trail);

        Vec3 back = trail.previous().subtract(pos);
        if (back.lengthSqr() < 1.0E-6 || back.lengthSqr() > MAX_SWEEP_BLOCKS * MAX_SWEEP_BLOCKS) {
            return now;
        }
        return now.minmax(now.move(back));
    }

    /** Жертва торкнулась дроту: починаємо удар струмом (ТЗ 5). */
    private void startShock(ElectricWireEntity wire, LivingEntity victim) {
        UUID vid = ManiacOffline.idOf(victim);
        if (vid == null) return;
        wireShocks.put(wire.getUUID(), new WireShock(vid,
            ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHOCK_TICKS), 0));
        MatchOrchestrator statsMatch = match();
        PlacedTrap wireInfo = placed.get(wire.getUUID());
        if (statsMatch != null && wireInfo != null) statsMatch.recordTrapCaught(wireInfo.type().id(), vid);
        victims.put(vid, wire.getUUID());
        ManiacMod.lib().interactionLockModule()
            .lock(vid, LockType.MOVEMENT, LOCK_REASON);

        // Пакети HUD і камери бачить лише онлайн-власник; тіло лише б'є струмом.
        if (victim instanceof ServerPlayer player) {
            MatchOrchestrator m = match();
            if (m != null) m.survivors().forceVitalsRefresh(player);
            // Окремий прапор для клієнта: рух і так глушиться trapped, але
            // дріт додатково блокує камеру й трясе нею (ТЗ 5).
            ModNetwork.toPlayer(player, new WireShockPacket(true,
                ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_DEGREES),
                ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_HZ)));
        }
        broadcastVictimGlow();
        playZap(wire, 0);
    }

    /** Раз на тік активного удару: секундна шкода, почергові звуки, частинки, завершення. */
    private void tickShock(ElectricWireEntity wire, WireShock shock) {
        MatchOrchestrator m = match();
        UUID victimId = shock.victimId();
        ServerPlayer victim = onlinePlayer(victimId);
        // Жертва вийшла з сервера, але її тіло стоїть на дроті: шок триває на тілі.
        LivingEntity body = victim == null ? bodyOf(victimId) : null;
        // Жертва зникла або більше не «на ногах» (непритомність — пастка
        // мусить відпустити, ТЗ 7) — завершуємо й прибираємо пастку.
        if ((victim == null && body == null) || m == null || !canCatchId(victimId)) {
            endShock(wire, victim, true);
            return;
        }

        if (shock.elapsed() % 20 == 0) {
            int damage = ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_DAMAGE_PER_SECOND);
            if (damage > 0 && m.damageSurvivor(victimId, damage)) {
                // Гравець чи тіло: SurvivorModule валить обох однаково (повалення з таймером).
                m.survivors().onSurvivorDowned(victim != null ? victim : body);
            }
            playZap(wire, shock.elapsed() / 20);
        }
        spawnShockParticles(victim != null ? victim : body);

        int ticksLeft = shock.ticksLeft() - 1;
        if (ticksLeft <= 0) {
            // 15 секунд минуло — пастка повністю зникає, жертва вільна (ТЗ 5.1/7).
            endShock(wire, victim, true);
            return;
        }
        wireShocks.put(wire.getUUID(), new WireShock(shock.victimId(), ticksLeft, shock.elapsed() + 1));
    }

    /**
     * Завершує удар струмом. {@code removeTrap} прибирає сам дріт (і,
     * через {@code remove()}, обидва кріплення) — за ТЗ 7 пастка після
     * спрацювання не залишається.
     */
    private void endShock(ElectricWireEntity wire, ServerPlayer victim, boolean removeTrap) {
        WireShock ended = wireShocks.remove(wire.getUUID());
        // Жертву знаємо й тоді, коли вона офлайн (тіло): id лежить у самому записі шоку.
        UUID victimId = victim != null ? victim.getUUID() : (ended == null ? null : ended.victimId());
        if (victimId != null) {
            victims.remove(victimId);
            ManiacMod.lib().interactionLockModule()
                .unlock(victimId, LockType.MOVEMENT, LOCK_REASON);
            // Імунітет — як після капкана: гравець стоїть на тому місці,
            // де був дріт.
            immunity.put(victimId, RELEASE_IMMUNITY_TICKS);
            if (victim != null) {
                ModNetwork.toPlayer(victim, WireShockPacket.stop());
                MatchOrchestrator m = match();
                if (m != null) m.survivors().forceVitalsRefresh(victim);
            }
        }
        broadcastVictimGlow();
        if (removeTrap) {
            placed.remove(wire.getUUID());
            wire.discard();
        }
    }

    /**
     * Geo-ножиці по дроту (ПКМ, ТЗ 4/8). Спільна точка входу і для
     * нейтралізації ДО спрацювання, і для звільнення жертви, яка вже
     * під струмом (ТЗ: «якщо переріже, поки гравець попався — вибирається
     * зразу») — тому логіка одна.
     */
    public void onWireScissors(ServerPlayer player, ElectricWireEntity wire) {
        if (!placed.containsKey(wire.getUUID())) return;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return;

        // Дальність рахуємо до найближчої точки ВІДРІЗКА, а не до центру
        // сутності: дріт довгий, і «в центр» означало б різати лише з
        // середини.
        Vec3 a = wire.anchorA();
        Vec3 b = wire.anchorB();
        // Запас на радіус прицілу ({@link #SCISSORS_AIM_RADIUS}): промінь
        // міг зачепити дріт трохи далі за номінальну дальність.
        double range = ManiacConfigs.get(ConfigSchema.GEO_SCISSORS_RANGE_BLOCKS) + SCISSORS_AIM_RADIUS;
        if (distanceToSegmentSqr(player.getEyePosition(), a, b) > range * range) return;

        ItemStack scissors = scissorsInHand(player);
        if (scissors.isEmpty()) return;

        // Звук розмінування — з місця дроту, чутний обом сторонам.
        player.level().playSound(null, wire.blockPosition(),
            ModSounds.WIRE_CUT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);

        // Звільняємо жертву, якщо хтось під струмом (endShock сам знімає
        // лок і шле WireShockPacket(false)).
        WireShock shock = wireShocks.get(wire.getUUID());
        ServerPlayer victim = shock == null ? null : onlinePlayer(shock.victimId());
        // Жертва-тіло: endShock бере її id із запису шоку й теж звільняє.
        endShock(wire, victim, false);

        // Одноразовість: предмет зникає (ТЗ 4/8).
        scissors.shrink(1);

        placed.remove(wire.getUUID());
        wire.discard();
        broadcastVictimGlow();
    }

    /** Наскільки близько промінь погляду має пройти від дроту, щоб «влучити» (блоки). */
    private static final double SCISSORS_AIM_RADIUS = 0.25;

    /**
     * ПКМ ножицями: ПРОМІНЬ погляду проти відрізків усіх дротів поруч.
     * Вибираємо найближчий влучений, якщо його не закриває блок.
     *
     * Чому промінь, а не ванільний приціл сутності: у дроту немає
     * хітбокса (див. {@link WireGeometry}), а AABB діагоналі — це великий
     * порожній прямокутник, у який цілилися б повз сам дріт.
     *
     * @return {@code true}, якщо дріт перерізано
     */
    public boolean onScissorsUse(ServerPlayer player) {
        if (scissorsInHand(player).isEmpty()) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return false;

        // Мотузка: спершу підвішений союзник у прицілі, інакше — сам собі
        // (якщо підвішений я). Ножиці ПКМ, без цілі на дріт.
        if (m.isSurvivor(player.getUUID())) {
            if (tryCutHangingInSight(player)) return true;
            if (ropeHangs.containsKey(player.getUUID()) && cutRope(player, player)) return true;
        }

        ServerLevel level = player.serverLevel();
        double range = ManiacConfigs.get(ConfigSchema.GEO_SCISSORS_RANGE_BLOCKS);
        Vec3 eye = VrServerCompat.aimOrigin(player);
        Vec3 look = VrServerCompat.aimDirView(player).normalize();

        // Блок на шляху променя закриває дріт за собою (не різати крізь стіну).
        double blockDist = range;
        var blockHit = level.clip(new net.minecraft.world.level.ClipContext(
            eye, eye.add(look.scale(range)),
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (blockHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            blockDist = eye.distanceTo(blockHit.getLocation());
        }

        // Бокс сутності дроту — крихітний, у центрі; тому шукаємо з запасом
        // на пів-довжини дроту, а не за перетином боксів.
        double maxLen = ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_MAX_LENGTH_BLOCKS);
        AABB search = player.getBoundingBox().inflate(range + maxLen + 1.0);

        ElectricWireEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ElectricWireEntity wire : level.getEntitiesOfClass(ElectricWireEntity.class, search)) {
            if (!placed.containsKey(wire.getUUID())) continue;
            double d = WireGeometry.rayHitDistance(eye, look, range,
                wire.anchorA(), wire.anchorB(), SCISSORS_AIM_RADIUS);
            if (Double.isNaN(d)) continue;
            if (d > blockDist + 0.3) continue;   // закритий блоком
            if (d < bestDist) {
                bestDist = d;
                best = wire;
            }
        }
        if (best == null) return false;

        onWireScissors(player, best);
        return true;
    }

    /** Ножиці в будь-якій руці, або {@code EMPTY} — зокрема для перевірки «а що тримає». */
    private static ItemStack scissorsInHand(ServerPlayer player) {
        if (player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof GeoScissorsItem) {
            return player.getItemInHand(InteractionHand.MAIN_HAND);
        }
        if (player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof GeoScissorsItem) {
            return player.getItemInHand(InteractionHand.OFF_HAND);
        }
        return ItemStack.EMPTY;
    }

    /** Одна з ТРЬОХ звукових подій удару — почергово (ТЗ 6), не випадково. */
    private void playZap(ElectricWireEntity wire, int index) {
        var sound = switch (Math.floorMod(index, 3)) {
            case 0 -> ModSounds.WIRE_ZAP;
            case 1 -> ModSounds.WIRE_ZAP_2;
            default -> ModSounds.WIRE_ZAP_3;
        };
        wire.level().playSound(null, wire.blockPosition(), sound.get(),
            SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * Електричні частинки по тілу жертви (ТЗ 5.4). Шлемо не-сильними
     * (normal=false прибирає far-видимість) — ефект тілесний, він має
     * бути прив'язаний до гравця й повторюватись щотіка.
     */
    private static void spawnShockParticles(LivingEntity victim) {
        if (!(victim.level() instanceof ServerLevel level)) return;
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            if (viewer.serverLevel() != level) continue;
            level.sendParticles(viewer, com.log_to_kot.maniacmod.registry.ModParticles.WIRE_SPARK.get(), true,
                victim.getX(), victim.getY() + 0.9, victim.getZ(),
                8, 0.35, 0.8, 0.35, 0.02);
        }
    }

    /** Квадрат відстані від точки до ВІДРІЗКА (не до прямої й не до кінців). */
    private static double distanceToSegmentSqr(Vec3 p, Vec3 a, Vec3 b) {
        return WireGeometry.distanceToSegmentSqr(p, a, b);
    }

    /** Чи стан гравця дозволяє тримати його в пастці (див. {@link #canCatch}). */
    private static boolean standsUp(SurvivorState state) {
        return state != null && !state.isCrawlOnly() && !state.isTerminal();
    }

    private void trigger(BearTrapEntity trap, PlacedTrap info, LivingEntity victim) {
        UUID vid = ManiacOffline.idOf(victim);
        if (vid == null) return;
        trap.setSnapped(true);
        victims.put(vid, trap.getUUID());
        // Клацок капкана чути з його місця — це головний звук-підказка
        // для тих, хто ще не бачить, що сталося. Боротьба почнеться після
        // нього, а не в один тік із ним (див. tickStruggle).
        trap.level().playSound(null, trap.blockPosition(),
            ModSounds.TRAP_SNAP.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        struggleCooldown.put(trap.getUUID(), Math.max(1, struggleSoundTicks));
        // Звук боротьби — СКІНЧЕННА кількість разів (ТЗ: без вічного
        // циклу). 0 у конфігу — капкан лише клацає.
        strugglePlaysLeft.put(trap.getUUID(),
            ManiacConfigs.get(ConfigSchema.BEAR_TRAP_STRUGGLE_REPEATS));

        // Візуальна підсвітка жертви — щоб було чітко видно, що вона в
        // пастці (ТЗ 1.1). Сервер шле новий склад усім: жертва світиться
        // контуром у КОЖНОГО гравця, а не лише в себе (це і є сенс —
        // товариші мають знайти того, хто потрапив).
        broadcastVictimGlow();

        // Жертву притягуємо до центру капкана: нога має опинитись «в зубах»,
        // а не збоку від моделі.
        if (victim.level() instanceof ServerLevel trapLevel) {
            ManiacOffline.placeAt(victim, trapLevel, trap.getX(), trap.getY(), trap.getZ(),
                victim.getYRot(), victim.getXRot());
        }
        ManiacMod.lib().interactionLockModule()
            .lock(vid, LockType.MOVEMENT, LOCK_REASON);

        // Статистика ДО applyEffect: падіння від капкана в історії приписується пастці, а не минулому удару.
        MatchOrchestrator statsMatch = match();
        if (statsMatch != null) statsMatch.recordTrapCaught(info.type().id(), vid);
        info.type().applyEffect(victim, trap.blockPosition());

        // Форс-знімок HUD одразу, а не на наступному throttled тіку:
        // клієнтський блок руху (MixinKeyboardInputTrapped) читає поле
        // trapped із SurvivorVitalsPacket і має спрацювати в той самий
        // момент, що серверний лок, інакше жертва встигає на кадр-два
        // зрушити з місця до прильоту пакета.
        // Підказка й HUD лише онлайн-власнику. Тіло, що наступило, вільне лише через
        // союзника (лом) або коли власник повернеться (див. onPlayerReturned).
        if (victim instanceof ServerPlayer player) {
            MatchOrchestrator m = match();
            if (m != null) m.survivors().forceVitalsRefresh(player);

            // А вже потім — підказка "Натисни F": клієнт вибирає її вигляд
            // за власним станом «я в пастці» (той самий стиль тексту, що
            // GeneratorHintOverlay), тож знімок HUD мусить прийти раніше за
            // цей стан (інакше на кадр блимнула б чужа підказка).
            openEscapePrompt(player, trap);
        }
    }

    // ── Саморятунок: міні-гра «попади в ціль» ─────────────────────────────

    /**
     * Відкриває жертві ПІДКАЗКУ "Натисни F, щоб вибратися" — ще не саму
     * міні-гру. Повзунок і відлік {@code trapEscapeTicks} не запущені:
     * вони стартують лише в {@link #onEscapeStart}, коли гравець
     * фактично натисне F. Тут лише виставляється короткий ліміт
     * {@code trapEscapePromptTicks} на саме натискання (див. клас-докстрінг
     * {@link #escapePromptTicks}).
     */
    private void openEscapePrompt(ServerPlayer victim, BearTrapEntity trap) {
        escapePromptTicks.put(victim.getUUID(),
            ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_PROMPT_TICKS));
        escapePromptTrap.put(victim.getUUID(), trap.getUUID());
        ModNetwork.toPlayer(victim, new TrapEscapePromptPacket(true));
    }

    /**
     * Відкриває жертві спробу вибратися з капкана — викликається лише
     * з {@link #onEscapeStart}, коли гравець натиснув F у відповідь на
     * підказку.
     *
     * Спільна з генератором геометрія ({@link TargetMinigameSpec}), але
     * СВОЇ значення з конфігу — капкан і ремонт настроюються окремо.
     * Ціль не по центру й не впритул до країв: та сама причина, що в
     * генератора — повзунок мусить мати місце пробігти повз неї, інакше
     * вона була б «безкоштовною» на розвороті хвилі.
     */
    private void startEscapeMinigame(ServerPlayer victim, BearTrapEntity trap) {
        double cursorSpeed = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_CURSOR_SPEED);
        double hitZoneWidth = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_HIT_ZONE_WIDTH);
        int hitsRequired = ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_HITS_REQUIRED);
        double targetPosition = randomTargetPosition();

        TargetMinigameSpec spec = new TargetMinigameSpec(
            rng.nextLong(), cursorSpeed, hitZoneWidth, targetPosition, hitsRequired);
        escapes.put(victim.getUUID(), new TrapEscapeMinigame(trap.getUUID(), spec,
            ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_MISS_PENALTY),
            ManiacConfigs.get(ConfigSchema.TRAP_ESCAPE_TICKS)));

        // Клієнт тримає ЛИШЕ ОДИН екран міні-гри. Якщо в гравця була
        // відкрита міні-гра ремонта генератора, її треба закрити ДО
        // відкриття цієї: інакше пізніший «результат» генератора закрив
        // би вже екран капкана, і гравець лишився б у пастці без нього.
        MatchOrchestrator m = match();
        if (m != null) m.generatorModule().abandonMinigame(victim);

        ModNetwork.toPlayer(victim, new TargetMinigameOpenPacket(
            trap.blockPosition(), spec.seed(), spec.cursorSpeed(), spec.hitZoneWidth(),
            spec.targetPosition(), spec.hitsRequired()));
    }

    /**
     * Гравець, що бачить підказку "Натисни F", фактично натиснув F.
     *
     * Мовчки ігнорує, якщо в гравця немає активної підказки (не в
     * капкані, вже почав спробу, чи спроба вже закінчилась) — той самий
     * стиль, що решта хендлерів C2S-намірів: клієнт може бути
     * модифікований і слати пакет коли завгодно.
     */
    public void onEscapeStart(ServerPlayer player) {
        // Підвішений мотузкою: F відкриває екран розкачування (можна повторно).
        if (ropeHangs.containsKey(player.getUUID())) {
            ModNetwork.toPlayer(player, new com.log_to_kot.maniacmod.net.s2c.minigame.RopeMinigameOpenPacket());
            return;
        }
        UUID trapId = escapePromptTrap.remove(player.getUUID());
        Integer left = escapePromptTicks.remove(player.getUUID());
        if (trapId == null || left == null) return;

        ModNetwork.toPlayer(player, new TrapEscapePromptPacket(false));
        BearTrapEntity trap = trapEntity(trapId, player);
        // Капкан зник (звільнений тимчасом ломом товариша) — підказка
        // закрита без наслідків, розкривати міні-гру нема для чого.
        if (trap == null) return;

        startEscapeMinigame(player, trap);
    }

    /**
     * Клік у міні-грі визволення — приходить із {@code ServerPacketHandler}
     * (той самий пакет кліку, що й у міні-гри генератора: на клієнті все
     * одно відкритий лише один екран).
     *
     * @return true, якщо клік належав спробі визволення цього гравця —
     *         тоді викликач більше нічого не пробує; false — у гравця
     *         жодної спроби немає
     */
    public boolean onEscapeAttempt(ServerPlayer player, double cursorPosition) {
        TrapEscapeMinigame game = escapes.get(player.getUUID());
        if (game == null) return false;

        // Допуск затримки мережі — спільний на всі міні-гри з повзунком:
        // це властивість КАНАЛУ, а не механіки, тож окремий ключ лише
        // розійшовся б із генераторним без причини.
        switch (game.attempt(cursorPosition,
                ManiacConfigs.get(ConfigSchema.TARGET_MINIGAME_LAG_TOLERANCE_MS))) {
            case HIT, MISS -> {
                // ТЗ 1.2: ціль ПЕРЕМІЩУЄТЬСЯ після КОЖНОЇ спроби — і
                // після влучання, і після промаху. Сервер підставляє нову
                // позицію у спеку (щоб наступний клік звірявся вже з нею)
                // і везе її клієнту в тому самому пакеті, що й вердикт, —
                // клієнт не вгадує, куди переїхала ціль.
                rerollTarget(game);
                ModNetwork.toPlayer(player, new TrapEscapeAttemptPacket(
                    game.hits(),
                    // ВЕРДИКТ із сервера, а не «число змінилось» — клієнту
                    // потрібна окрема реакція на попадання й на промах.
                    game.lastResult() == TrapEscapeMinigame.Result.HIT,
                    game.spec().targetPosition()));
            }
            case ESCAPED -> {
                escapes.remove(player.getUUID());
                ModNetwork.toPlayer(player, new RepairMinigameResultPacket(true));
                escapeSuccess(player, game);
            }
        }
        return true;
    }

    /** Нова випадкова позиція цілі в межах смуги (та сама формула, що при відкритті гри). */
    private double randomTargetPosition() {
        return 0.15 + rng.nextDouble() * 0.70;
    }

    /**
     * Переносить ціль міні-гри в нове місце, зберігаючи решту параметрів
     * спроби. Використовується після кожної спроби (див.
     * {@link #onEscapeAttempt}); при старті гри ціль ставить
     * {@link #startEscapeMinigame}.
     */
    private void rerollTarget(TrapEscapeMinigame game) {
        TargetMinigameSpec old = game.spec();
        game.setSpec(new TargetMinigameSpec(old.seed(), old.cursorSpeed(), old.hitZoneWidth(),
            randomTargetPosition(), old.hitsRequired()));
    }

    /**
     * Гравець вибрався з капкана сам — той самий наслідок, що удар ломом
     * по капкану: лок знято, капкан розкритий і прибраний.
     * Імунітет тут потрібен так само: гравець стоїть НА тому місці, де
     * щойно був капкан.
     */
    private void escapeSuccess(ServerPlayer victim, TrapEscapeMinigame game) {
        BearTrapEntity trap = trapEntity(game.trapId(), victim);
        if (trap != null) {
            release(victim, trap);
            trap.discard();
            placed.remove(trap.getUUID());
            struggleCooldown.remove(trap.getUUID());
        }
        notify(victim, ActionBarMessageType.SUCCESS, "maniacmod.trap.escape_success");
    }

    /**
     * Раз на тік для кожної відкритої підказки "Натисни F". Час вийшов
     * (гравець не встиг чи не захотів натиснути) — та сама доля, що
     * провал самої міні-гри: підказка гасне, гравець лишається в
     * капкані й чекає на товариша з ломом. Спроба вважається
     * витраченою, як і у {@link #tickEscapes}, тож повторної підказки
     * на це саме захлопування не буде.
     */
    private void tickEscapePrompts(List<ServerPlayer> players) {
        for (Map.Entry<UUID, Integer> entry : new ArrayList<>(escapePromptTicks.entrySet())) {
            UUID id = entry.getKey();
            int left = entry.getValue() - 1;
            if (left > 0) {
                escapePromptTicks.put(id, left);
                continue;
            }

            escapePromptTicks.remove(id);
            escapePromptTrap.remove(id);
            ServerPlayer player = onlinePlayer(id);
            if (player == null) continue;
            // Клієнт сам НЕ здогадається сховати підказку: isTrapped()
            // лишається true. Тому сервер явно каже "ховай". Екран
            // міні-гри ще не відкривався — закривати нічого.
            ModNetwork.toPlayer(player, new TrapEscapePromptPacket(false));
            notify(player, ActionBarMessageType.COOLDOWN, "maniacmod.trap.escape_timeout");
        }
    }

    /**
     * Раз на тік для кожної активної спроби визволення. Час вийшов —
     * екран закривається, гравець лишається в капкані (спроба
     * витрачена, чекає на товариша з ломом). Зниклих чи невиживих
     * закриває {@link #releaseInvalidVictims} — тут вони лише
     * прибираються без пакета.
     */
    private void tickEscapes(List<ServerPlayer> players) {
        for (Map.Entry<UUID, TrapEscapeMinigame> entry : new ArrayList<>(escapes.entrySet())) {
            ServerPlayer player = onlinePlayer(entry.getKey());
            if (player == null) {
                escapes.remove(entry.getKey());
                continue;
            }

            TrapEscapeMinigame game = entry.getValue();
            game.tick();
            if (!game.isExpired()) continue;

            escapes.remove(entry.getKey());
            ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
            notify(player, ActionBarMessageType.COOLDOWN, "maniacmod.trap.escape_timeout");
        }
    }

    /**
     * Закриває підказку "Натисни F" і/або активну спробу визволення
     * цього гравця, якщо вони є (напр. товариш зірвав капкан ломом).
     * Підказка ховається явним {@code TrapEscapePromptPacket(false)},
     * відкритий екран міні-гри — {@code RepairMinigameResultPacket}.
     */
    private void closeEscape(ServerPlayer player) {
        if (escapePromptTicks.remove(player.getUUID()) != null) {
            ModNetwork.toPlayer(player, new TrapEscapePromptPacket(false));
        }
        escapePromptTrap.remove(player.getUUID());
        if (escapes.remove(player.getUUID()) == null) return;
        ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
    }

    /**
     * Закриває УСІ відкриті підказки й спроби — вихід із ігрової фази
     * чи кінець матчу. Без цього екран міні-гри лишився б висіти на
     * екрані гравця (сервер уже нічого не тікає, отже й не надішле
     * результат); підказку так само гасимо явним пакетом.
     */
    private void closeAllEscapes(List<ServerPlayer> players) {
        for (UUID id : new ArrayList<>(escapes.keySet())) {
            ServerPlayer player = onlinePlayer(id);
            if (player != null) ModNetwork.toPlayer(player, new RepairMinigameResultPacket(false));
        }
        for (UUID id : new ArrayList<>(escapePromptTicks.keySet())) {
            ServerPlayer player = onlinePlayer(id);
            if (player != null) ModNetwork.toPlayer(player, new TrapEscapePromptPacket(false));
        }
        escapes.clear();
        escapePromptTicks.clear();
        escapePromptTrap.clear();
    }

    /** Сутність капкана за UUID, якщо вона зараз завантажена у світі гравця. */
    private BearTrapEntity trapEntity(UUID trapId, ServerPlayer context) {
        if (trapId == null || context == null) return null;
        if (!(context.level() instanceof ServerLevel level)) return null;
        return level.getEntities().get(trapId) instanceof BearTrapEntity trap ? trap : null;
    }

    // ── Мотузка-підвіс ───────────────────────────────────────────────────
    //
    // Модель: жорсткий маятник. Кут theta (рад) відраховується від вертикалі
    // у бік осі гойдання (вправо від погляду жертви на момент підвішування).
    // Щотіка: omega = (omega - k·theta)·(1 - damping); theta += omega.
    // Натискання A/D додає omega·kick лише у ТАКТ (у напрямку поточного руху й
    // не частіше за інтервал) — інакше промах, який гасить розгойдування вдвічі.
    // Розмах ≥ ropeSwingBreakDegrees — нитка рветься, жертва вільна.
    // Позицією жертви керує СЕРВЕР (teleportTo щотіка, коли вона зрушила):
    // без гравітації, з фіксованим yaw — вона «висить» і не може тікати.

    /** Половина висоти тіла: центр підвішеного тіла над початком хітбокса жертви. */
    private static final double ROPE_BODY_HALF = 1.0;
    /** На скільки голова висить над підлогою у спокої. */
    private static final double ROPE_HEAD_CLEARANCE = 0.5;
    /** Скільки напрямків гойдання перебираємо по півколу (15° крок). */
    private static final int ROPE_AXIS_CANDIDATES = 12;
    /** Скільки позицій маятника перевіряємо в кожну сторону. */
    private static final int ROPE_AXIS_SAMPLES = 6;
    /** Повільніше за це вважається «стоїть»: тоді натискання будь-якого напрямку зараховується. */
    private static final double ROPE_REST_OMEGA = 0.004;

    /** Стан одного підвісу (за UUID жертви). theta/omega/lastPress змінюються щотіка. */
    private static final class RopeHang {
        final UUID ropeId;
        final Vec3 pivot;          // точка на стелі
        final double length;       // від pivot до центру тіла
        final Vec3 axis;           // одиничний вектор осі гойдання
        final float yaw;           // фіксований yaw жертви
        final double breakRad;
        double theta;
        double omega;
        long lastPress = Long.MIN_VALUE / 2;

        RopeHang(UUID ropeId, Vec3 pivot, double length, Vec3 axis, float yaw, double breakRad) {
            this.ropeId = ropeId;
            this.pivot = pivot;
            this.length = length;
            this.axis = axis;
            this.yaw = yaw;
            this.breakRad = breakRad;
        }
    }

    /** UUID жертви → її підвіс. Ключі ⊂ {@link #victims}. */
    private final Map<UUID, RopeHang> ropeHangs = new HashMap<>();

    /** Тік мотузки (з {@code RopeTrapEntity.tick}): або чекає жертву, або веде розгойдування. */
    public void tickRope(RopeTrapEntity rope) {
        PlacedTrap info = placed.get(rope.getUUID());
        if (info == null) return;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return;

        if (rope.isSnapped()) {
            tickRopeHang(rope);
            return;
        }
        for (LivingEntity candidate : ManiacOffline.inBox(rope.level(), rope.getBoundingBox())) {
            UUID cid = ManiacOffline.idOf(candidate);
            if (cid == null) continue;
            if (immunity.containsKey(cid)) continue;
            if (victims.containsKey(cid)) continue;
            if (!canCatchId(cid)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            triggerRope(rope, info, candidate);
            return;
        }
    }

    private static Vec3 ropeOrigin(RopeHang h, double theta) {
        double s = Math.sin(theta);
        double c = Math.cos(theta);
        return new Vec3(
            h.pivot.x + h.axis.x * h.length * s,
            h.pivot.y - h.length * c - ROPE_BODY_HALF,
            h.pivot.z + h.axis.z * h.length * s);
    }

    /**
     * Вибирає напрямок гойдання там, де НЕМАЄ стін. Перебирає {@code ROPE_AXIS_CANDIDATES}
     * осей по колу (вісь двостороння: +θ і −θ), на кожній перевіряє, чи влізе
     * тіло в позиціях маятника від 1/6 до повного кута розриву. Обирається
     * вісь із найменшою кількістю заблокованих позицій; при рівності — ближча
     * до «вправо від погляду» (камері природніше). Повертає кут осі в градусах
     * (у тій самій конвенції, що {@code RopeTrapEntity#swingAxis}).
     */
    private float chooseSwingAxisYaw(ServerLevel level, Vec3 pivot, double length,
                                     double breakRad, float viewYaw) {
        float bestYaw = viewYaw;
        int bestBlocked = Integer.MAX_VALUE;
        double bestPenalty = Double.MAX_VALUE;

        for (int i = 0; i < ROPE_AXIS_CANDIDATES; i++) {
            float offset = i * (180f / ROPE_AXIS_CANDIDATES);
            // Нормалізуємо відступ у (-90, 90]: «майже вправо» краще за «майже вліво».
            float d = offset > 90f ? offset - 180f : offset;
            float yaw = viewYaw + d;
            double r = Math.toRadians(yaw);
            Vec3 axis = new Vec3(-Math.cos(r), 0, -Math.sin(r));

            int blocked = 0;
            for (int k = 1; k <= ROPE_AXIS_SAMPLES; k++) {
                double theta = breakRad * k / ROPE_AXIS_SAMPLES;
                for (int sign = -1; sign <= 1; sign += 2) {
                    double t = theta * sign;
                    double sn = Math.sin(t), cs = Math.cos(t);
                    double cx = pivot.x + axis.x * length * sn;
                    double cy = pivot.y - length * cs - ROPE_BODY_HALF;
                    double cz = pivot.z + axis.z * length * sn;
                    var box = new net.minecraft.world.phys.AABB(
                        cx - 0.3, cy, cz - 0.3, cx + 0.3, cy + 1.8, cz + 0.3);
                    if (!level.noCollision(box)) blocked++;
                }
            }
            double penalty = Math.abs(d);
            if (blocked < bestBlocked || (blocked == bestBlocked && penalty < bestPenalty)) {
                bestBlocked = blocked;
                bestPenalty = penalty;
                bestYaw = yaw;
            }
        }
        return bestYaw;
    }

    private void triggerRope(RopeTrapEntity rope, PlacedTrap info, LivingEntity victim) {
        UUID vid = ManiacOffline.idOf(victim);
        if (vid == null || !(victim.level() instanceof ServerLevel victimLevel)) return;
        float yaw = victim.getYRot();               // погляд фіксується
        Vec3 pivot = rope.ceilingPoint();
        double restY = rope.getY() + ROPE_HEAD_CLEARANCE;
        double length = pivot.y - (restY + ROPE_BODY_HALF);
        double breakRad = Math.toRadians(ManiacConfigs.get(ConfigSchema.ROPE_SWING_BREAK_DEGREES));

        // Вісь гойдання — туди, де вільно (не обов'язково вздовж погляду).
        float axisYaw = chooseSwingAxisYaw(victimLevel, pivot, length, breakRad, yaw);
        double ar = Math.toRadians(axisYaw);
        Vec3 axis = new Vec3(-Math.cos(ar), 0, -Math.sin(ar));

        RopeHang hang = new RopeHang(rope.getUUID(), pivot, length, axis, yaw, breakRad);
        ropeHangs.put(vid, hang);
        victims.put(vid, rope.getUUID());

        rope.setVictim(victim.getId());
        rope.setAxisYaw(axisYaw);
        rope.setBreakAngle((float) breakRad);
        rope.setSwing(0f);
        rope.setSnapped(true);

        ManiacMod.lib().interactionLockModule()
            .lock(vid, LockType.MOVEMENT, LOCK_REASON);
        victim.setNoGravity(true);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.fallDistance = 0f;
        Vec3 origin = ropeOrigin(hang, 0.0);
        ManiacOffline.placeAt(victim, victimLevel, origin.x, origin.y, origin.z, yaw, 0f);

        rope.level().playSound(null, rope.blockPosition(),
            ModSounds.ROPE_CATCH.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
        MatchOrchestrator statsMatch = match();
        if (statsMatch != null) statsMatch.recordTrapCaught(info.type().id(), vid);
        info.type().applyEffect(victim, rope.blockPosition());
        broadcastVictimGlow();

        if (victim instanceof ServerPlayer player) {
            MatchOrchestrator m = match();
            if (m != null) m.survivors().forceVitalsRefresh(player);
            notify(player, ActionBarMessageType.INFO, "maniacmod.trap.rope_hung");
            // Підказка «Натисни F» — міні-гра відкривається окремим екраном, як у капкана.
            ModNetwork.toPlayer(player, new TrapEscapePromptPacket(true));
        }
    }

    private void tickRopeHang(RopeTrapEntity rope) {
        UUID vid = victimOf(rope.getUUID());
        RopeHang h = vid == null ? null : ropeHangs.get(vid);
        // Підвішений може бути й ТІЛОМ (власник вийшов): нитка тримає сутність, а не гравця.
        LivingEntity v = vid == null ? null : playerOrBody(vid);
        if (h == null || v == null || !(v.level() instanceof ServerLevel vLevel)) {
            // Жертви більше нема (вийшла, скинули) — пастка одноразова, прибираємо.
            if (vid != null) endRopeHang(vid);
            placed.remove(rope.getUUID());
            rope.discard();
            return;
        }

        double k = ManiacConfigs.get(ConfigSchema.ROPE_SWING_FREQUENCY);
        double damping = ManiacConfigs.get(ConfigSchema.ROPE_SWING_DAMPING);
        h.omega = (h.omega - k * h.theta) * (1.0 - damping);
        double next = h.theta + h.omega;

        if (Math.abs(next) >= h.breakRad) {
            v.level().playSound(null, v.blockPosition(),
                ModSounds.ROPE_ESCAPE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
            if (v instanceof ServerPlayer player) {
                notify(player, ActionBarMessageType.SUCCESS, "maniacmod.trap.rope_snapped");
            }
            endRopeHang(vid);
            return;
        }

        // Стіни не гальмують: напрямок гойдання вибрано там, де вільно
        // ({@link #chooseSwingAxisYaw}); якщо скрізь тісно — проходимо крізь
        // блок, щоб розкачування не вмирало об стіну.
        h.theta = next;
        Vec3 origin = ropeOrigin(h, next);
        if (origin.distanceToSqr(v.position()) > 1.0E-4) {
            ManiacOffline.placeAt(v, vLevel, origin.x, origin.y, origin.z, h.yaw, 0f);
        }
        // Жертва не падає й не їде: гасимо все, що встигла накопичити фізика.
        v.setDeltaMovement(Vec3.ZERO);
        v.fallDistance = 0f;
        v.setNoGravity(true);
        // id сутності міняється, коли власник виходить/повертається (гравець <-> тіло):
        // нитка мусить завжди вести до актуальної. Однакове значення не шле пакетів.
        rope.setVictim(v.getId());
        rope.setSwing((float) h.theta);
    }

    /**
     * Натискання A (-1) чи D (+1) підвішеною жертвою. Сервер сам вирішує,
     * чи це «в такт»: клієнт лише каже, яку клавішу тиснули.
     */
    public void onRopeSwing(ServerPlayer player, int dir) {
        RopeHang h = ropeHangs.get(player.getUUID());
        if (h == null || dir == 0) return;
        int d = dir > 0 ? 1 : -1;

        long now = player.level().getGameTime();
        int interval = ManiacConfigs.get(ConfigSchema.ROPE_SWING_PRESS_INTERVAL_TICKS);
        boolean tooFast = now - h.lastPress < interval;
        boolean wrongWay = Math.abs(h.omega) > ROPE_REST_OMEGA && d * h.omega < 0;
        h.lastPress = now;   // промах теж «використовує» вікно — спам не проходить

        if (tooFast || wrongWay) {
            h.omega *= 0.5;  // схибив — розгойдування слабшає
        } else {
            h.omega += ManiacConfigs.get(ConfigSchema.ROPE_SWING_KICK) * d;
        }
    }

    /** Знімає підвіс: жертва вільна (падає), нитка зникає, короткий імунітет. */
    private void endRopeHang(UUID victimId) {
        RopeHang h = ropeHangs.remove(victimId);
        victims.remove(victimId);
        LivingEntity v = playerOrBody(victimId);
        ManiacMod.lib().interactionLockModule()
            .unlock(victimId, LockType.MOVEMENT, LOCK_REASON);
        if (v != null) {
            v.setNoGravity(false);
            v.fallDistance = 0f;
            immunity.put(victimId, RELEASE_IMMUNITY_TICKS);
            if (v instanceof ServerPlayer player) {
                ModNetwork.toPlayer(player, new TrapEscapePromptPacket(false));
                MatchOrchestrator m = match();
                if (m != null) m.survivors().forceVitalsRefresh(player);
            }
        }
        if (h != null) {
            placed.remove(h.ropeId);
            if (v != null && v.level() instanceof ServerLevel vLevel
                && vLevel.getEntity(h.ropeId) instanceof RopeTrapEntity rope) {
                rope.discard();
            }
        }
        broadcastVictimGlow();
    }

    /**
     * ПКМ ножицями по підвішеному гравцеві (союзником) або самим підвішеним.
     * Ножиці одноразові — витрачаються, як і на дроті.
     */
    private boolean cutRope(ServerPlayer cutter, LivingEntity victim) {
        UUID vid = ManiacOffline.idOf(victim);
        if (vid == null || !ropeHangs.containsKey(vid)) return false;
        ItemStack scissors = scissorsInHand(cutter);
        if (scissors.isEmpty()) return false;

        victim.level().playSound(null, victim.blockPosition(),
            ModSounds.ROPE_ESCAPE.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
        scissors.shrink(1);
        endRopeHang(vid);
        if (victim instanceof ServerPlayer player) {
            notify(player, ActionBarMessageType.SUCCESS, "maniacmod.trap.rope_cut");
        }
        if (cutter != victim) notify(cutter, ActionBarMessageType.INFO, "maniacmod.trap.rope_cut_ally");
        return true;
    }

    /** Онлайн-гравець або його живе тіло: те, що фізично висить/стоїть у пастці. */
    private LivingEntity playerOrBody(UUID id) {
        ServerPlayer player = onlinePlayer(id);
        if (player != null) return player;
        return bodyOf(id);
    }

    /**
     * Промінь погляду проти хітбоксів підвішених ІНШИХ гравців (блок на шляху
     * закриває ціль). Найближчого влученого — розрізає.
     */
    private boolean tryCutHangingInSight(ServerPlayer player) {
        if (ropeHangs.isEmpty()) return false;
        double range = ManiacConfigs.get(ConfigSchema.GEO_SCISSORS_RANGE_BLOCKS) + 0.5;
        Vec3 eye = VrServerCompat.aimOrigin(player);
        Vec3 end = eye.add(VrServerCompat.aimDirView(player).normalize().scale(range));

        double blockDist = range;
        var blockHit = player.level().clip(new net.minecraft.world.level.ClipContext(
            eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (blockHit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            blockDist = eye.distanceTo(blockHit.getLocation());
        }

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (UUID vid : ropeHangs.keySet()) {
            if (vid.equals(player.getUUID())) continue;
            LivingEntity v = playerOrBody(vid);
            if (v == null || v.level() != player.level()) continue;
            var hit = v.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceTo(hit.get());
            if (d > blockDist + 0.3 || d >= bestDist) continue;
            bestDist = d;
            best = v;
        }
        return best != null && cutRope(player, best);
    }

    // ── Звільнення ───────────────────────────────────────────────────────

    /**
     * Виживий вдарив по капкану чи димовій міні ломом (ЛКМ). Викликається
     * з хука {@code AttackEntityEvent}.
     *
     * Що робить удар залежить від стану капкана: якщо він захлопнув
     * жертву — звільняє її; якщо порожній — просто знешкоджує (зникає).
     * Це і «звільнити себе/тімейта», і «позбутись, поки не закрився».
     *
     * ── Хто платить за удар ──────────────────────────────────────────
     * Міцність і перезарядку списує САМЕ ЦЕЙ метод, і лише коли удар
     * зараховано: промах повз дальність чи удар ломом на перезарядці
     * нічого не коштує. Викликач (хук) лише передає лом із руки.
     *
     * @return true, якщо удар зараховано — хук тоді скасовує ванільний
     *         {@code attack}; false — не наш випадок, подію не чіпаємо
     */
    public boolean onCrowbarHit(ServerPlayer hitter, Entity trap, net.minecraft.world.item.ItemStack crowbar) {
        if (!(crowbar.getItem() instanceof com.log_to_kot.maniacmod.items.CrowbarItem)) return false;
        if (!placed.containsKey(trap.getUUID())) return false;
        // Шипи знешкодити НЕ можна (ні до спрацювання, ні після) — лом їх ігнорує.
        if (trap instanceof SpikeTrapEntity) return false;
        // Димова міна, що вже випустила хмару, — не ціль: розряджати нічого.
        if (trap instanceof SmokeMineEntity mine && mine.isTriggered()) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return false;
        if (!m.isSurvivor(hitter.getUUID())) return false;

        // Зламаний лом і лом на перезарядці не працюють. Повідомлення
        // даємо тут: гравець має зрозуміти, ЧОМУ клік нічого не зробив.
        if (com.log_to_kot.maniacmod.items.CrowbarItem.isBroken(crowbar)) {
            notify(hitter, ActionBarMessageType.ERROR, "maniacmod.trap.crowbar_broken");
            return true;
        }
        if (com.log_to_kot.maniacmod.items.OwnCooldowns.isOnCooldown(hitter, com.log_to_kot.maniacmod.items.CrowbarItem.COOLDOWN_KEY)) {
            notify(hitter, ActionBarMessageType.COOLDOWN, "maniacmod.trap.crowbar_cooldown");
            return true;
        }

        double range = ManiacConfigs.get(ConfigSchema.CROWBAR_RANGE_BLOCKS);
        if (hitter.getEyePosition().distanceToSqr(trap.position().add(0, 0.2, 0)) > range * range) return false;

        // Удар зараховано (дальність, перезарядка й міцність уже
        // перевірені) — звук із місця капкана, чутний обом сторонам.
        hitter.level().playSound(null, trap.blockPosition(),
            ModSounds.CROWBAR_HIT.get(), SoundSource.PLAYERS, 1.0f, 1.0f);

        // "Жива" анімація удару тілом гравця (playerlib) замість
        // GeckoLib-тригера на самому предметі — див. CrowbarItem
        // клас-докстрінг щодо синхронізації руки/предмета.
        com.log_to_kot.maniacmod.items.CrowbarItem.playHitLive(hitter);

        UUID trappedId = victimOf(trap.getUUID());
        if (trappedId != null && trap instanceof BearTrapEntity bearTrap) {
            ServerPlayer victim = onlinePlayer(trappedId);
            if (victim != null) release(victim, bearTrap);
            else if (ManiacOffline.hasBody(trappedId)) releaseBody(trappedId);
        }
        trap.discard();
        placed.remove(trap.getUUID());
        struggleCooldown.remove(trap.getUUID());

        int left = com.log_to_kot.maniacmod.items.CrowbarItem.wear(crowbar);
        // 0% — лом ламається повністю й зникає з руки.
        com.log_to_kot.maniacmod.items.CrowbarItem.breakIfWornOut(hitter, crowbar);
        com.log_to_kot.maniacmod.items.OwnCooldowns.start(hitter, com.log_to_kot.maniacmod.items.CrowbarItem.COOLDOWN_KEY,
            ManiacConfigs.get(ConfigSchema.CROWBAR_COOLDOWN_TICKS));
        notify(hitter, left > 0 ? ActionBarMessageType.INFO : ActionBarMessageType.ERROR,
            left > 0 ? "maniacmod.trap.crowbar_used" : "maniacmod.trap.crowbar_broke",
            String.valueOf(left));
        return true;
    }

    /** Знімає лок із жертви, дає короткий імунітет. Сам капкан лишається на розсуд викликача. */
    private void release(ServerPlayer victim, BearTrapEntity trap) {
        victims.remove(victim.getUUID());
        // Спроба визволення, якщо була, теж закінчена: лок знято, екран
        // має закритись (це шлях звільнення ломом; успіх міні-гри шле свій
        // результат ДО виклику release).
        closeEscape(victim);
        ManiacMod.lib().interactionLockModule()
            .unlock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);
        immunity.put(victim.getUUID(), RELEASE_IMMUNITY_TICKS);

        // Той самий форс-знімок, що в trigger(): trapped має злетіти на
        // клієнті миттєво, звільнений гравець рухається одразу після удару
        // ломом, а не через кадр-два.
        MatchOrchestrator m = match();
        if (m != null) m.survivors().forceVitalsRefresh(victim);

        // Підсвітка знімається разом із локом — інакше вільний гравець
        // ще світився б контуром «в пастці» (ТЗ 1.1 вимагає чіткої
        // відповідності підсвітки стану).
        broadcastVictimGlow();
    }

    /**
     * Те саме, що {@link #release}, для ТІЛА: власник офлайн, тож немає ні мінііри
     * визволення, ні пакетів клієнту. Повернувшись, він уже вільний.
     */
    private void releaseBody(UUID ownerId) {
        victims.remove(ownerId);
        ManiacMod.lib().interactionLockModule()
            .unlock(ownerId, LockType.MOVEMENT, LOCK_REASON);
        immunity.put(ownerId, RELEASE_IMMUNITY_TICKS);
        broadcastVictimGlow();
    }

    /**
     * Тіло власника зникло зі складу матчу (вбите). Усе, що пастки тримали за його
     * UUID: звільнення капкана, дріт під струмом, лок руху, підсвітка, спроба
     * визволення, бо ні {@link #releaseInvalidVictims}, ні {@link #onPlayerLeft}
     * вже не побачать його серед онлайн-гравців.
     */
    public void onOfflineBodyGone(UUID owner) {
        // Підвішене тіло: прибираємо нитку (endRopeHang сам знімає лок і жертву).
        if (ropeHangs.containsKey(owner)) endRopeHang(owner);
        escapes.remove(owner);
        escapePromptTicks.remove(owner);
        escapePromptTrap.remove(owner);
        immunity.remove(owner);
        trails.remove(owner);
        smokeMuffled.remove(owner);
        smokeStaminaLock.remove(owner);
        smokeGlow.remove(owner);

        UUID trapId = victims.remove(owner);
        ManiacMod.lib().interactionLockModule()
            .unlock(owner, LockType.MOVEMENT, LOCK_REASON);
        // Дріт, що бив цю жертву: запис шоку знімаємо тут (сам дріт прибере tickShock).
        wireShocks.values().removeIf(shock -> shock.victimId().equals(owner));
        if (trapId != null) {
            BearTrapEntity trap = bearTrapAnywhere(trapId);
            if (trap != null) trap.setSnapped(false);
        }
        broadcastVictimGlow();
    }

    /** Капкан за UUID у будь-якому завантаженому світі (власника-гравця для контексту немає). */
    private BearTrapEntity bearTrapAnywhere(UUID trapId) {
        MatchOrchestrator m = match();
        if (trapId == null || m == null || m.server() == null) return null;
        for (ServerLevel level : m.server().getAllLevels()) {
            if (level.getEntities().get(trapId) instanceof BearTrapEntity trap) return trap;
        }
        return null;
    }

    /** Живе тіло власника або {@code null}. */
    private LivingEntity bodyOf(UUID id) {
        MatchOrchestrator m = match();
        if (m == null || m.server() == null || !ManiacOffline.hasBody(id)) return null;
        return ManiacOffline.actor(m.server(), id).orElse(null);
    }

    /**
     * Надсилає всім актуальний склад підсвічених жертв. ПОВНИЙ набір, а
     * не дельту (див. {@code TrapVictimGlowPacket}) — тому викликається
     * після будь-якої зміни {@link #victims}, а не лише на додавання.
     *
     * <p>Шлемо {@code toAll}, а не в конкретний вимір: клієнт світить
     * лише ті сутності, які справді рендерить, тож зайвий запис у
     * реєстрі нічого не показує. Так не треба знати, у якому вимірі
     * кожна жертва, і поведінка лишається коректною, навіть якщо
     * пастки колись стануть можливими в кількох вимірах.</p>
     */
    private void broadcastVictimGlow() {
        MatchOrchestrator m = match();
        int[] ids = new int[0];
        if (m != null && (!victims.isEmpty() || !smokeGlow.isEmpty())) {
            // Жертви капкана/дроту/мотузки + ті, хто світиться після димової міни.
            Set<UUID> glowing = new HashSet<>(victims.keySet());
            glowing.addAll(smokeGlow.keySet());
            List<Integer> collected = new ArrayList<>();
            for (UUID victimId : glowing) {
                ServerPlayer victim = m.onlinePlayer(victimId);
                if (victim != null) {
                    collected.add(victim.getId());
                } else {
                    // Власник офлайн: світиться його тіло (та сама жертва, інша сутність).
                    LivingEntity body = bodyOf(victimId);
                    if (body != null) collected.add(body.getId());
                }
            }
            ids = new int[collected.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = collected.get(i);
        }
        ModNetwork.toAll(new TrapVictimGlowPacket(TrapVictimGlowPacket.VICTIM_COLOR, ids));
    }

    private UUID victimOf(UUID trapId) {
        for (Map.Entry<UUID, UUID> e : victims.entrySet()) {
            if (e.getValue().equals(trapId)) return e.getKey();
        }
        return null;
    }

    /**
     * Звільняє тих, кого капкан уже НЕ має тримати: тих, хто вийшов із
     * сервера, втратив роль виживого, або збитий з ніг / вибув.
     *
     * ── Чому лежачого капкан відпускає ─────────────────────────────────
     * Те саме правило, що {@link #canCatch}, лише з іншого боку: пастка
     * тримає того, хто СТОЇТЬ у ній. Гравець, що втратив свідомість,
     * лежить — тримати його «в зубах» означало б забороняти йому навіть
     * повзти, доки не скінчиться таймер смерті. Тому капкан
     * розкривається (знову бойовий, але без жертви), а лок знімається.
     * Імунітет НЕ виставляємо: лежачий і без нього не спрацьовує пастки.
     */
    private void releaseInvalidVictims(List<ServerPlayer> players) {
        MatchOrchestrator m = match();
        for (UUID id : new ArrayList<>(victims.keySet())) {
            ServerPlayer p = onlinePlayer(id);
            // «Присутній» = онлайн або стоїть тілом: тіло в капкані лишається в капкані.
            boolean present = p != null || ManiacOffline.hasBody(id);
            boolean stillTrapWorthy = present && m.isSurvivor(id)
                && standsUp(m.survivorStateOf(id));
            if (stillTrapWorthy) continue;

            // Підвішений упав/вибув/вийшов — нитка відпускає (і зникає).
            if (ropeHangs.containsKey(id)) {
                endRopeHang(id);
                continue;
            }

            UUID trapId = victims.remove(id);

            // Лок знімаємо й для офлайн-жертви: інакше він лишився б у реєстрі lib назавжди.
            ManiacMod.lib().interactionLockModule()
                .unlock(id, LockType.MOVEMENT, LOCK_REASON);
            if (p == null) {
                BearTrapEntity orphan = bearTrapAnywhere(trapId);
                if (orphan != null) orphan.setSnapped(false);
                continue;
            }
            closeEscape(p);

            BearTrapEntity trap = trapEntity(trapId, p);
            if (trap != null) trap.setSnapped(false);
        }
        // Хтось із жертв перестав бути «в пастці» (вибув/ліг/вийшов) —
        // склад підсвітки змінився, навіть якщо жодного разу не
        // потрапляли в гілку вище.
        broadcastVictimGlow();
    }

    private void releaseEveryone(List<ServerPlayer> players) {
        // Підвішені: повна розв'язка (гравітація — вона зберігається в NBT
        // гравця!, лок, підказка F, нитка, підсвітка) — так само, як при розриві.
        for (UUID id : new ArrayList<>(ropeHangs.keySet())) {
            endRopeHang(id);
        }
        ropeHangs.clear();
        for (UUID id : new ArrayList<>(victims.keySet())) {
            ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
            ServerPlayer victim = onlinePlayer(id);
            // Кінець матчу — гарантовано гасимо і прапор тряски камери
            // (WireShockPacket), інакше він лишився б висіти на клієнті,
            // якщо гравець був під струмом у момент завершення.
            if (victim != null) ModNetwork.toPlayer(victim, WireShockPacket.stop());
        }
        victims.clear();
        wireShocks.clear();
        pendingFirstPoint.clear();
        broadcastVictimGlow();
    }

    /** Гравець вийшов із сервера — не лишаємо замок і запис у мапах. */
    public void onPlayerLeft(ServerPlayer player) {
        UUID id = player.getUUID();
        boolean hasBody = ManiacOffline.hasBody(id);
        RopeHang leftHang = hasBody ? ropeHangs.get(id) : ropeHangs.remove(id);
        if (leftHang != null && !hasBody) {
            player.setNoGravity(false);   // інакше після перезаходу висітиме в повітрі
            placed.remove(leftHang.ropeId);
        }
        // Тіло в капкані, на дроті чи на мотузці ТРИМАЄ жертву: запис і лок руху лишаються,
        // бо власник повернеться в ту саму пастку (звільнити можна ломом/ножицями або
        // добити таймером кровотечі). Підвішене тіло висить далі: нитку веде сервер за
        // сутністю (див. tickRopeHang), а id нитки він оновлює сам.
        boolean keepAsBody = hasBody && victims.containsKey(id);
        if (keepAsBody) {
            broadcastVictimGlow();   // підсвітка переходить з гравця на його тіло
        } else if (victims.remove(id) != null) {
            ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
            broadcastVictimGlow();
        }
        immunity.remove(id);
        trails.remove(id);
        smokeMuffled.remove(id);
        smokeStaminaLock.remove(id);
        if (smokeGlow.remove(id) != null) broadcastVictimGlow();
        // Спроба визволення (і підказка "Натисни F") померли разом із
        // сесією: пакет шукати нема кому.
        escapes.remove(id);
        escapePromptTicks.remove(id);
        escapePromptTrap.remove(id);
    }

    /**
     * Власник повернувся до свого тіла. Якщо тіло трималося в пастці, власник знову
     * онлайн з НОВИМ id сутності: підсвітку треба перебудувати, а під шоком клієнту
     * повторно віддати прапор тряски камери (пакет пішов би лише тоді, коли гравець був онлайн).
     */
    public void onPlayerReturned(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!victims.containsKey(id)) return;
        // Повернувся в пастку: гравцеві знову доступна спроба вибратися (як якщо б він
        // не виходив). Капкан: підказка F; мотузка: нитка веде до нового id сутності.
        UUID trapId = victims.get(id);
        if (ropeHangs.containsKey(id)) {
            ModNetwork.toPlayer(player, new TrapEscapePromptPacket(true));
        } else if (trapId != null && !wireShocks.containsKey(trapId)) {
            BearTrapEntity bear = bearTrapAnywhere(trapId);
            if (bear != null) openEscapePrompt(player, bear);
        }
        for (WireShock shock : wireShocks.values()) {
            if (shock.victimId().equals(id)) {
                ModNetwork.toPlayer(player, new WireShockPacket(true,
                    ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_DEGREES),
                    ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_HZ)));
                break;
            }
        }
        broadcastVictimGlow();
    }

    // ── Запити ───────────────────────────────────────────────────────────

    /** Хто зараз у пастці. Для SurvivorModule: у пастці нога не гоїться. */
    public Set<UUID> victimIds() {
        return Set.copyOf(victims.keySet());
    }

    /** Чи гравець зараз у пастці. */
    public boolean isTrapped(UUID playerId) {
        return victims.containsKey(playerId);
    }

    // ── Тікання ──────────────────────────────────────────────────────────

    /** Коротке повідомлення гравцю (action bar). Ключі — у lang. */
    private void notify(ServerPlayer player, ActionBarMessageType type, String key, String... args) {
        ModNetwork.toPlayer(player, new ActionBarPacket(type, key, args));
    }

    private void tickSlotCooldowns(List<ServerPlayer> players) {
        for (int i = 0; i < slotCooldown.length; i++) {
            if (slotCooldown[i] > 0) slotCooldown[i]--;
        }
    }

    private void tickImmunity() {
        immunity.replaceAll((id, ticks) -> ticks - 1);
        immunity.values().removeIf(ticks -> ticks <= 0);
    }

    private ServerPlayer onlinePlayer(UUID id) {
        MatchOrchestrator m = match();
        return m == null ? null : m.onlinePlayer(id);
    }
}

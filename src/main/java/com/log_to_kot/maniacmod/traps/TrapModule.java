package com.log_to_kot.maniacmod.traps;

import com.log_to_kot.maniacmod.ManiacMod;
import com.log_to_kot.maniacmod.config.ConfigSchema;
import com.log_to_kot.maniacmod.config.ManiacConfigs;
import com.log_to_kot.maniacmod.core.match.MatchOrchestrator;
import com.log_to_kot.maniacmod.core.phase.GamePhase;
import com.log_to_kot.maniacmod.core.phase.PhaseListener;
import com.log_to_kot.maniacmod.core.phase.PhaseRule;
import com.log_to_kot.maniacmod.entity.BearTrapEntity;
import com.log_to_kot.maniacmod.entity.ElectricWireEntity;
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
import com.log_to_kot.maniacmod.net.s2c.notify.WireShockPacket;
import com.log_to_kot.maniacmod.net.s2c.minigame.TrapEscapeAttemptPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapCatalogPacket;
import com.log_to_kot.maniacmod.net.s2c.traps.TrapLoadoutPacket;
import com.log_to_kot.maniacmod.registry.ModSounds;
import com.log_to_kot.maniacmod.sound.OggSoundLength;
import com.log_to_kot.maniacmod.survivors.SurvivorState;
import dev.shaurmalib.common.overlay.ActionBarMessageType;
import dev.shaurmalib.common.lock.LockType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 *   • {@link #immunity}    — короткий імунітет жертви після звільнення.
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
                    if (m.isManiac(p.getUUID())) syncCatalogIfChoiceNeeded(p, archetype);
                }
            }
        }

        // Вхід в ігрову фазу. Якщо маньяк не встиг обрати пастки в меню
        // (дебаг, старт командою, вибір відкладено чи не був потрібен) —
        // беремо всі, до ліміту: маньяк не має лишитись без пасток лише
        // через те, що не було кому натиснути. Клієнту одразу шлемо набір.
        if (phase.allows(PhaseRule.TRAPS)) {
            MatchOrchestrator m = match();
            if (m != null && chosenSlots.isEmpty()) chooseAll(m.maniacArchetype());
            for (ServerPlayer p : players) {
                if (m != null && m.isManiac(p.getUUID())) syncLoadout(p);
            }
            return;
        }

        // Стан пасток живе лише в ігрових фазах. Вихід у неігрову
        // (кінець, скидання, лобі) обов'язково розморожує жертв.
        clearLoadoutOnClient(players);
        releaseEveryone(players);
        placed.clear();
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
        closeAllEscapes(players);
        escapePromptTicks.clear();
        escapePromptTrap.clear();
        java.util.Arrays.fill(slotCooldown, 0);
        // chosenSlots чистимо лише при поверненні в LOBBY/RESET: вибір у
        // меню робиться ДО ігрової фази (ROLE_REVEAL — теж неігрова), і
        // стирати його на вході в ROLE_REVEAL означало б знищити вибір,
        // який щойно зроблено.
        if (phase == GamePhase.LOBBY || phase == GamePhase.RESET) resetChoice();
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
     * Автовибір: усі пастки архетипу до ліміту. Викликається, коли меню
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
            ModSounds.TRAP_PLACE.get(), SoundSource.BLOCKS, 1.0f, 1.0f);
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

        return type.validatePlacement(level, a, b);
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
                x -> x instanceof BearTrapEntity)) {
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
        for (ServerPlayer candidate : trap.level().getEntitiesOfClass(ServerPlayer.class, zone)) {
            if (immunity.containsKey(candidate.getUUID())) continue;
            if (victims.containsKey(candidate.getUUID())) continue;
            // Лежачого пастка не ловить — і це перевіряється ОДИН раз тут,
            // на всі типи пасток, а не в кожній архетипі (див. canCatch).
            if (!canCatch(candidate)) continue;
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
        MatchOrchestrator m = match();
        if (m == null || !m.isSurvivor(candidate.getUUID())) return false;
        return standsUp(m.survivorStateOf(candidate.getUUID()));
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
        // прискорення). Саме торкання — ТОЧНЕ: відрізок дроту проти
        // справжнього AABB гравця, тож ноги теж рахуються, а кут
        // «порожнього прямокутника» діагоналі — ні.
        for (ServerPlayer candidate : wire.level().getEntitiesOfClass(ServerPlayer.class,
                wire.getBoundingBoxForCulling().inflate(0.5))) {
            if (immunity.containsKey(candidate.getUUID())) continue;
            if (victims.containsKey(candidate.getUUID())) continue;
            if (!canCatch(candidate)) continue;
            if (!info.type().shouldTrigger(candidate)) continue;
            if (!WireGeometry.segmentTouchesBox(a, b, candidate.getBoundingBox())) continue;
            startShock(wire, candidate);
            return;
        }
    }

    /** Жертва торкнулась дроту: починаємо удар струмом (ТЗ 5). */
    private void startShock(ElectricWireEntity wire, ServerPlayer victim) {
        wireShocks.put(wire.getUUID(), new WireShock(victim.getUUID(),
            ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHOCK_TICKS), 0));
        victims.put(victim.getUUID(), wire.getUUID());
        ManiacMod.lib().interactionLockModule()
            .lock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);

        MatchOrchestrator m = match();
        if (m != null) m.survivors().forceVitalsRefresh(victim);
        // Окремий прапор для клієнта: рух і так глушиться trapped, але
        // дріт додатково блокує камеру й трясе нею (ТЗ 5).
        ModNetwork.toPlayer(victim, new WireShockPacket(true,
            ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_DEGREES),
            ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_SHAKE_HZ)));
        broadcastVictimGlow();
        playZap(wire, 0);
    }

    /** Раз на тік активного удару: секундна шкода, почергові звуки, частинки, завершення. */
    private void tickShock(ElectricWireEntity wire, WireShock shock) {
        MatchOrchestrator m = match();
        ServerPlayer victim = onlinePlayer(shock.victimId());
        // Жертва зникла або більше не «на ногах» (непритомність — пастка
        // мусить відпустити, ТЗ 7) — завершуємо й прибираємо пастку.
        if (victim == null || m == null || !canCatch(victim)) {
            endShock(wire, victim, true);
            return;
        }

        if (shock.elapsed() % 20 == 0) {
            int damage = ManiacConfigs.get(ConfigSchema.ELECTRIC_WIRE_DAMAGE_PER_SECOND);
            if (damage > 0 && m.damageSurvivor(victim.getUUID(), damage)) {
                m.survivors().onSurvivorDowned(victim);
            }
            playZap(wire, shock.elapsed() / 20);
        }
        spawnShockParticles(victim);

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
        wireShocks.remove(wire.getUUID());
        if (victim != null) {
            victims.remove(victim.getUUID());
            ManiacMod.lib().interactionLockModule()
                .unlock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);
            ModNetwork.toPlayer(victim, WireShockPacket.stop());
            // Імунітет — як після капкана: гравець стоїть на тому місці,
            // де був дріт.
            immunity.put(victim.getUUID(), RELEASE_IMMUNITY_TICKS);
            MatchOrchestrator m = match();
            if (m != null) m.survivors().forceVitalsRefresh(victim);
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

        ServerLevel level = player.serverLevel();
        double range = ManiacConfigs.get(ConfigSchema.GEO_SCISSORS_RANGE_BLOCKS);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0f).normalize();

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
    private static void spawnShockParticles(ServerPlayer victim) {
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

    private void trigger(BearTrapEntity trap, PlacedTrap info, ServerPlayer victim) {
        trap.setSnapped(true);
        victims.put(victim.getUUID(), trap.getUUID());
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
        victim.teleportTo(trap.getX(), trap.getY(), trap.getZ());
        ManiacMod.lib().interactionLockModule()
            .lock(victim.getUUID(), LockType.MOVEMENT, LOCK_REASON);

        info.type().applyEffect(victim, trap.blockPosition());

        // Форс-знімок HUD одразу, а не на наступному throttled тіку:
        // клієнтський блок руху (MixinKeyboardInputTrapped) читає поле
        // trapped із SurvivorVitalsPacket і має спрацювати в той самий
        // момент, що серверний лок, інакше жертва встигає на кадр-два
        // зрушити з місця до прильоту пакета.
        MatchOrchestrator m = match();
        if (m != null) m.survivors().forceVitalsRefresh(victim);

        // А вже потім — підказка "Натисни F": клієнт вибирає її вигляд
        // за власним станом «я в пастці» (той самий стиль тексту, що
        // GeneratorHintOverlay), тож знімок HUD мусить прийти раніше за
        // цей стан (інакше на кадр блимнула б чужа підказка).
        openEscapePrompt(victim, trap);
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

    // ── Звільнення ───────────────────────────────────────────────────────

    /**
     * Виживий вдарив по капкану ломом (ЛКМ). Викликається з хука
     * {@code AttackEntityEvent}.
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
    public boolean onCrowbarHit(ServerPlayer hitter, BearTrapEntity trap, net.minecraft.world.item.ItemStack crowbar) {
        if (!(crowbar.getItem() instanceof com.log_to_kot.maniacmod.items.CrowbarItem)) return false;
        if (!placed.containsKey(trap.getUUID())) return false;
        MatchOrchestrator m = match();
        if (m == null || !m.phases().allows(PhaseRule.TRAPS)) return false;
        if (!m.isSurvivor(hitter.getUUID())) return false;

        // Зламаний лом і лом на перезарядці не працюють. Повідомлення
        // даємо тут: гравець має зрозуміти, ЧОМУ клік нічого не зробив.
        if (com.log_to_kot.maniacmod.items.CrowbarItem.isBroken(crowbar)) {
            notify(hitter, ActionBarMessageType.ERROR, "maniacmod.trap.crowbar_broken");
            return true;
        }
        if (hitter.getCooldowns().isOnCooldown(crowbar.getItem())) {
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
        if (trappedId != null) {
            ServerPlayer victim = onlinePlayer(trappedId);
            if (victim != null) release(victim, trap);
        }
        trap.discard();
        placed.remove(trap.getUUID());
        struggleCooldown.remove(trap.getUUID());

        int left = com.log_to_kot.maniacmod.items.CrowbarItem.wear(crowbar);
        hitter.getCooldowns().addCooldown(crowbar.getItem(),
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
        if (m != null && !victims.isEmpty()) {
            List<Integer> collected = new ArrayList<>();
            for (UUID victimId : victims.keySet()) {
                ServerPlayer victim = m.onlinePlayer(victimId);
                if (victim != null) collected.add(victim.getId());
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
            boolean stillTrapWorthy = p != null && m.isSurvivor(id)
                && standsUp(m.survivorStateOf(id));
            if (stillTrapWorthy) continue;

            UUID trapId = victims.remove(id);
            if (p == null) continue;

            ManiacMod.lib().interactionLockModule()
                .unlock(id, LockType.MOVEMENT, LOCK_REASON);
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
        if (victims.remove(id) != null) {
            ManiacMod.lib().interactionLockModule().unlock(id, LockType.MOVEMENT, LOCK_REASON);
            broadcastVictimGlow();
        }
        immunity.remove(id);
        // Спроба визволення (і підказка "Натисни F") померли разом із
        // сесією: пакет шукати нема кому.
        escapes.remove(id);
        escapePromptTicks.remove(id);
        escapePromptTrap.remove(id);
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
